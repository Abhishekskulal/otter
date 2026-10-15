rootProject.name = "otter"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The React client is a separate npm project under client/ — not a Gradle module.
include("ot-core", "server")
