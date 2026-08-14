plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // deliberately NOT depending on androidx.room3:room3-runtime here - this module only
    // needs the KMP-common sqlite core types, so it stays safe to consume from both a
    // plain JVM module (:poc) and an Android app (:app) without variant conflicts.
    api("androidx.sqlite:sqlite:2.7.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(17)
}
