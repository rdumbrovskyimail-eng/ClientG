plugins {
    // Регистрация плагинов без применения к корню (apply false).
    // Модуль :app активирует их через алиасы из каталога libs.versions.toml.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Задача очистки сборочных артефактов по современному API Gradle 9.x
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}