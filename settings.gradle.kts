rootProject.name = "ClientG"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    }
}