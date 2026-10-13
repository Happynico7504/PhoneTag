pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhoneTag"

include(":core")   // shared protocol + authoritative game engine (pure JVM)
include(":server") // dedicated internet game server
// Android client (Wi-Fi Direct host/join + online mode). Skipped for server-only builds
// (e.g. the Docker image) that have no Android SDK installed.
if (System.getenv("PHONETAG_SERVER_ONLY") == null) include(":app")
