pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Репозиторий JetBrains Space для RC-версий компилятора Kotlin 2.4.x
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    }
}

dependencyResolutionManagement {
    // Запрещает дочерним модулям переопределять репозитории (стандарт безопасности Gradle)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Репозиторий JetBrains Space для предрелизных библиотек Kotlinx
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    }
}

// Включение типобезопасного доступа к подпроектам в Gradle (Type-safe Project Accessors)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "ClientG"

include(":app")