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
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe.NewPipeExtractor"); includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "OpenSoundboard"
include(":app")
