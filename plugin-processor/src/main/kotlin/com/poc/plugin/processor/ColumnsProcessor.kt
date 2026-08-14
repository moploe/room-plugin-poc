package com.poc.plugin.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import java.io.OutputStreamWriter

private const val ENTITY_ANNOTATION = "androidx.room3.Entity"
private const val DATABASE_ANNOTATION = "androidx.room3.Database"
private const val DAO_ANNOTATION = "androidx.room3.Dao"

/**
 * Reads plain androidx.room3 @Entity / @Dao / @Database classes (no new annotations to
 * learn) and generates, per entity:
 *  - a "<Name>Columns" object with typed com.poc.plugin.runtime.*Column vals, for the
 *    Where DSL
 *  - a "<Name>ExpectedColumns()" function (ADD-COLUMN-ready, excludes the primary key -
 *    SQLite forbids adding PRIMARY KEY/UNIQUE via ALTER)
 *  - a "<Name>CreateTableSql()" function - the full CREATE TABLE IF NOT EXISTS, including
 *    primary key (single autoincrement or composite), foreign keys
 *  - a "<Name>Indices()" function - one IndexDef per @Index on the entity
 *  - for every @Embedded field, its nested type's own properties are flattened into this
 *    entity's column list (honoring the prefix), recursively
 *  - for every field whose type is unsupported but @Serializable, a shared
 *    "<Type>DefaultJsonConverter" object (deduplicated across entities)
 *  - for every field whose type is a Kotlin enum, a TEXT column (Room's own built-in enum
 *    converter handles the actual value <-> name conversion, we just need to know it's a
 *    string column for schema/Where-DSL purposes)
 *
 * ...and per @Dao:
 *  - for every method `@RawQuery fun x(query: RoomRawQuery): List<Entity>`, a same-named
 *    extension function taking a Where instead, wired to the entity's table name
 *
 * ...and per @Database:
 *  - a "<Name>AutoMigrations()" function: one Migration(v, currentVersion) per possible
 *    prior version, all diff-based so ANY older version upgrades directly (no chained
 *    intermediate migrations needed), and each one creates whole new tables (a brand new
 *    @Entity added since the stored version) as well as diffing columns on tables that
 *    already exist
 *  - a "<Name>Builder(path)" function pre-wired with the above, so call sites never need
 *    to reference *AutoMigrations() at all
 *
 * Scope: Long/Int/Short/Byte/String/Double/Float/Boolean/ByteArray map directly to SQL
 * columns; enums map to TEXT; anything else is either @Embedded (flattened), @Serializable
 * (gets an auto JSON converter), or skipped with a warning. Composite primary keys, indices
 * and foreign keys are read for CREATE TABLE / CREATE INDEX purposes. Column deletion,
 * renames and type changes are NOT handled - SQLite's ALTER support for those varies by
 * version across real Android OS builds, so this stays intentionally conservative to
 * portable, universally-supported operations (CREATE TABLE, ADD COLUMN, CREATE INDEX).
 */
class ColumnsProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    // both configurable via KSP processor options (see ColumnsProcessorProvider) rather than
    // requiring a fork of the plugin to change - e.g. in the consuming module's
    // build.gradle.kts: ksp { arg("roomPluginMaxNestDepth", "5") }
    private val maxNestDepth: Int = 3,
    private val sqliteInChunkSize: Int = 900,
) : SymbolProcessor {

    private data class TypeMapping(val columnClass: String, val sqlType: String)

    private val supportedTypes = mapOf(
        "kotlin.Long" to TypeMapping("LongColumn", "INTEGER"),
        "kotlin.Int" to TypeMapping("IntColumn", "INTEGER"),
        "kotlin.Short" to TypeMapping("ShortColumn", "INTEGER"),
        "kotlin.Byte" to TypeMapping("ByteColumn", "INTEGER"),
        "kotlin.String" to TypeMapping("StringColumn", "TEXT"),
        "kotlin.Double" to TypeMapping("DoubleColumn", "REAL"),
        "kotlin.Float" to TypeMapping("FloatColumn", "REAL"),
        "kotlin.Boolean" to TypeMapping("BooleanColumn", "INTEGER"),
        "kotlin.ByteArray" to TypeMapping("ByteArrayColumn", "BLOB"),
    )

    private enum class ColumnKind { PRIMITIVE, ENUM, JSON_CONVERTER }

    private data class ColumnDef(
        val propName: String,
        val columnName: String,
        val sqlType: String,
        // null for a column backed by a @ColumnTypeConverter (e.g. JSON) - it's still a
        // real column that must exist in CREATE TABLE / ExpectedColumns, it just doesn't
        // get a typed Where-DSL val since we can't reason about comparisons on an opaque
        // converter-produced value.
        val columnRefClass: String?,
        val nullable: Boolean,
        val defaultValue: String?,
        val isPrimaryKey: Boolean,
        // fully-qualified Kotlin type, and enough of a tag to know how to read a value of
        // it back out of a SQLiteStatement - only used by relation-fetch codegen.
        val kotlinTypeName: String,
        val kind: ColumnKind,
        // only meaningful for kind == JSON_CONVERTER: true if the field has its own
        // @ColumnTypeConverters override rather than using the auto-generated default
        // (jsonEncode/jsonDecode) one. We can't safely infer an arbitrary user-supplied
        // converter's decode call, so entities with any such column stay excluded from
        // relation-fetch codegen (see EntityAnalysis.readableForRelations).
        val hasExplicitConverter: Boolean = false,
    )

    // Mirrors an entity's actual (possibly @Embedded-nested) constructor shape, so
    // read<Entity>() can reconstruct nested objects instead of assuming every property is a
    // flat column - built in lockstep with the flat `columns` list in collectColumns(), so a
    // Scalar leaf's `index` always matches that column's position in `columns` (== its
    // position in `SELECT *`).
    private sealed class FieldNode {
        data class Scalar(val col: ColumnDef, val index: Int) : FieldNode()
        data class Embedded(val propName: String, val typeSimpleName: String, val nullable: Boolean, val children: List<FieldNode>) : FieldNode()
    }

    private data class IndexInfo(val name: String, val unique: Boolean, val columns: List<String>)

    private data class ForeignKeyInfo(
        val childColumns: List<String>,
        val parentTable: String,
        val parentColumns: List<String>,
        val onDelete: String?,
        val onUpdate: String?,
    )

    private data class EntityAnalysis(
        val tableName: String,
        val columns: List<ColumnDef>,
        val primaryKeyColumns: List<String>,
        val autoIncrement: Boolean,
        val indices: List<IndexInfo>,
        val foreignKeys: List<ForeignKeyInfo>,
        val jsonConverterTypes: Set<String>,
        val fieldTree: List<FieldNode>,
        // relation-fetch codegen bypasses Room's Dao layer entirely (see the delete-by-Where
        // comment for why) and has to reconstruct entity instances from raw statement rows
        // itself, using `fieldTree` above - @Embedded is fully supported (reconstructed
        // recursively, including the "all descendant columns null => property is null" case
        // for a nullable @Embedded field) and so is a @ColumnTypeConverter field as long as
        // it uses the auto-generated default JSON converter (calls jsonDecode<T> directly,
        // bypassing Room's own converter object entirely). An explicit custom
        // @ColumnTypeConverters override is the one thing that still excludes an entity -
        // there's no safe way to infer an arbitrary user-supplied converter's decode call.
        val readableForRelations: Boolean,
    )

    // one-to-one/one-to-many relation, auto-detected from a single-column FK.
    private data class RelationSpec(
        val parentDecl: KSClassDeclaration,
        val childDecl: KSClassDeclaration,
        val fk: ForeignKeyInfo,
        val oneToOne: Boolean,
    )

    // many-to-many relation, auto-detected from a "junction-shaped" entity.
    private data class ManyToManySpec(
        val leftDecl: KSClassDeclaration,
        val rightDecl: KSClassDeclaration,
        val junctionDecl: KSClassDeclaration,
        val leftFkColumn: String,
        val rightFkColumn: String,
    )

    // a nested-relation "spine" edge is what a chain can pass *through* to keep going -
    // either a single-column FK relation (1:1 or 1:N - next stop: the child either way, just
    // 0-or-1 vs 0-or-many of them) or a M:N relation (next stop: the right side, reached via
    // the junction table).
    private sealed class SpineEdge {
        abstract val nextDecl: KSClassDeclaration
        data class SingleFk(val rel: RelationSpec) : SpineEdge() {
            override val nextDecl get() = rel.childDecl
        }
        data class ManyToMany(val rel: ManyToManySpec) : SpineEdge() {
            override val nextDecl get() = rel.rightDecl
        }
    }

    private val jsonConverterTypes = mutableMapOf<String, KSClassDeclaration>() // qualified name -> decl
    private val entityAnalyses = mutableMapOf<String, EntityAnalysis>() // key: entity qualified name

    // KSP may invoke process() more than once per compilation (multi-round processing,
    // triggered whenever a previous round generates new files) - the resolver keeps
    // returning the SAME original symbols in every round, so without these guards we'd
    // try to createNewFile the same output twice and crash.
    private val generatedForEntity = mutableSetOf<String>()
    private val generatedConverterFor = mutableSetOf<String>()
    private val generatedForDatabase = mutableSetOf<String>()
    private val generatedForDao = mutableSetOf<String>()
    // relation wrapper data classes are shaped only by the (parent, child) entity pair, not
    // by which @Database is fetching them - generated once globally, shared across every
    // database that happens to reference the same pair (avoids a "Redeclaration" clash when
    // e.g. several @Database versions in a migration-version test all include the same
    // parent+child entities).
    private val generatedRelationWrapperFor = mutableSetOf<Pair<String, String>>()
    // same "generate once globally, keyed on identity not on which @Database asked" reasoning
    // as generatedRelationWrapperFor above, for two-level nested relation wrappers.
    private val generatedNestedWrapperFor = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val entities = resolver.getSymbolsWithAnnotation(ENTITY_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        // Analyze all entities up front (idempotent via the map itself) - Dao/Database
        // generation below need to cross-reference other entities' table names/columns.
        for (entity in entities) {
            val qualifiedName = entity.qualifiedName?.asString() ?: continue
            entityAnalyses.getOrPut(qualifiedName) { analyzeEntity(entity) }
        }

        for (entity in entities) {
            val qualifiedName = entity.qualifiedName?.asString() ?: continue
            if (!generatedForEntity.add(qualifiedName)) continue
            generateEntityFile(entity, entityAnalyses.getValue(qualifiedName))
        }

        generateJsonConverters()

        val daos = resolver.getSymbolsWithAnnotation(DAO_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        for (dao in daos) {
            val qualifiedName = dao.qualifiedName?.asString() ?: continue
            if (!generatedForDao.add(qualifiedName)) continue
            processDao(dao)
        }

        val databases = resolver.getSymbolsWithAnnotation(DATABASE_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        for (db in databases) {
            val qualifiedName = db.qualifiedName?.asString() ?: continue
            if (!generatedForDatabase.add(qualifiedName)) continue
            processDatabase(db)
        }

        return emptyList()
    }

    // ---------------- shared helpers ----------------

    private fun argValue(anno: KSAnnotation, argName: String): Any? =
        anno.arguments.firstOrNull { it.name?.asString() == argName }?.value

    private fun stringList(anno: KSAnnotation, argName: String): List<String> =
        (argValue(anno, argName) as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun tableNameOf(entity: KSClassDeclaration): String {
        val anno = entity.annotations.first { it.shortName.asString() == "Entity" }
        val raw = argValue(anno, "tableName") as? String
        return raw?.takeIf { it.isNotBlank() } ?: entity.simpleName.asString()
    }

    /**
     * A Kotlin annotation with no explicit use-site target, written on a primary
     * constructor property, can bind to either the property or the constructor
     * parameter depending on the annotation's own @Target - callers can't rely on
     * KSPropertyDeclaration.annotations alone to see it. Union both sources.
     */
    private fun annotationsOf(owner: KSClassDeclaration, prop: KSPropertyDeclaration): List<KSAnnotation> {
        val fromProp = prop.annotations.toList()
        val paramName = prop.simpleName.asString()
        val fromCtorParam = owner.primaryConstructor?.parameters
            ?.firstOrNull { it.name?.asString() == paramName }
            ?.annotations?.toList()
            .orEmpty()
        return fromProp + fromCtorParam
    }

    private fun findAnno(annos: List<KSAnnotation>, simpleName: String): KSAnnotation? =
        annos.firstOrNull { it.shortName.asString() == simpleName }

    private fun actionSqlFor(code: Int?): String? = when (code) {
        1 -> "NO ACTION"
        2 -> "RESTRICT"
        3 -> "SET NULL"
        4 -> "SET DEFAULT"
        5 -> "CASCADE"
        else -> null
    }

    private fun writeFile(dependencies: Dependencies, packageName: String, fileName: String, code: String) {
        val file = codeGenerator.createNewFile(dependencies, packageName, fileName)
        OutputStreamWriter(file).use { it.write(code) }
    }

    private fun escapeKotlinString(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ---------------- per @Entity: analysis (columns, PK, indices, FKs, embedded, enums, json) ----------------

    private fun analyzeEntity(entity: KSClassDeclaration): EntityAnalysis {
        val entityAnno = entity.annotations.first { it.shortName.asString() == "Entity" }
        val tableName = tableNameOf(entity)
        val className = entity.simpleName.asString()

        val columns = mutableListOf<ColumnDef>()
        val entityJsonTypes = mutableSetOf<String>()
        val fieldTree = mutableListOf<FieldNode>()
        collectColumns(entity, "", columns, entityJsonTypes, className, treeOut = fieldTree)
        // @Embedded is fully supported by the read<Entity> codegen below (it walks
        // fieldTree, not the flat columns list) - only an explicit custom
        // @ColumnTypeConverters override still disqualifies an entity, since there's no
        // safe way to infer an arbitrary user-supplied converter's decode call.
        val readableForRelations = columns.none { it.kind == ColumnKind.JSON_CONVERTER && it.hasExplicitConverter }

        val explicitPkNames = stringList(entityAnno, "primaryKeys")
        val fieldPkColumns = columns.filter { it.isPrimaryKey }.map { it.columnName }
        val primaryKeyColumns = explicitPkNames.ifEmpty { fieldPkColumns }

        val singlePkCol = columns.singleOrNull { it.isPrimaryKey }
        val autoIncrement = explicitPkNames.isEmpty() && singlePkCol != null && run {
            val prop = entity.getAllProperties().firstOrNull { it.simpleName.asString() == singlePkCol.propName }
            val pkAnno = prop?.let { findAnno(annotationsOf(entity, it), "PrimaryKey") }
            pkAnno?.let { argValue(it, "autoGenerate") as? Boolean } == true
        }

        val indexAnnos = (argValue(entityAnno, "indices") as? List<*>)?.filterIsInstance<KSAnnotation>() ?: emptyList()
        val indices = indexAnnos.map { idxAnno ->
            val cols = stringList(idxAnno, "value")
            val unique = argValue(idxAnno, "unique") as? Boolean ?: false
            val rawName = argValue(idxAnno, "name") as? String
            val name = rawName?.takeIf { it.isNotBlank() } ?: "index_${tableName}_${cols.joinToString("_")}"
            IndexInfo(name, unique, cols)
        }

        val fkAnnos = (argValue(entityAnno, "foreignKeys") as? List<*>)?.filterIsInstance<KSAnnotation>() ?: emptyList()
        val foreignKeys = fkAnnos.mapNotNull { fkAnno ->
            val parentEntityType = (argValue(fkAnno, "entity") as? KSType)?.declaration as? KSClassDeclaration
                ?: return@mapNotNull null
            ForeignKeyInfo(
                childColumns = stringList(fkAnno, "childColumns"),
                parentTable = tableNameOf(parentEntityType),
                parentColumns = stringList(fkAnno, "parentColumns"),
                onDelete = actionSqlFor(argValue(fkAnno, "onDelete") as? Int),
                onUpdate = actionSqlFor(argValue(fkAnno, "onUpdate") as? Int),
            )
        }

        return EntityAnalysis(tableName, columns, primaryKeyColumns, autoIncrement, indices, foreignKeys, entityJsonTypes, fieldTree, readableForRelations)
    }

    // Builds the flat `into` column list (for schema/DDL) AND, in lockstep, the `treeOut`
    // field tree (for read<Entity> codegen) - a Scalar leaf's index always equals its
    // position in `into`, which is what makes reading it back out of a SQLiteStatement by
    // that index correct.
    private fun collectColumns(
        classDecl: KSClassDeclaration,
        prefix: String,
        into: MutableList<ColumnDef>,
        jsonTypes: MutableSet<String>,
        contextName: String,
        forceNullable: Boolean = false,
        treeOut: MutableList<FieldNode>,
    ) {
        for (prop in classDecl.getAllProperties()) {
            val propName = prop.simpleName.asString()
            val annos = annotationsOf(classDecl, prop)

            // Room itself excludes @Ignore properties from the real table entirely (they must
            // either sit outside the primary constructor or have a default value there) - if
            // we didn't skip it here too, it would end up as a real column in CREATE TABLE and
            // a named constructor arg in the generated read<Entity>(), referencing a column
            // that was never actually created.
            if (findAnno(annos, "Ignore") != null) continue

            val embeddedAnno = findAnno(annos, "Embedded")
            if (embeddedAnno != null) {
                val embedPrefix = prefix + ((argValue(embeddedAnno, "prefix") as? String) ?: "")
                val nestedDecl = prop.type.resolve().declaration as? KSClassDeclaration
                if (nestedDecl != null) {
                    // if the @Embedded property itself is nullable, Room's semantics is
                    // "container null => every flattened column reads back null", so that
                    // has to force every descendant column nullable too, regardless of
                    // whether the descendant's own declared type is non-null.
                    val embeddedIsNullable = prop.type.resolve().isMarkedNullable
                    val childTree = mutableListOf<FieldNode>()
                    collectColumns(nestedDecl, embedPrefix, into, jsonTypes, contextName, forceNullable || embeddedIsNullable, childTree)
                    treeOut += FieldNode.Embedded(propName, nestedDecl.simpleName.asString(), embeddedIsNullable, childTree)
                } else {
                    logger.warn("ColumnsProcessor: $contextName.$propName is @Embedded but its type isn't a class, skipping")
                }
                continue
            }

            val columnInfoAnno = findAnno(annos, "ColumnInfo")
            val rawColumnName = columnInfoAnno?.let { argValue(it, "name") as? String }
            val columnName = prefix + (rawColumnName?.takeIf { it.isNotBlank() } ?: propName)

            val rawDefault = columnInfoAnno?.let { argValue(it, "defaultValue") as? String }
            val defaultValue = rawDefault?.takeIf { it.isNotBlank() && !(it.startsWith("[") && it.endsWith("]")) }

            val isPrimaryKey = findAnno(annos, "PrimaryKey") != null

            val resolvedType = prop.type.resolve()
            val nullable = resolvedType.isMarkedNullable || forceNullable
            val typeDecl = resolvedType.declaration
            val typeQualifiedName = typeDecl.qualifiedName?.asString()

            val mapping = supportedTypes[typeQualifiedName]
            if (mapping != null) {
                into += ColumnDef(
                    propName, columnName, mapping.sqlType, mapping.columnClass, nullable, defaultValue, isPrimaryKey,
                    kotlinTypeName = typeQualifiedName!!, kind = ColumnKind.PRIMITIVE,
                )
                treeOut += FieldNode.Scalar(into.last(), into.size - 1)
                continue
            }

            if (typeDecl is KSClassDeclaration && typeDecl.classKind == ClassKind.ENUM_CLASS) {
                // Room's own built-in enum converter handles value<->name; we only need
                // to know this is a TEXT column for schema/Where-DSL purposes.
                into += ColumnDef(
                    propName, columnName, "TEXT", "StringColumn", nullable, defaultValue, isPrimaryKey,
                    kotlinTypeName = typeQualifiedName!!, kind = ColumnKind.ENUM,
                )
                treeOut += FieldNode.Scalar(into.last(), into.size - 1)
                continue
            }

            val isSerializable = typeDecl.annotations.any { it.shortName.asString() == "Serializable" }
            val hasExplicitConverter = findAnno(annos, "ColumnTypeConverters") != null
            if (isSerializable && typeDecl is KSClassDeclaration) {
                // Either way this is still a real TEXT column that must exist in CREATE
                // TABLE / ExpectedColumns - it just has no typed Where-DSL val, since we
                // can't reason about comparisons on an opaque converter-produced value.
                into += ColumnDef(
                    propName, columnName, "TEXT", null, nullable, defaultValue, isPrimaryKey,
                    kotlinTypeName = typeQualifiedName ?: "kotlin.Any", kind = ColumnKind.JSON_CONVERTER,
                    hasExplicitConverter = hasExplicitConverter,
                )
                treeOut += FieldNode.Scalar(into.last(), into.size - 1)
                if (hasExplicitConverter) {
                    logger.info("ColumnsProcessor: $contextName.$propName has its own @ColumnTypeConverters override, not generating a default for $typeQualifiedName here")
                } else if (typeQualifiedName != null) {
                    jsonConverterTypes.putIfAbsent(typeQualifiedName, typeDecl)
                    jsonTypes += typeQualifiedName
                }
            } else {
                logger.warn("ColumnsProcessor: skipping $contextName.$propName (unsupported type $typeQualifiedName - not a primitive, not an enum, not @Embedded, not @Serializable)")
            }
        }
    }

    private fun createTableSql(analysis: EntityAnalysis): String {
        val singleAutoIncPk = analysis.autoIncrement && analysis.primaryKeyColumns.size == 1
        val pkColumnName = analysis.primaryKeyColumns.singleOrNull()

        val colDefs = analysis.columns.map { c ->
            val isSinglePkAutoInc = singleAutoIncPk && c.columnName == pkColumnName
            val pk = if (isSinglePkAutoInc) " PRIMARY KEY AUTOINCREMENT" else ""
            val notNull = if (!c.nullable || isSinglePkAutoInc) " NOT NULL" else ""
            val default = c.defaultValue?.let { " DEFAULT $it" } ?: ""
            "`${c.columnName}` ${c.sqlType}$pk$notNull$default"
        }.toMutableList()

        if (analysis.primaryKeyColumns.isNotEmpty() && !singleAutoIncPk) {
            colDefs += "PRIMARY KEY (${analysis.primaryKeyColumns.joinToString(", ") { "`$it`" }})"
        }

        for (fk in analysis.foreignKeys) {
            colDefs += buildString {
                append("FOREIGN KEY (${fk.childColumns.joinToString(", ") { "`$it`" }})")
                append(" REFERENCES `${fk.parentTable}`(${fk.parentColumns.joinToString(", ") { "`$it`" }})")
                fk.onDelete?.let { append(" ON DELETE $it") }
                fk.onUpdate?.let { append(" ON UPDATE $it") }
            }
        }

        return "CREATE TABLE IF NOT EXISTS `${analysis.tableName}` (${colDefs.joinToString(", ")})"
    }

    private fun indexSql(tableName: String, idx: IndexInfo): String {
        val uniqueKw = if (idx.unique) "UNIQUE " else ""
        val cols = idx.columns.joinToString(", ") { "`$it`" }
        return "CREATE ${uniqueKw}INDEX IF NOT EXISTS `${idx.name}` ON `$tableName` ($cols)"
    }

    // ---------------- shared by relation-fetch codegen: reading/binding raw statement values ----------------

    private fun ktType(col: ColumnDef): String = col.kotlinTypeName + if (col.nullable) "?" else ""

    private fun readColumnExpr(col: ColumnDef, index: Int): String {
        val base = when {
            col.kind == ColumnKind.ENUM -> "enumValueOf<${col.kotlinTypeName}>(stmt.getText($index))"
            // only reachable for the auto-generated default converter (hasExplicitConverter
            // == false) - entities with an explicit override are excluded from
            // readableForRelations entirely, so this is never generated for those.
            col.kind == ColumnKind.JSON_CONVERTER -> "com.poc.plugin.runtime.jsonDecode<${col.kotlinTypeName}>(stmt.getText($index))"
            col.kotlinTypeName == "kotlin.Long" -> "stmt.getLong($index)"
            col.kotlinTypeName == "kotlin.Int" -> "stmt.getInt($index)"
            col.kotlinTypeName == "kotlin.Short" -> "stmt.getLong($index).toShort()"
            col.kotlinTypeName == "kotlin.Byte" -> "stmt.getLong($index).toByte()"
            col.kotlinTypeName == "kotlin.String" -> "stmt.getText($index)"
            col.kotlinTypeName == "kotlin.Double" -> "stmt.getDouble($index)"
            col.kotlinTypeName == "kotlin.Float" -> "stmt.getFloat($index)"
            col.kotlinTypeName == "kotlin.Boolean" -> "stmt.getBoolean($index)"
            col.kotlinTypeName == "kotlin.ByteArray" -> "stmt.getBlob($index)"
            else -> "stmt.getText($index)"
        }
        return if (col.nullable) "if (stmt.isNull($index)) null else $base" else base
    }

    private fun fieldNodePropName(node: FieldNode): String = when (node) {
        is FieldNode.Scalar -> node.col.propName
        is FieldNode.Embedded -> node.propName
    }

    private fun collectLeafIndices(node: FieldNode): List<Int> = when (node) {
        is FieldNode.Scalar -> listOf(node.index)
        is FieldNode.Embedded -> node.children.flatMap { collectLeafIndices(it) }
    }

    // Recursively builds the Kotlin expression that reconstructs one field's value from a
    // SQLiteStatement row - a plain readColumnExpr() for a scalar leaf, or a nested
    // `TypeName(child = ..., ...)` constructor call for an @Embedded field. A nullable
    // @Embedded field additionally checks "are every one of this group's own columns NULL in
    // this row" and produces null for the whole group if so (mirroring how a null @Embedded
    // value would have been written on insert - every flattened column NULL).
    private fun fieldNodeValueExpr(node: FieldNode): String = when (node) {
        is FieldNode.Scalar -> readColumnExpr(node.col, node.index)
        is FieldNode.Embedded -> {
            val ctorArgs = node.children.joinToString(", ") { "${fieldNodePropName(it)} = ${fieldNodeValueExpr(it)}" }
            val construct = "${node.typeSimpleName}($ctorArgs)"
            val leafIndices = collectLeafIndices(node)
            if (node.nullable && leafIndices.isNotEmpty()) {
                val allNullCheck = leafIndices.joinToString(" && ") { "stmt.isNull($it)" }
                "if ($allNullCheck) null else $construct"
            } else {
                construct
            }
        }
    }

    private fun bindScalarExpr(col: ColumnDef, index: String, valueExpr: String): String = when (col.kotlinTypeName) {
        "kotlin.Long" -> "stmt.bindLong($index, $valueExpr)"
        "kotlin.String" -> "stmt.bindText($index, $valueExpr)"
        "kotlin.Double" -> "stmt.bindDouble($index, $valueExpr)"
        "kotlin.Float" -> "stmt.bindFloat($index, $valueExpr)"
        "kotlin.Boolean" -> "stmt.bindBoolean($index, $valueExpr)"
        else -> "stmt.bindLong($index, ($valueExpr).toLong())" // Int/Short/Byte
    }

    private fun readEntityFunctionName(simpleName: String) = "read$simpleName"

    // Emits `val $varName = $accumulatorInit` followed by a loop over `$idsExpr.chunked(...)`
    // that runs one `usePrepared` per chunk and folds every row into that same accumulator via
    // `perRowStatement` - this is what makes every batch/relation query below safe against
    // SQLite's bound-parameter limit regardless of how many ids are passed in. An empty
    // `idsExpr` naturally produces zero chunks, so callers don't need a separate empty-list
    // guard around this.
    private fun StringBuilder.appendChunkedInQuery(
        varName: String,
        accumulatorInit: String,
        idsExpr: String,
        idColForBind: ColumnDef,
        selectSqlPrefix: String,
        perRowStatement: String,
        indent: String = "    ",
        emitReturnExpr: Boolean = false,
    ) {
        appendLine("${indent}val $varName = $accumulatorInit")
        appendLine("${indent}for (chunk in $idsExpr.chunked($sqliteInChunkSize)) {")
        appendLine("$indent    transactor.usePrepared(")
        appendLine("$indent        \"$selectSqlPrefix IN (\${chunk.joinToString(\",\") { \"?\" }})\"")
        appendLine("$indent    ) { stmt ->")
        appendLine("$indent        chunk.forEachIndexed { i, v -> ${bindScalarExpr(idColForBind, "i + 1", "v")} }")
        appendLine("$indent        while (stmt.step()) { $perRowStatement }")
        appendLine("$indent    }")
        appendLine("$indent}")
        if (emitReturnExpr) appendLine("$indent$varName")
    }

    // Emits Flow-based reactive variants of an already-generated get<Wrapper>(id) /
    // getAll<Wrapper>(ids) pair - a relation's Flow just re-invokes the plain suspend fetch
    // inside invalidationTracker.createFlow(...).map { ... }, so it re-runs (and re-emits)
    // whenever ANY of the tables that fetch actually reads from changes - not just the
    // "root" entity's own table. That's why `tables` must include every table involved,
    // not just the wrapper's top-level one.
    private fun StringBuilder.appendRelationFlowFns(dbName: String, wrapperName: String, pkKtType: String, tables: Set<String>) {
        val tablesArgs = tables.joinToString(", ") { "\"$it\"" }
        appendLine()
        appendLine("fun $dbName.get${wrapperName}Flow(id: $pkKtType): Flow<$wrapperName?> =")
        appendLine("    invalidationTracker.createFlow($tablesArgs).map { get$wrapperName(id) }")
        appendLine()
        appendLine("fun $dbName.getAll${wrapperName}Flow(ids: List<$pkKtType>): Flow<List<$wrapperName>> =")
        appendLine("    invalidationTracker.createFlow($tablesArgs).map { getAll$wrapperName(ids) }")
    }

    // Emits query<Wrapper>(where, ...) / query<Wrapper>Flow(where, ...) - the relation
    // counterpart to query<Entity>Flow: selects just the matching root ids via a
    // WHERE-filtered SELECT of the root's own PK column, then reuses the already-generated
    // getAll<Wrapper>(ids) batch fetch (still a fixed number of queries, not N+1), wrapped in
    // invalidationTracker.createFlow(*tables) for the Flow variant so it invalidates on a
    // write to any table in the chain, exactly like getAll<Wrapper>Flow already does.
    private fun StringBuilder.appendRelationQueryFns(dbName: String, wrapperName: String, rootTableName: String, rootPkCol: ColumnDef, tables: Set<String>) {
        val tablesArgs = tables.joinToString(", ") { "\"$it\"" }
        appendLine()
        appendLine("suspend fun $dbName.query$wrapperName(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): List<$wrapperName> {")
        appendLine("    val ids = useReaderConnection { transactor ->")
        appendLine("        val sql = buildString {")
        appendLine("            append(\"SELECT `${rootPkCol.columnName}` FROM $rootTableName WHERE \")")
        appendLine("            append(where.sql)")
        appendLine("            if (orderBy != null) { append(\" ORDER BY \"); append(orderBy.sql) }")
        appendLine("            if (limit != null) { append(\" LIMIT \"); append(limit) }")
        appendLine("            if (offset != null) { append(\" OFFSET \"); append(offset) }")
        appendLine("        }")
        appendLine("        transactor.usePrepared(sql) { stmt ->")
        appendLine("            where.bindingFunction()(stmt)")
        appendLine("            val list = mutableListOf<${ktType(rootPkCol)}>()")
        appendLine("            while (stmt.step()) list.add(${readColumnExpr(rootPkCol, 0)})")
        appendLine("            list")
        appendLine("        }")
        appendLine("    }")
        appendLine("    return getAll$wrapperName(ids)")
        appendLine("}")
        appendLine()
        appendLine("fun $dbName.query${wrapperName}Flow(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): Flow<List<$wrapperName>> =")
        appendLine("    invalidationTracker.createFlow($tablesArgs).map { query$wrapperName(where, orderBy, limit, offset) }")
    }

    private fun generateEntityFile(entity: KSClassDeclaration, analysis: EntityAnalysis) {
        if (analysis.columns.isEmpty()) return

        val packageName = entity.packageName.asString()
        val className = entity.simpleName.asString()

        val columnsObjectBody = analysis.columns.filter { it.columnRefClass != null }.joinToString("\n") { c ->
            val valName = c.propName.replaceFirstChar { it.uppercase() }
            "    val $valName = ${c.columnRefClass}(\"${c.columnName}\")"
        }

        // single-column foreign keys can carry their REFERENCES clause into an ALTER TABLE
        // ADD COLUMN - but SQLite only allows that when the added column's default is NULL,
        // i.e. it's nullable with no other default. Composite (multi-column) FKs can't be
        // expressed piecemeal via ALTER at all, so those are left out here on purpose - the
        // constraint still applies fine when the table is CREATEd fresh (createTableSql
        // below always includes every foreign key as a table-level constraint).
        val singleColumnFks = analysis.foreignKeys.filter { it.childColumns.size == 1 }
            .associateBy { it.childColumns[0] }

        val expectedColumnsBody = analysis.columns.filterNot { it.isPrimaryKey }.joinToString(",\n") { c ->
            val notNull = if (!c.nullable) " NOT NULL" else ""
            val default = c.defaultValue?.let { " DEFAULT $it" } ?: ""
            val fk = singleColumnFks[c.columnName]
            val references = when {
                fk == null -> ""
                c.nullable && c.defaultValue == null -> buildString {
                    append(" REFERENCES `${fk.parentTable}`(${fk.parentColumns.joinToString(", ") { "`$it`" }})")
                    fk.onDelete?.let { append(" ON DELETE $it") }
                    fk.onUpdate?.let { append(" ON UPDATE $it") }
                }
                else -> {
                    logger.warn(
                        "ColumnsProcessor: ${className}.${c.propName} is a foreign key column that isn't a plain " +
                            "nullable-with-no-default column - SQLite only allows ALTER TABLE ADD COLUMN with a " +
                            "REFERENCES clause when the new column's default is NULL. If this column is added to an " +
                            "EXISTING table by a migration (as opposed to created fresh with the table), it will exist " +
                            "but WITHOUT the foreign key constraint enforced.",
                    )
                    ""
                }
            }
            "        ExpectedColumn(\"${c.columnName}\", \"`${c.columnName}` ${c.sqlType}$notNull$default$references\")"
        }

        val indexDefsBody = analysis.indices.joinToString(",\n") { idx ->
            "        IndexDef(\"${idx.name}\", \"${escapeKotlinString(indexSql(analysis.tableName, idx))}\")"
        }

        val code = buildString {
            appendLine("// GENERATED by :plugin-processor from @Entity ${packageName}.${className} - do not edit")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.poc.plugin.runtime.BooleanColumn")
            appendLine("import com.poc.plugin.runtime.ByteArrayColumn")
            appendLine("import com.poc.plugin.runtime.ByteColumn")
            appendLine("import com.poc.plugin.runtime.DoubleColumn")
            appendLine("import com.poc.plugin.runtime.ExpectedColumn")
            appendLine("import com.poc.plugin.runtime.FloatColumn")
            appendLine("import com.poc.plugin.runtime.IndexDef")
            appendLine("import com.poc.plugin.runtime.IntColumn")
            appendLine("import com.poc.plugin.runtime.LongColumn")
            appendLine("import com.poc.plugin.runtime.ShortColumn")
            appendLine("import com.poc.plugin.runtime.StringColumn")
            if (analysis.readableForRelations) {
                appendLine("import androidx.sqlite.SQLiteStatement")
            }
            appendLine()
            appendLine("object ${className}Columns {")
            appendLine(columnsObjectBody)
            appendLine("}")
            appendLine()
            appendLine("fun ${className}ExpectedColumns(): List<ExpectedColumn> = listOf(")
            appendLine(expectedColumnsBody)
            appendLine(")")
            appendLine()
            appendLine("fun ${className}CreateTableSql(): String = \"${escapeKotlinString(createTableSql(analysis))}\"")
            appendLine()
            appendLine("fun ${className}Indices(): List<IndexDef> = listOf(")
            if (indexDefsBody.isNotEmpty()) appendLine(indexDefsBody)
            appendLine(")")

            // relation-fetch codegen (see processDatabase) reconstructs rows itself since it
            // bypasses Room's Dao layer entirely - walks fieldTree (not the flat columns
            // list) so @Embedded and default-JSON-converter fields reconstruct correctly;
            // only an explicit custom @ColumnTypeConverters override still excludes an
            // entity (see EntityAnalysis.readableForRelations).
            if (analysis.readableForRelations) {
                appendLine()
                appendLine("fun ${readEntityFunctionName(className)}(stmt: SQLiteStatement): $className = $className(")
                appendLine(
                    analysis.fieldTree.joinToString(",\n") { node -> "    ${fieldNodePropName(node)} = ${fieldNodeValueExpr(node)}" },
                )
                appendLine(")")
            }
        }

        writeFile(Dependencies(false, entity.containingFile!!), packageName, "${className}Generated", code)
    }

    // ---------------- shared default JSON converters, one per distinct type ----------------

    private fun generateJsonConverters() {
        for ((qualifiedName, decl) in jsonConverterTypes) {
            if (!generatedConverterFor.add(qualifiedName)) continue
            val packageName = decl.packageName.asString()
            val simpleName = decl.simpleName.asString()
            val converterName = "${simpleName}DefaultJsonConverter"

            val code = buildString {
                appendLine("// GENERATED by :plugin-processor - default JSON @ColumnTypeConverter for $qualifiedName")
                appendLine("// A field-level @field:ColumnTypeConverters override still takes precedence over this.")
                appendLine("package $packageName")
                appendLine()
                appendLine("import androidx.room3.ColumnTypeConverter")
                appendLine("import com.poc.plugin.runtime.jsonDecode")
                appendLine("import com.poc.plugin.runtime.jsonEncode")
                appendLine()
                appendLine("object $converterName {")
                appendLine("    @ColumnTypeConverter")
                appendLine("    fun toColumn(value: $simpleName): String = jsonEncode(value)")
                appendLine()
                appendLine("    @ColumnTypeConverter")
                appendLine("    fun fromColumn(value: String): $simpleName = jsonDecode(value)")
                appendLine("}")
            }

            writeFile(Dependencies(false, decl.containingFile!!), packageName, converterName, code)
        }
    }

    // ---------------- per @Dao: Where-DSL wrapper for @RawQuery(RoomRawQuery) methods ----------------

    private fun processDao(dao: KSClassDeclaration) {
        val packageName = dao.packageName.asString()
        val daoName = dao.simpleName.asString()

        val rawQueryFuncs = dao.getAllFunctions().filter { func ->
            func.annotations.any { it.shortName.asString() == "RawQuery" } &&
                func.parameters.size == 1 &&
                func.parameters[0].type.resolve().declaration.qualifiedName?.asString() == "androidx.room3.RoomRawQuery"
        }.toList()

        val wrappers = mutableListOf<String>()

        // List<Entity>-returning methods get a Where + orderBy + limit + offset overload.
        // NOTE: this deliberately does NOT also auto-wrap Int-returning @RawQuery methods
        // for DELETE/UPDATE - confirmed empirically that Room 3 routes @RawQuery through
        // its reader connection pool, which either throws "attempt to write a readonly
        // database" (file-backed db) or silently deletes 0 rows (in-memory db) for a write.
        // @RawQuery is a read-only mechanism in Room 3, not a bug in our generated SQL.
        // The delete-by-Where feature instead lives on the *Database* (see processDatabase)
        // and goes through useWriterConnection() directly, bypassing @RawQuery entirely.
        for (func in rawQueryFuncs) {
            val returnType = func.returnType?.resolve() ?: continue
            val returnDeclName = returnType.declaration.qualifiedName?.asString()
            val methodName = func.simpleName.asString()
            if (returnDeclName != "kotlin.collections.List") {
                logger.info("ColumnsProcessor: skipping ${daoName}.$methodName - only a 'List<Entity>' return type is auto-wrapped with a Where overload (@RawQuery can't reliably write, see delete${'$'}{Entity}Where on the @Database instead)")
                continue
            }

            val entityType = returnType.arguments.firstOrNull()?.type?.resolve() ?: continue
            val entityQualifiedName = entityType.declaration.qualifiedName?.asString()
            val entityInfo = entityAnalyses[entityQualifiedName]
            if (entityInfo == null) {
                logger.warn("ColumnsProcessor: ${daoName}.$methodName returns List<${entityType.declaration.simpleName.asString()}>, which has no generated columns - skipping its Where overload")
                continue
            }

            val entitySimpleName = entityType.declaration.simpleName.asString()
            wrappers += buildString {
                appendLine("suspend fun $daoName.$methodName(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): List<$entitySimpleName> {")
                appendLine("    val sql = buildString {")
                appendLine("        append(\"SELECT * FROM ${entityInfo.tableName} WHERE \")")
                appendLine("        append(where.sql)")
                appendLine("        if (orderBy != null) { append(\" ORDER BY \"); append(orderBy.sql) }")
                appendLine("        if (limit != null) { append(\" LIMIT \"); append(limit) }")
                appendLine("        if (offset != null) { append(\" OFFSET \"); append(offset) }")
                appendLine("    }")
                append("    return $methodName(RoomRawQuery(sql, where.bindingFunction()))\n}")
            }
        }

        if (wrappers.isEmpty()) return

        val code = buildString {
            appendLine("// GENERATED by :plugin-processor from @Dao ${packageName}.${daoName} - do not edit")
            appendLine("package $packageName")
            appendLine()
            appendLine("import androidx.room3.RoomRawQuery")
            appendLine("import com.poc.plugin.runtime.OrderBy")
            appendLine("import com.poc.plugin.runtime.Where")
            appendLine("import com.poc.plugin.runtime.bindingFunction")
            appendLine()
            for (w in wrappers) {
                appendLine(w)
                appendLine()
            }
        }

        writeFile(Dependencies(false, dao.containingFile!!), packageName, "${daoName}WhereQueries", code)
    }

    // ---------------- per @Database: AutoMigrations() + Builder(path) ----------------

    private fun processDatabase(db: KSClassDeclaration) {
        val dbAnno = db.annotations.first { it.shortName.asString() == "Database" }
        val version = argValue(dbAnno, "version") as? Int ?: return
        val entityTypes = (argValue(dbAnno, "entities") as? List<*>)
            ?.mapNotNull { (it as? KSType)?.declaration as? KSClassDeclaration }
            ?: emptyList()

        val packageName = db.packageName.asString()
        val dbName = db.simpleName.asString()

        val manageable = entityTypes.filter { decl ->
            entityAnalyses[decl.qualifiedName?.asString()]?.columns?.isNotEmpty() == true
        }

        // ---- AutoMigrations() ----
        val hasMigrations = manageable.isNotEmpty() && version > 1
        if (hasMigrations) {
            val migrationEntries = (1 until version).joinToString("\n") { fromVersion ->
                val calls = manageable.joinToString("\n") { decl ->
                    val analysis = entityAnalyses.getValue(decl.qualifiedName!!.asString())
                    val simpleName = decl.simpleName.asString()
                    "            autoDiffMigration(connection, \"${analysis.tableName}\", ${simpleName}CreateTableSql(), ${simpleName}ExpectedColumns(), ${simpleName}Indices())"
                }
                "    Migration($fromVersion, $version) { connection ->\n$calls\n    },"
            }

            val code = buildString {
                appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                appendLine("package $packageName")
                appendLine()
                appendLine("import androidx.room3.migration.Migration")
                appendLine("import com.poc.plugin.runtime.autoDiffMigration")
                appendLine()
                appendLine("// Every entry does the same create-or-diff regardless of the declared 'from'")
                appendLine("// version, so upgrading from ANY earlier version lands correctly in one step -")
                appendLine("// no chained intermediate migrations required. A brand-new @Entity added since")
                appendLine("// the stored version gets its table created from scratch; an existing table only")
                appendLine("// gets missing columns/indices added.")
                appendLine("fun ${dbName}AutoMigrations(): Array<Migration> = arrayOf(")
                appendLine(migrationEntries)
                appendLine(")")
            }
            writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}AutoMigration", code)
        }

        // ---- <Name>Builder(path) - so call sites never need to mention *AutoMigrations() ----
        run {
            val code = buildString {
                appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                appendLine("package $packageName")
                appendLine()
                appendLine("import androidx.room3.Room")
                appendLine("import androidx.room3.RoomDatabase")
                appendLine()
                appendLine("// Pre-wired so callers never need to know *AutoMigrations() exists - they still")
                appendLine("// choose the driver themselves (Android vs JVM-test vs desktop is an environment")
                appendLine("// decision this processor has no business hardcoding).")
                appendLine("fun ${dbName}Builder(path: String): RoomDatabase.Builder<$dbName> {")
                appendLine("    var builder = Room.databaseBuilder<$dbName>(path) { ${dbName}_Impl() }")
                appendLine("        .fallbackToDestructiveMigrationOnDowngrade()")
                appendLine("    // generated migrations only ever go forward (older -> current) - there's no")
                appendLine("    // sensible way to auto-generate a downgrade (it could mean dropping columns")
                appendLine("    // with real user data), so an older app opening a newer-version db file falls")
                appendLine("    // back to a clean recreate instead of crashing outright.")
                if (hasMigrations) {
                    appendLine("    builder = builder.addMigrations(*${dbName}AutoMigrations())")
                }
                appendLine("    return builder")
                appendLine("}")
            }
            writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}Builder", code)
        }

        // ---- one-to-one / one-to-many relations, auto-detected from single-column foreign
        // keys between two manageable, "readable" entities (no explicit custom
        // @ColumnTypeConverters override - see EntityAnalysis.readableForRelations). One-to-one
        // vs one-to-many is decided by whether the FK's child column is itself unique (its
        // own primary key, or covered by a UNIQUE index). Self-referencing FKs (like
        // SessionV2.parentSessionId) are supported too - parent and child both resolve to
        // the same entity, giving a "fetch its direct children" tree-shaped relation. ----
        // both spec lists are hoisted to processDatabase scope (not just this run{} block) so
        // the two-level nested-relation codegen further down can cross-reference "is this
        // relation's child also some other relation's parent/left" across both kinds.
        val oneToXSpecs = mutableListOf<RelationSpec>()
        val m2mSpecs = mutableListOf<ManyToManySpec>()
        run {
            val seenPairs = mutableSetOf<Pair<String, String>>()
            val specs = oneToXSpecs

            for (childDecl in manageable) {
                val childQualified = childDecl.qualifiedName?.asString() ?: continue
                val childAnalysis = entityAnalyses.getValue(childQualified)
                if (!childAnalysis.readableForRelations) continue

                for (fk in childAnalysis.foreignKeys) {
                    if (fk.childColumns.size != 1) continue
                    val parentDecl = manageable.firstOrNull {
                        entityAnalyses[it.qualifiedName?.asString()]?.tableName == fk.parentTable
                    } ?: continue
                    val parentQualified = parentDecl.qualifiedName?.asString() ?: continue
                    // self-references (e.g. SessionV2.parentSessionId -> SessionV2.id) are
                    // fine - parentDecl/childDecl just both resolve to the same class, giving
                    // a tree-shaped "fetch its direct children" relation.

                    val parentAnalysis = entityAnalyses.getValue(parentQualified)
                    if (!parentAnalysis.readableForRelations) continue
                    if (parentAnalysis.primaryKeyColumns.size != 1) continue // fetch-by-id needs a single-column PK

                    if (!seenPairs.add(parentQualified to childQualified)) {
                        // relation wrapper/function names are shaped only by the (parent, child)
                        // entity pair, not by which FK column - a second FK between the same two
                        // entities (e.g. Book.authorId and Book.editorId both -> Author) would
                        // collide on the same generated names, so only the first FK encountered
                        // gets a relation. Flagged explicitly so this isn't a silent gap.
                        logger.warn(
                            "ColumnsProcessor: ${childDecl.simpleName.asString()} has more than one foreign key to " +
                                "${parentDecl.simpleName.asString()} (via `${fk.childColumns[0]}`) - only the first one " +
                                "found got a generated relation; this one was skipped. Generated relation names are " +
                                "keyed by entity pair only, not by FK column, so both can't coexist yet.",
                        )
                        continue
                    }

                    val childCol = fk.childColumns[0]
                    val oneToOne = childAnalysis.primaryKeyColumns == listOf(childCol) ||
                        childAnalysis.indices.any { it.unique && it.columns == listOf(childCol) }

                    specs += RelationSpec(parentDecl, childDecl, fk, oneToOne)
                }
            }

            // wrapper data classes are shaped only by the (parent, child) pair - generate
            // each one at most once globally, in the parent entity's own package, so two
            // @Database classes that happen to share the same pair don't redeclare it.
            for (spec in specs) {
                val parentQualified = spec.parentDecl.qualifiedName!!.asString()
                val childQualified = spec.childDecl.qualifiedName!!.asString()
                if (!generatedRelationWrapperFor.add(parentQualified to childQualified)) continue

                val parentSimple = spec.parentDecl.simpleName.asString()
                val childSimple = spec.childDecl.simpleName.asString()
                val parentPropName = parentSimple.replaceFirstChar { it.lowercase() }
                val childPropName = childSimple.replaceFirstChar { it.lowercase() }
                val wrapperPackage = spec.parentDecl.packageName.asString()

                val wrapperCode = if (spec.oneToOne) {
                    val wrapperName = "${parentSimple}With$childSimple"
                    "// GENERATED by :plugin-processor - relation wrapper for $parentQualified + $childQualified\n" +
                        "package $wrapperPackage\n\n" +
                        "data class $wrapperName(val $parentPropName: $parentSimple, val $childPropName: $childSimple?)\n"
                } else {
                    val wrapperName = "${parentSimple}With${childSimple}List"
                    "// GENERATED by :plugin-processor - relation wrapper for $parentQualified + $childQualified\n" +
                        "package $wrapperPackage\n\n" +
                        "data class $wrapperName(val $parentPropName: $parentSimple, val ${childPropName}List: List<$childSimple>)\n"
                }
                val wrapperFileName = if (spec.oneToOne) "${parentSimple}With$childSimple" else "${parentSimple}With${childSimple}List"
                writeFile(Dependencies(false, spec.parentDecl.containingFile!!), wrapperPackage, wrapperFileName, wrapperCode)
            }

            if (specs.isNotEmpty()) {
                val imports = sortedSetOf(
                    "androidx.room3.useReaderConnection",
                    "com.poc.plugin.runtime.OrderBy",
                    "com.poc.plugin.runtime.Where",
                    "com.poc.plugin.runtime.bindingFunction",
                    "kotlinx.coroutines.flow.Flow",
                    "kotlinx.coroutines.flow.map",
                )
                for (spec in specs) {
                    imports += "${spec.parentDecl.packageName.asString()}.${readEntityFunctionName(spec.parentDecl.simpleName.asString())}"
                    imports += "${spec.childDecl.packageName.asString()}.${readEntityFunctionName(spec.childDecl.simpleName.asString())}"
                    val parentSimple = spec.parentDecl.simpleName.asString()
                    val childSimple = spec.childDecl.simpleName.asString()
                    val wrapperName = if (spec.oneToOne) "${parentSimple}With$childSimple" else "${parentSimple}With${childSimple}List"
                    imports += "${spec.parentDecl.packageName.asString()}.$wrapperName"
                }

                val code = buildString {
                    appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                    appendLine("package $packageName")
                    appendLine()
                    for (imp in imports) appendLine("import $imp")
                    appendLine()

                    for (spec in specs) {
                        val parentSimple = spec.parentDecl.simpleName.asString()
                        val childSimple = spec.childDecl.simpleName.asString()
                        val parentAnalysis = entityAnalyses.getValue(spec.parentDecl.qualifiedName!!.asString())
                        val childAnalysis = entityAnalyses.getValue(spec.childDecl.qualifiedName!!.asString())
                        val parentPkCol = parentAnalysis.columns.first { it.columnName == parentAnalysis.primaryKeyColumns[0] }
                        val childFkCol = spec.fk.childColumns[0]
                        val childFkColDef = childAnalysis.columns.first { it.columnName == childFkCol }
                        // WHERE ... IN (ids) never matches NULL rows in SQL, so every row this
                        // batch query returns is guaranteed to have a non-null FK value even
                        // when the Kotlin property type is nullable (e.g. an optional self-FK) -
                        // the !! here reflects that SQL guarantee, not an unchecked assumption.
                        val childFkKeyExpr = "c.${childFkColDef.propName}" + if (childFkColDef.nullable) "!!" else ""

                        if (spec.oneToOne) {
                            val wrapperName = "${parentSimple}With$childSimple"
                            appendLine("suspend fun $dbName.get$wrapperName(id: ${ktType(parentPkCol)}): $wrapperName? = useReaderConnection { transactor ->")
                            appendLine("    val parent = transactor.usePrepared(\"SELECT * FROM ${parentAnalysis.tableName} WHERE `${parentPkCol.columnName}` = ?\") { stmt ->")
                            appendLine("        ${bindScalarExpr(parentPkCol, "1", "id")}")
                            appendLine("        if (stmt.step()) ${readEntityFunctionName(parentSimple)}(stmt) else null")
                            appendLine("    } ?: return@useReaderConnection null")
                            appendLine("    val child = transactor.usePrepared(\"SELECT * FROM ${childAnalysis.tableName} WHERE `$childFkCol` = ?\") { stmt ->")
                            appendLine("        ${bindScalarExpr(parentPkCol, "1", "id")}")
                            appendLine("        if (stmt.step()) ${readEntityFunctionName(childSimple)}(stmt) else null")
                            appendLine("    }")
                            appendLine("    $wrapperName(parent, child)")
                            appendLine("}")
                            appendLine()
                            // batch fetch: 2 queries total regardless of how many ids are
                            // passed in, instead of calling get$wrapperName in a loop (N+1).
                            appendLine("suspend fun $dbName.getAll$wrapperName(ids: List<${ktType(parentPkCol)}>): List<$wrapperName> = useReaderConnection { transactor ->")
                            appendChunkedInQuery(
                                varName = "parents",
                                accumulatorInit = "mutableListOf<$parentSimple>()",
                                idsExpr = "ids",
                                idColForBind = parentPkCol,
                                selectSqlPrefix = "SELECT * FROM ${parentAnalysis.tableName} WHERE `${parentPkCol.columnName}`",
                                perRowStatement = "parents.add(${readEntityFunctionName(parentSimple)}(stmt))",
                            )
                            appendLine("    val parentIds = parents.map { it.${parentPkCol.propName} }")
                            appendChunkedInQuery(
                                varName = "childByParentId",
                                accumulatorInit = "mutableMapOf<${ktType(parentPkCol)}, $childSimple>()",
                                idsExpr = "parentIds",
                                idColForBind = parentPkCol,
                                selectSqlPrefix = "SELECT * FROM ${childAnalysis.tableName} WHERE `$childFkCol`",
                                perRowStatement = "val c = ${readEntityFunctionName(childSimple)}(stmt); childByParentId[$childFkKeyExpr] = c",
                            )
                            appendLine("    parents.map { p -> $wrapperName(p, childByParentId[p.${parentPkCol.propName}]) }")
                            appendLine("}")
                            appendRelationFlowFns(dbName, wrapperName, ktType(parentPkCol), setOf(parentAnalysis.tableName, childAnalysis.tableName))
                            appendRelationQueryFns(dbName, wrapperName, parentAnalysis.tableName, parentPkCol, setOf(parentAnalysis.tableName, childAnalysis.tableName))
                        } else {
                            val wrapperName = "${parentSimple}With${childSimple}List"
                            appendLine("suspend fun $dbName.get$wrapperName(id: ${ktType(parentPkCol)}): $wrapperName? = useReaderConnection { transactor ->")
                            appendLine("    val parent = transactor.usePrepared(\"SELECT * FROM ${parentAnalysis.tableName} WHERE `${parentPkCol.columnName}` = ?\") { stmt ->")
                            appendLine("        ${bindScalarExpr(parentPkCol, "1", "id")}")
                            appendLine("        if (stmt.step()) ${readEntityFunctionName(parentSimple)}(stmt) else null")
                            appendLine("    } ?: return@useReaderConnection null")
                            appendLine("    val children = transactor.usePrepared(\"SELECT * FROM ${childAnalysis.tableName} WHERE `$childFkCol` = ?\") { stmt ->")
                            appendLine("        ${bindScalarExpr(parentPkCol, "1", "id")}")
                            appendLine("        val list = mutableListOf<$childSimple>()")
                            appendLine("        while (stmt.step()) list.add(${readEntityFunctionName(childSimple)}(stmt))")
                            appendLine("        list")
                            appendLine("    }")
                            appendLine("    $wrapperName(parent, children)")
                            appendLine("}")
                            appendLine()
                            // batch fetch: 2 queries total regardless of how many ids are
                            // passed in, instead of calling get$wrapperName in a loop (N+1).
                            appendLine("suspend fun $dbName.getAll$wrapperName(ids: List<${ktType(parentPkCol)}>): List<$wrapperName> = useReaderConnection { transactor ->")
                            appendChunkedInQuery(
                                varName = "parents",
                                accumulatorInit = "mutableListOf<$parentSimple>()",
                                idsExpr = "ids",
                                idColForBind = parentPkCol,
                                selectSqlPrefix = "SELECT * FROM ${parentAnalysis.tableName} WHERE `${parentPkCol.columnName}`",
                                perRowStatement = "parents.add(${readEntityFunctionName(parentSimple)}(stmt))",
                            )
                            appendLine("    val parentIds = parents.map { it.${parentPkCol.propName} }")
                            appendChunkedInQuery(
                                varName = "childrenByParentId",
                                accumulatorInit = "mutableMapOf<${ktType(parentPkCol)}, MutableList<$childSimple>>()",
                                idsExpr = "parentIds",
                                idColForBind = parentPkCol,
                                selectSqlPrefix = "SELECT * FROM ${childAnalysis.tableName} WHERE `$childFkCol`",
                                perRowStatement = "val c = ${readEntityFunctionName(childSimple)}(stmt); childrenByParentId.getOrPut($childFkKeyExpr) { mutableListOf() }.add(c)",
                            )
                            appendLine("    parents.map { p -> $wrapperName(p, childrenByParentId[p.${parentPkCol.propName}] ?: emptyList()) }")
                            appendLine("}")
                            appendRelationFlowFns(dbName, wrapperName, ktType(parentPkCol), setOf(parentAnalysis.tableName, childAnalysis.tableName))
                            appendRelationQueryFns(dbName, wrapperName, parentAnalysis.tableName, parentPkCol, setOf(parentAnalysis.tableName, childAnalysis.tableName))
                        }
                        appendLine()
                    }
                }
                writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}Relations", code)
            }
        }

        // ---- many-to-many, auto-detected from "junction-shaped" entities: readable, with a
        // composite 2-column primary key where each column is also a single-column foreign
        // key to a different manageable, readable entity with a single-column primary key.
        // Fetches left -> junction rows -> right, matching Room's own @Relation(associateBy
        // = @Junction(...)) shape conceptually, just without needing that annotation at all. ----
        run {
            val seenTriples = mutableSetOf<Triple<String, String, String>>()

            for (junctionDecl in manageable) {
                val junctionQualified = junctionDecl.qualifiedName?.asString() ?: continue
                val junctionAnalysis = entityAnalyses.getValue(junctionQualified)
                if (!junctionAnalysis.readableForRelations) continue
                if (junctionAnalysis.primaryKeyColumns.size != 2) continue

                val fksOnPk = junctionAnalysis.primaryKeyColumns.mapNotNull { pkCol ->
                    junctionAnalysis.foreignKeys.firstOrNull { it.childColumns == listOf(pkCol) }?.let { pkCol to it }
                }
                if (fksOnPk.size != 2) continue // both PK columns must each be their own single-column FK

                val (leftColPair, rightColPair) = fksOnPk
                val leftDecl = manageable.firstOrNull { entityAnalyses[it.qualifiedName?.asString()]?.tableName == leftColPair.second.parentTable } ?: continue
                val rightDecl = manageable.firstOrNull { entityAnalyses[it.qualifiedName?.asString()]?.tableName == rightColPair.second.parentTable } ?: continue
                val leftQualified = leftDecl.qualifiedName?.asString() ?: continue
                val rightQualified = rightDecl.qualifiedName?.asString() ?: continue
                if (leftQualified == rightQualified) continue // self-referencing junction, not handled

                val leftAnalysis = entityAnalyses.getValue(leftQualified)
                val rightAnalysis = entityAnalyses.getValue(rightQualified)
                if (!leftAnalysis.readableForRelations || !rightAnalysis.readableForRelations) continue
                if (leftAnalysis.primaryKeyColumns.size != 1 || rightAnalysis.primaryKeyColumns.size != 1) continue

                if (!seenTriples.add(Triple(leftQualified, rightQualified, junctionQualified))) continue

                m2mSpecs += ManyToManySpec(leftDecl, rightDecl, junctionDecl, leftColPair.first, rightColPair.first)
            }

            // wrapper classes, deduped globally per (left, right, junction) triple
            for (spec in m2mSpecs) {
                val leftQualified = spec.leftDecl.qualifiedName!!.asString()
                val rightQualified = spec.rightDecl.qualifiedName!!.asString()
                val junctionQualified = spec.junctionDecl.qualifiedName!!.asString()
                if (!generatedRelationWrapperFor.add(leftQualified to "$rightQualified via $junctionQualified")) continue

                val leftSimple = spec.leftDecl.simpleName.asString()
                val rightSimple = spec.rightDecl.simpleName.asString()
                val junctionSimple = spec.junctionDecl.simpleName.asString()
                val leftPropName = leftSimple.replaceFirstChar { it.lowercase() }
                val wrapperName = "${leftSimple}With${rightSimple}Via$junctionSimple"
                val wrapperPackage = spec.leftDecl.packageName.asString()
                // each pairing carries its own junction row alongside the right-side entity,
                // so any extra columns on the junction table (e.g. "taggedAt") aren't
                // silently discarded - just List<Right> would throw that data away.
                val wrapperCode = "// GENERATED by :plugin-processor - many-to-many wrapper for $leftQualified <-> $rightQualified via $junctionQualified\n" +
                    "package $wrapperPackage\n\n" +
                    "data class $wrapperName(\n" +
                    "    val $leftPropName: $leftSimple,\n" +
                    "    val ${rightSimple.replaceFirstChar { it.lowercase() }}WithJunctionList: List<Pair<$rightSimple, $junctionSimple>>,\n" +
                    ")\n"
                writeFile(Dependencies(false, spec.leftDecl.containingFile!!), wrapperPackage, wrapperName, wrapperCode)
            }

            if (m2mSpecs.isNotEmpty()) {
                val imports = sortedSetOf(
                    "androidx.room3.useReaderConnection",
                    "com.poc.plugin.runtime.OrderBy",
                    "com.poc.plugin.runtime.Where",
                    "com.poc.plugin.runtime.bindingFunction",
                    "kotlinx.coroutines.flow.Flow",
                    "kotlinx.coroutines.flow.map",
                )
                for (spec in m2mSpecs) {
                    imports += "${spec.leftDecl.packageName.asString()}.${readEntityFunctionName(spec.leftDecl.simpleName.asString())}"
                    imports += "${spec.rightDecl.packageName.asString()}.${readEntityFunctionName(spec.rightDecl.simpleName.asString())}"
                    imports += "${spec.junctionDecl.packageName.asString()}.${readEntityFunctionName(spec.junctionDecl.simpleName.asString())}"
                    val wrapperName = "${spec.leftDecl.simpleName.asString()}With${spec.rightDecl.simpleName.asString()}Via${spec.junctionDecl.simpleName.asString()}"
                    imports += "${spec.leftDecl.packageName.asString()}.$wrapperName"
                }

                val code = buildString {
                    appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                    appendLine("package $packageName")
                    appendLine()
                    for (imp in imports) appendLine("import $imp")
                    appendLine()

                    for (spec in m2mSpecs) {
                        val leftSimple = spec.leftDecl.simpleName.asString()
                        val rightSimple = spec.rightDecl.simpleName.asString()
                        val junctionSimple = spec.junctionDecl.simpleName.asString()
                        val leftAnalysis = entityAnalyses.getValue(spec.leftDecl.qualifiedName!!.asString())
                        val rightAnalysis = entityAnalyses.getValue(spec.rightDecl.qualifiedName!!.asString())
                        val junctionAnalysis = entityAnalyses.getValue(spec.junctionDecl.qualifiedName!!.asString())
                        val leftPkCol = leftAnalysis.columns.first { it.columnName == leftAnalysis.primaryKeyColumns[0] }
                        val rightPkCol = rightAnalysis.columns.first { it.columnName == rightAnalysis.primaryKeyColumns[0] }
                        val junctionRightFkCol = junctionAnalysis.columns.first { it.columnName == spec.rightFkColumn }
                        val junctionLeftFkCol = junctionAnalysis.columns.first { it.columnName == spec.leftFkColumn }
                        val wrapperName = "${leftSimple}With${rightSimple}Via$junctionSimple"

                        appendLine("suspend fun $dbName.get$wrapperName(id: ${ktType(leftPkCol)}): $wrapperName? = useReaderConnection { transactor ->")
                        appendLine("    val left = transactor.usePrepared(\"SELECT * FROM ${leftAnalysis.tableName} WHERE `${leftPkCol.columnName}` = ?\") { stmt ->")
                        appendLine("        ${bindScalarExpr(leftPkCol, "1", "id")}")
                        appendLine("        if (stmt.step()) ${readEntityFunctionName(leftSimple)}(stmt) else null")
                        appendLine("    } ?: return@useReaderConnection null")
                        appendLine("    val junctionRows = transactor.usePrepared(\"SELECT * FROM ${junctionAnalysis.tableName} WHERE `${spec.leftFkColumn}` = ?\") { stmt ->")
                        appendLine("        ${bindScalarExpr(leftPkCol, "1", "id")}")
                        appendLine("        val list = mutableListOf<$junctionSimple>()")
                        appendLine("        while (stmt.step()) list.add(${readEntityFunctionName(junctionSimple)}(stmt))")
                        appendLine("        list")
                        appendLine("    }")
                        // SQLite's `IN (...)` does not guarantee results come back in the same
                        // order as the id list, so we build an id->entity map and pair by id
                        // rather than zipping positionally against junctionRows.
                        appendLine("    val rightIds = junctionRows.map { it.${junctionRightFkCol.propName} }.distinct()")
                        appendChunkedInQuery(
                            varName = "rightsById",
                            accumulatorInit = "mutableMapOf<${ktType(rightPkCol)}, $rightSimple>()",
                            idsExpr = "rightIds",
                            idColForBind = rightPkCol,
                            selectSqlPrefix = "SELECT * FROM ${rightAnalysis.tableName} WHERE `${rightPkCol.columnName}`",
                            perRowStatement = "val r = ${readEntityFunctionName(rightSimple)}(stmt); rightsById[r.${rightPkCol.propName}] = r",
                        )
                        appendLine("    val pairs = junctionRows.mapNotNull { j -> rightsById[j.${junctionRightFkCol.propName}]?.let { it to j } }")
                        appendLine("    $wrapperName(left, pairs)")
                        appendLine("}")
                        appendLine()

                        // batch fetch: 3 queries total regardless of how many ids are passed
                        // in, instead of calling get$wrapperName in a loop (N+1).
                        appendLine("suspend fun $dbName.getAll$wrapperName(ids: List<${ktType(leftPkCol)}>): List<$wrapperName> = useReaderConnection { transactor ->")
                        appendChunkedInQuery(
                            varName = "lefts",
                            accumulatorInit = "mutableListOf<$leftSimple>()",
                            idsExpr = "ids",
                            idColForBind = leftPkCol,
                            selectSqlPrefix = "SELECT * FROM ${leftAnalysis.tableName} WHERE `${leftPkCol.columnName}`",
                            perRowStatement = "lefts.add(${readEntityFunctionName(leftSimple)}(stmt))",
                        )
                        appendLine("    val leftIds = lefts.map { it.${leftPkCol.propName} }")
                        appendChunkedInQuery(
                            varName = "junctionRows",
                            accumulatorInit = "mutableListOf<$junctionSimple>()",
                            idsExpr = "leftIds",
                            idColForBind = leftPkCol,
                            selectSqlPrefix = "SELECT * FROM ${junctionAnalysis.tableName} WHERE `${spec.leftFkColumn}`",
                            perRowStatement = "junctionRows.add(${readEntityFunctionName(junctionSimple)}(stmt))",
                        )
                        appendLine("    val junctionsByLeftId = junctionRows.groupBy { it.${junctionLeftFkCol.propName} }")
                        appendLine("    val rightIds = junctionRows.map { it.${junctionRightFkCol.propName} }.distinct()")
                        appendChunkedInQuery(
                            varName = "rightsById",
                            accumulatorInit = "mutableMapOf<${ktType(rightPkCol)}, $rightSimple>()",
                            idsExpr = "rightIds",
                            idColForBind = rightPkCol,
                            selectSqlPrefix = "SELECT * FROM ${rightAnalysis.tableName} WHERE `${rightPkCol.columnName}`",
                            perRowStatement = "val r = ${readEntityFunctionName(rightSimple)}(stmt); rightsById[r.${rightPkCol.propName}] = r",
                        )
                        appendLine("    lefts.map { l ->")
                        appendLine("        val pairs = (junctionsByLeftId[l.${leftPkCol.propName}] ?: emptyList()).mapNotNull { j -> rightsById[j.${junctionRightFkCol.propName}]?.let { it to j } }")
                        appendLine("        $wrapperName(l, pairs)")
                        appendLine("    }")
                        appendLine("}")
                        appendRelationFlowFns(dbName, wrapperName, ktType(leftPkCol), setOf(leftAnalysis.tableName, junctionAnalysis.tableName, rightAnalysis.tableName))
                        appendRelationQueryFns(dbName, wrapperName, leftAnalysis.tableName, leftPkCol, setOf(leftAnalysis.tableName, junctionAnalysis.tableName, rightAnalysis.tableName))
                        appendLine()
                    }
                }
                writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}ManyToManyRelations", code)
            }
        }

        // ---- N-level nested relations, generalizing the same idea Room's own @Relation
        // processing uses for nested POJOs: recursively drill through "spine" edges - either
        // a 1:N relation or a many-to-many (junction) relation, so a chain can pass through
        // the middle of a M:N hop, not just terminate in one - terminating each chain in any
        // relation kind (1:1/1:N/M:N) at the bottom. Unlike Room, no POJO needs to be
        // hand-authored - the whole chain is inferred purely from the entity FK graph, same
        // as every other relation in this processor.
        //
        // maxNestDepth (a constructor param, defaulting to 3, configurable via a KSP
        // processor option - see ColumnsProcessorProvider) is the only knob for how deep a
        // chain can go, nothing else needs to change. It exists because a self-referencing
        // entity (like SessionV2's parent/child FK) would otherwise let a chain recurse
        // forever, and because the number of distinct chains can grow multiplicatively with
        // depth - defaults to 3 (parent -> child -> grandchild -> leaf-relation) to keep
        // codegen bounded out of the box.
        //
        // Every depth level - not just the last - gets BOTH a batch (ids-based) fetch and a
        // single-id convenience fetch. The batch fetch is what makes further nesting possible
        // without N+1: level D's batch fetch groups children per root with one query, then
        // calls level (D-1)'s already-generated batch fetch ONCE for the union of all
        // children across all roots, then reassembles per root - so total query count is
        // fixed per level (not per row), all the way down the chain.
        run {
            fun spineEdgesFrom(decl: KSClassDeclaration): List<SpineEdge> {
                val declQualified = decl.qualifiedName?.asString() ?: return emptyList()
                // 1:1 is included too, not just 1:N - the "child ids per root" grouping query
                // below doesn't care whether a root has 0-or-1 vs 0-or-many children, it just
                // builds a List either way, so a 1:1 relation composes as a mid-chain hop
                // exactly the same as a 1:N one, just with lists that happen to be ≤1 long.
                val fromOneToX = oneToXSpecs.filter { it.parentDecl.qualifiedName?.asString() == declQualified }
                    .map { SpineEdge.SingleFk(it) }
                val fromM2m = m2mSpecs.filter { it.leftDecl.qualifiedName?.asString() == declQualified }
                    .map { SpineEdge.ManyToMany(it) }
                return fromOneToX + fromM2m
            }

            data class NestSpec(
                val rootDecl: KSClassDeclaration,
                val label: String,
                val wrapperName: String,
                val wrapperPackage: String,
                val batchFnName: String,
                // every table this spec's fetch actually reads from, transitively - what a
                // Flow variant of this spec needs to invalidate on.
                val tables: Set<String>,
                // non-null only for depth > 1 (depth == 1 reuses the batch fetch already
                // generated by the relation/many-to-many blocks above - nothing new to emit).
                val spine: SpineEdge? = null,
                val childSpec: NestSpec? = null,
            )

            fun tableNameOfDecl(decl: KSClassDeclaration) = entityAnalyses.getValue(decl.qualifiedName!!.asString()).tableName

            fun baseSpecsFor(decl: KSClassDeclaration): List<NestSpec> {
                val declQualified = decl.qualifiedName?.asString() ?: return emptyList()
                val fromOneToX = oneToXSpecs.filter { it.parentDecl.qualifiedName?.asString() == declQualified }.map { s ->
                    val childSimple = s.childDecl.simpleName.asString()
                    val label = if (s.oneToOne) childSimple else "${childSimple}List"
                    val wrapperName = if (s.oneToOne) "${decl.simpleName.asString()}With$childSimple" else "${decl.simpleName.asString()}With${childSimple}List"
                    NestSpec(decl, label, wrapperName, s.parentDecl.packageName.asString(), "getAll$wrapperName", setOf(tableNameOfDecl(decl), tableNameOfDecl(s.childDecl)))
                }
                val fromM2m = m2mSpecs.filter { it.leftDecl.qualifiedName?.asString() == declQualified }.map { s ->
                    val rightSimple = s.rightDecl.simpleName.asString()
                    val junctionSimple = s.junctionDecl.simpleName.asString()
                    val label = "${rightSimple}Via$junctionSimple"
                    val wrapperName = "${decl.simpleName.asString()}With${rightSimple}Via$junctionSimple"
                    val tables = setOf(tableNameOfDecl(decl), tableNameOfDecl(s.junctionDecl), tableNameOfDecl(s.rightDecl))
                    NestSpec(decl, label, wrapperName, s.leftDecl.packageName.asString(), "getAll$wrapperName", tables)
                }
                return fromOneToX + fromM2m
            }

            val memo = mutableMapOf<Pair<String, Int>, List<NestSpec>>()
            fun specsAtDepth(decl: KSClassDeclaration, depth: Int): List<NestSpec> {
                val declQualified = decl.qualifiedName?.asString() ?: return emptyList()
                val key = declQualified to depth
                memo[key]?.let { return it }
                val result = if (depth == 1) {
                    baseSpecsFor(decl)
                } else {
                    spineEdgesFrom(decl).flatMap { spine ->
                        specsAtDepth(spine.nextDecl, depth - 1).map { child ->
                            val rootSimple = decl.simpleName.asString()
                            val nextSimple = spine.nextDecl.simpleName.asString()
                            val label = "${nextSimple}Then${child.label}"
                            val wrapperName = "${rootSimple}With$label"
                            val spineTable = if (spine is SpineEdge.ManyToMany) tableNameOfDecl(spine.rel.junctionDecl) else null
                            val tables = setOf(tableNameOfDecl(decl)) + child.tables + listOfNotNull(spineTable)
                            NestSpec(decl, label, wrapperName, decl.packageName.asString(), "getAll$wrapperName", tables, spine, child)
                        }
                    }
                }
                memo[key] = result
                return result
            }

            // collect every depth>1 spec reachable from any manageable entity, at every depth
            // up to the cap - these are the ones that actually need wrapper classes + fetch
            // functions generated (depth==1 specs are already fully generated above).
            val allGenerated = mutableListOf<NestSpec>()
            for (root in manageable) {
                for (depth in 2..maxNestDepth) {
                    allGenerated += specsAtDepth(root, depth)
                }
            }

            for (spec in allGenerated) {
                if (!generatedNestedWrapperFor.add("${spec.wrapperPackage}.${spec.wrapperName}")) continue
                val rootSimple = spec.rootDecl.simpleName.asString()
                val childSimple = spec.spine!!.nextDecl.simpleName.asString()
                val rootPropName = rootSimple.replaceFirstChar { it.lowercase() }
                val childPropName = childSimple.replaceFirstChar { it.lowercase() }
                val wrapperCode = "// GENERATED by :plugin-processor - nested relation wrapper: " +
                    "${spec.rootDecl.qualifiedName!!.asString()} -> ${spec.spine.nextDecl.qualifiedName!!.asString()} -> ${spec.childSpec!!.label}\n" +
                    "package ${spec.wrapperPackage}\n\n" +
                    "data class ${spec.wrapperName}(\n" +
                    "    val $rootPropName: $rootSimple,\n" +
                    "    val ${childPropName}NestedList: List<${spec.childSpec.wrapperName}>,\n" +
                    ")\n"
                writeFile(Dependencies(false, spec.rootDecl.containingFile!!), spec.wrapperPackage, spec.wrapperName, wrapperCode)
            }

            if (allGenerated.isNotEmpty()) {
                val imports = sortedSetOf(
                    "androidx.room3.useReaderConnection",
                    "com.poc.plugin.runtime.OrderBy",
                    "com.poc.plugin.runtime.Where",
                    "com.poc.plugin.runtime.bindingFunction",
                    "kotlinx.coroutines.flow.Flow",
                    "kotlinx.coroutines.flow.map",
                )
                for (spec in allGenerated) {
                    imports += "${spec.rootDecl.packageName.asString()}.${readEntityFunctionName(spec.rootDecl.simpleName.asString())}"
                    imports += "${spec.wrapperPackage}.${spec.wrapperName}"
                    imports += "${spec.childSpec!!.wrapperPackage}.${spec.childSpec.wrapperName}"
                }

                val code = buildString {
                    appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                    appendLine("package $packageName")
                    appendLine()
                    for (imp in imports) appendLine("import $imp")
                    appendLine()

                    for (spec in allGenerated) {
                        val rootSimple = spec.rootDecl.simpleName.asString()
                        val childSimple = spec.spine!!.nextDecl.simpleName.asString()
                        val rootAnalysis = entityAnalyses.getValue(spec.rootDecl.qualifiedName!!.asString())
                        val childAnalysis = entityAnalyses.getValue(spec.spine.nextDecl.qualifiedName!!.asString())
                        val rootPkCol = rootAnalysis.columns.first { it.columnName == rootAnalysis.primaryKeyColumns[0] }
                        val childPkCol = childAnalysis.columns.first { it.columnName == childAnalysis.primaryKeyColumns[0] }
                        val childInnerProp = childSimple.replaceFirstChar { it.lowercase() }
                        // the "child ids grouped by root id" query differs by spine kind: a
                        // 1:N spine reads the child's own table directly; a M:N spine reads
                        // the junction table instead, which already stores the right side's
                        // id as a plain column value - no extra lookup needed either way.
                        val (childIdsTable, childIdsSelectCol, rootFkCol) = when (val sp = spec.spine!!) {
                            is SpineEdge.SingleFk -> Triple(childAnalysis.tableName, childPkCol.columnName, sp.rel.fk.childColumns[0])
                            is SpineEdge.ManyToMany -> Triple(entityAnalyses.getValue(sp.rel.junctionDecl.qualifiedName!!.asString()).tableName, sp.rel.rightFkColumn, sp.rel.leftFkColumn)
                        }

                        appendLine("suspend fun $dbName.getAll${spec.wrapperName}(ids: List<${ktType(rootPkCol)}>): List<${spec.wrapperName}> {")
                        appendLine("    if (ids.isEmpty()) return emptyList()")
                        appendLine("    val roots = useReaderConnection { transactor ->")
                        appendChunkedInQuery(
                            varName = "roots",
                            accumulatorInit = "mutableListOf<$rootSimple>()",
                            idsExpr = "ids",
                            idColForBind = rootPkCol,
                            selectSqlPrefix = "SELECT * FROM ${rootAnalysis.tableName} WHERE `${rootPkCol.columnName}`",
                            perRowStatement = "roots.add(${readEntityFunctionName(rootSimple)}(stmt))",
                            indent = "        ",
                            emitReturnExpr = true,
                        )
                        appendLine("    }")
                        appendLine("    val rootIds = roots.map { it.${rootPkCol.propName} }")
                        appendLine("    val childIdsByRootId = useReaderConnection { transactor ->")
                        // read the FK column using rootPkCol's (always non-nullable) type info
                        // rather than the FK's own possibly-nullable one - safe because WHERE
                        // ... IN (ids) never returns NULL rows for that column, so no null
                        // check is needed here regardless of the FK's declared nullability.
                        appendChunkedInQuery(
                            varName = "childIdsByRootId",
                            accumulatorInit = "mutableMapOf<${ktType(rootPkCol)}, MutableList<${ktType(childPkCol)}>>()",
                            idsExpr = "rootIds",
                            idColForBind = rootPkCol,
                            selectSqlPrefix = "SELECT `$childIdsSelectCol`, `$rootFkCol` FROM $childIdsTable WHERE `$rootFkCol`",
                            perRowStatement = "val childId = ${readColumnExpr(childPkCol, 0)}; val rootFk = ${readColumnExpr(rootPkCol, 1)}; childIdsByRootId.getOrPut(rootFk) { mutableListOf() }.add(childId)",
                            indent = "        ",
                            emitReturnExpr = true,
                        )
                        appendLine("    }")
                        appendLine("    val allChildIds = childIdsByRootId.values.flatten().distinct()")
                        appendLine("    val childWrappersById = if (allChildIds.isEmpty()) emptyMap() else")
                        appendLine("        ${spec.childSpec!!.batchFnName}(allChildIds).associateBy { it.$childInnerProp.${childPkCol.propName} }")
                        appendLine("    return roots.map { r ->")
                        appendLine("        val childIds = childIdsByRootId[r.${rootPkCol.propName}] ?: emptyList()")
                        appendLine("        val nested = childIds.mapNotNull { childWrappersById[it] }")
                        appendLine("        ${spec.wrapperName}(r, nested)")
                        appendLine("    }")
                        appendLine("}")
                        appendLine()
                        appendLine("suspend fun $dbName.get${spec.wrapperName}(id: ${ktType(rootPkCol)}): ${spec.wrapperName}? =")
                        appendLine("    getAll${spec.wrapperName}(listOf(id)).firstOrNull()")
                        appendRelationFlowFns(dbName, spec.wrapperName, ktType(rootPkCol), spec.tables)
                        appendRelationQueryFns(dbName, spec.wrapperName, rootAnalysis.tableName, rootPkCol, spec.tables)
                        appendLine()
                    }
                }
                writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}NestedRelations", code)
            }
        }

        // ---- delete<Entity>Where(where) - goes through useWriterConnection() directly,
        // bypassing @RawQuery entirely (Room 3 routes @RawQuery through a reader
        // connection, confirmed empirically to be unable to reliably write - it either
        // throws "attempt to write a readonly database" on a file-backed db, or silently
        // deletes 0 rows on an in-memory one). No SQLiteStatement API exposes a portable
        // "rows changed" count, so this counts matches first and deletes within the
        // same writer-connection call for consistency. ----
        if (manageable.isNotEmpty()) {
            val code = buildString {
                appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                appendLine("package $packageName")
                appendLine()
                appendLine("import androidx.room3.useWriterConnection")
                appendLine("import com.poc.plugin.runtime.Where")
                appendLine("import com.poc.plugin.runtime.bindingFunction")
                appendLine()
                for (decl in manageable) {
                    val analysis = entityAnalyses.getValue(decl.qualifiedName!!.asString())
                    val simpleName = decl.simpleName.asString()
                    appendLine("suspend fun $dbName.delete${simpleName}Where(where: Where): Int = useWriterConnection { transactor ->")
                    appendLine("    val count = transactor.usePrepared(\"SELECT COUNT(*) FROM ${analysis.tableName} WHERE \${where.sql}\") { stmt ->")
                    appendLine("        where.bindingFunction()(stmt)")
                    appendLine("        stmt.step()")
                    appendLine("        stmt.getInt(0)")
                    appendLine("    }")
                    appendLine("    transactor.usePrepared(\"DELETE FROM ${analysis.tableName} WHERE \${where.sql}\") { stmt ->")
                    appendLine("        where.bindingFunction()(stmt)")
                    appendLine("        stmt.step()")
                    appendLine("    }")
                    appendLine("    count")
                    appendLine("}")
                    appendLine()
                }
            }
            writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}DeleteWhere", code)
        }

        // ---- update<Entity>Where(where, set) - same writer-connection-bypassing-@RawQuery
        // approach as delete<Entity>Where above, for the same reason (Room 3's @RawQuery
        // can't reliably write). Binds SET assignments first, then WHERE binders continuing
        // from that offset, matching "UPDATE t SET a=?,b=? WHERE c=?" parameter order. ----
        if (manageable.isNotEmpty()) {
            val code = buildString {
                appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                appendLine("package $packageName")
                appendLine()
                appendLine("import androidx.room3.useWriterConnection")
                appendLine("import com.poc.plugin.runtime.SetClause")
                appendLine("import com.poc.plugin.runtime.Where")
                appendLine("import com.poc.plugin.runtime.bindingFunction")
                appendLine()
                for (decl in manageable) {
                    val analysis = entityAnalyses.getValue(decl.qualifiedName!!.asString())
                    val simpleName = decl.simpleName.asString()
                    appendLine("suspend fun $dbName.update${simpleName}Where(where: Where, set: SetClause): Int = useWriterConnection { transactor ->")
                    appendLine("    val count = transactor.usePrepared(\"SELECT COUNT(*) FROM ${analysis.tableName} WHERE \${where.sql}\") { stmt ->")
                    appendLine("        where.bindingFunction()(stmt)")
                    appendLine("        stmt.step()")
                    appendLine("        stmt.getInt(0)")
                    appendLine("    }")
                    appendLine("    transactor.usePrepared(\"UPDATE ${analysis.tableName} SET \${set.sql} WHERE \${where.sql}\") { stmt ->")
                    appendLine("        set.bindingFunction(1)(stmt)")
                    appendLine("        where.binders.forEachIndexed { i, binder -> binder(stmt, set.placeholderCount + i + 1) }")
                    appendLine("        stmt.step()")
                    appendLine("    }")
                    appendLine("    count")
                    appendLine("}")
                    appendLine()
                }
            }
            writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}UpdateWhere", code)
        }

        // ---- query<Entity>Flow(where, ...) - a reactive counterpart to the Dao-level
        // Where-query wrapper, built on RoomDatabase.invalidationTracker.createFlow(table)
        // (confirmed empirically via InvalidationTrackerFlowExperiment to emit once
        // immediately on collection with no prior write, then again after every write to the
        // table - so this Flow always starts with current data, not just future changes).
        // Bypasses Dao/@RawQuery entirely and reuses the same read<Entity> row-reader as
        // relation fetches, so it's only available for readableForRelations entities. ----
        run {
            val flowable = manageable.filter { entityAnalyses[it.qualifiedName?.asString()]?.readableForRelations == true }
            if (flowable.isNotEmpty()) {
                val imports = sortedSetOf(
                    "androidx.room3.useReaderConnection",
                    "com.poc.plugin.runtime.OrderBy",
                    "com.poc.plugin.runtime.Where",
                    "com.poc.plugin.runtime.bindingFunction",
                    "kotlinx.coroutines.flow.Flow",
                    "kotlinx.coroutines.flow.map",
                )
                for (decl in flowable) {
                    imports += "${decl.packageName.asString()}.${readEntityFunctionName(decl.simpleName.asString())}"
                }
                val code = buildString {
                    appendLine("// GENERATED by :plugin-processor from @Database ${packageName}.${dbName} - do not edit")
                    appendLine("package $packageName")
                    appendLine()
                    for (imp in imports) appendLine("import $imp")
                    appendLine()
                    for (decl in flowable) {
                        val analysis = entityAnalyses.getValue(decl.qualifiedName!!.asString())
                        val simpleName = decl.simpleName.asString()
                        appendLine("fun $dbName.query${simpleName}Flow(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): Flow<List<$simpleName>> =")
                        appendLine("    invalidationTracker.createFlow(\"${analysis.tableName}\").map {")
                        appendLine("        useReaderConnection { transactor ->")
                        appendLine("            val sql = buildString {")
                        appendLine("                append(\"SELECT * FROM ${analysis.tableName} WHERE \")")
                        appendLine("                append(where.sql)")
                        appendLine("                if (orderBy != null) { append(\" ORDER BY \"); append(orderBy.sql) }")
                        appendLine("                if (limit != null) { append(\" LIMIT \"); append(limit) }")
                        appendLine("                if (offset != null) { append(\" OFFSET \"); append(offset) }")
                        appendLine("            }")
                        appendLine("            transactor.usePrepared(sql) { stmt ->")
                        appendLine("                where.bindingFunction()(stmt)")
                        appendLine("                val list = mutableListOf<$simpleName>()")
                        appendLine("                while (stmt.step()) list.add(${readEntityFunctionName(simpleName)}(stmt))")
                        appendLine("                list")
                        appendLine("            }")
                        appendLine("        }")
                        appendLine("    }")
                        appendLine()
                    }
                }
                writeFile(Dependencies(false, db.containingFile!!), packageName, "${dbName}FlowQueries", code)
            }
        }

        // NOTE: there is deliberately no "InstallDefaultConverters(builder)" generated here.
        // RoomDatabase.Builder.addColumnTypeConverter(...) only *supplies an instance* for a
        // converter Room already knows about via a compile-time @ColumnTypeConverters
        // reference - it can't teach Room's codegen about a type conversion path it didn't
        // know about at KSP time. Confirmed empirically: Room's own compiler fails with
        // "Cannot figure out how to save this property into database" for a field with no
        // @ColumnTypeConverters annotation at all, builder registration or not. So wiring a
        // generated <Type>DefaultJsonConverter in still requires one manual line on the
        // @Database (or @Entity/field): @ColumnTypeConverters(ShippingDefaultJsonConverter::class)
        // The processor generates the converter body; it cannot generate that annotation
        // reference, because it cannot edit the user's existing @Database/@Entity source.
    }
}

class ColumnsProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        // e.g. in the consuming module's build.gradle.kts:
        //   ksp { arg("roomPluginMaxNestDepth", "5"); arg("roomPluginSqliteInChunkSize", "500") }
        val maxNestDepth = environment.options["roomPluginMaxNestDepth"]?.toIntOrNull() ?: 3
        val sqliteInChunkSize = environment.options["roomPluginSqliteInChunkSize"]?.toIntOrNull() ?: 900
        return ColumnsProcessor(environment.codeGenerator, environment.logger, maxNestDepth, sqliteInChunkSize)
    }
}
