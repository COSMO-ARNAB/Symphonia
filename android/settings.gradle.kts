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

rootProject.name = "SymphoniaTestHarness"
include(":app")
include(":gate2:contracts")
include(":gate2:pcm")
include(":gate2:signaling")
include(":gate2:transport")
include(":gate2:diagnostics")
include(":gate2:benchmark")
include(":gate2-harness")
include(":gate2:spike")
include(":gate2:spike-app")
