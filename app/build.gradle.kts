plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
}

android {
    namespace = "com.poc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poc.app"
        minSdk = 26
        targetSdk = 35
        versionName = "1.0"
    }

    // "old" and "new" are two builds of the SAME app (same applicationId, same debug
    // signing key) that do NOT share any Person-related classes - "old" only compiles
    // PersonV1/PersonDbV1, "new" only compiles PersonV2/PersonDbV2+migration. Installing
    // "new" over "old" with `adb install -r` is a real app-upgrade, not a same-process
    // simulation.
    flavorDimensions += "stage"
    productFlavors {
        create("old") {
            dimension = "stage"
            versionCode = 1
            versionNameSuffix = "-old"
        }
        create("new") {
            dimension = "stage"
            versionCode = 2
            versionNameSuffix = "-new"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // debuggable so App Inspection / Database Inspector can attach
    buildTypes {
        debug {
            isDebuggable = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

val roomVersion = "3.0.1"
val sqliteVersion = "2.7.0"

dependencies {
    implementation("androidx.room3:room3-runtime:$roomVersion")
    ksp("androidx.room3:room3-compiler:$roomVersion")

    implementation(project(":plugin-runtime"))
    ksp(project(":plugin-processor"))

    // framework-backed driver: this is what makes the DB visible to Android Studio's
    // Database Inspector, unlike BundledSQLiteDriver used in the :poc JVM tests.
    implementation("androidx.sqlite:sqlite-framework:$sqliteVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
