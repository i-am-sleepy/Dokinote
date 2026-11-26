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
        // Official Artifex repository for the MuPDF Android bindings (AGPL-3.0).
        maven {
            url = uri("https://maven.ghostscript.com/")
            content { includeGroup("com.artifex.mupdf") }
        }
    }
}
rootProject.name = "Dokinote"
include(":app")
