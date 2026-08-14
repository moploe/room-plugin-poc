plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
}

repositories {
    google()
    mavenCentral()
}

val roomVersion = "3.0.1"
val sqliteVersion = "2.7.0"

dependencies {
    implementation("androidx.room3:room3-runtime:$roomVersion")
    ksp("androidx.room3:room3-compiler:$roomVersion")

    implementation(project(":plugin-runtime"))
    ksp(project(":plugin-processor"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("androidx.sqlite:sqlite-bundled:$sqliteVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.generateKotlin", "true")
    // bumped from the plugin's default of 3, to prove nested-relation depth is genuinely
    // configurable per-consumer via a KSP processor option, not just a hardcoded constant -
    // see ChunkedInQueryTest/NestedManyToManyChainTest for what this unlocks.
    arg("roomPluginMaxNestDepth", "4")
}
