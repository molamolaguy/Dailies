pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central, tried first: it serves every file itself, while Maven Central
        // forwards some large ones (the Kotlin compiler) to GitHub release downloads, which have had outages.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central, tried first: it serves every file itself, while Maven Central
        // forwards some large ones (the Kotlin compiler) to GitHub release downloads, which have had outages.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}
rootProject.name = "Dailies"
include(":app")
