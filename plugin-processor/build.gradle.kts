plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    signing
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
}

kotlin {
    jvmToolchain(17)
}

group = "io.github.moploe"
version = "0.1.0"

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "room-plugin-processor", version.toString())

    pom {
        name.set("Room Plugin Processor")
        description.set("KSP processor for the Room 3 companion plugin - auto schema migration, typed Where/Set DSL, and relation fetching (1:1/1:N/M:N/nested) from plain @Entity/@Dao/@Database, no new annotations. Requires room-plugin-runtime at runtime.")
        inceptionYear.set("2026")
        url.set("https://github.com/moploe/room-plugin-poc")
        licenses {
            license {
                name.set("MIT")
                url.set("https://github.com/moploe/room-plugin-poc/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("moploe")
                name.set("moploe")
                url.set("https://github.com/moploe")
            }
        }
        scm {
            url.set("https://github.com/moploe/room-plugin-poc")
            connection.set("scm:git:git://github.com/moploe/room-plugin-poc.git")
            developerConnection.set("scm:git:ssh://git@github.com/moploe/room-plugin-poc.git")
        }
    }
}

signing {
    useGpgCmd()
}
