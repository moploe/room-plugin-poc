plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("plugin.compose") version "2.1.0" apply false
    id("com.android.application") version "8.9.1" apply false
    // 0.37.0+ needs Kotlin 2.2 / Gradle 9.0 - pinned to 0.34.0 (last release compatible
    // with this project's Kotlin 2.1.0 / Gradle 8.11.1) instead of bumping those just for
    // publishing.
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}
