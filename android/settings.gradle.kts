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
        // MapLibre with 3D terrain comes only from the community build that scripts/fetch-maplibre-terrain.sh unpacks.
        exclusiveContent {
            forRepository { maven(uri("third_party/maplibre-terrain3d/maven-repository")) }
            filter { includeModule("org.maplibre.gl", "android-sdk-opengl") }
        }
        google()
        mavenCentral()
        // livekit-android depends on one library that only lives on JitPack (audioswitch); nothing else comes from here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}

rootProject.name = "convoyage"
include(":app")
