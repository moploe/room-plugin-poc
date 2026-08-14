pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RoomPluginPOC"
include(":poc")
include(":app")
include(":plugin-runtime")
include(":plugin-processor")
