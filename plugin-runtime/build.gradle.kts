plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    signing
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

group = "io.github.moploe"
version = "0.1.0"

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "room-plugin-runtime", version.toString())

    pom {
        name.set("Room Plugin Runtime")
        description.set("Runtime support (typed Where/Set DSL, auto-migration diff logic, JSON/enum helpers) for the Room 3 KSP companion plugin - see room-plugin-processor.")
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
