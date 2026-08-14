plugins {
    kotlin("jvm")
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
