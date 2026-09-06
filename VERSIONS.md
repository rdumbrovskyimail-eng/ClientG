# 📦 Сводный отчет верификации версий (ClientG Stack)

Прямой аудит выполнен к официальным реестрам: Google Maven, Maven Central, Gradle Services.

| Категория | Компонент | Версия в проекте | Последний релиз в репо | Статус сети | Репозиторий |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Build System** | Gradle | `9.7.1` | `9.8.0-milestone-2` | ✅ Verified Online | [Gradle Services](https://services.gradle.org/versions/all) |
| **Build System** | Android Gradle Plugin (AGP) | `9.5.0-alpha04` | `9.5.0-alpha04` | ✅ Verified Online | [Google Maven](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml) |
| **Language** | Kotlin Compiler / Stdlib | `2.4.20-RC3` | `2.4.20-RC3` | ✅ Verified Online | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml) |
| **Android UI** | Compose BOM | `2026.08.00` | `2026.08.00` | ✅ Verified Online | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml) |
| **Android UI** | Activity Compose | `1.14.0-alpha01` | `1.14.0-alpha01` | ✅ Verified Online | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml) |
| **Android UI** | Compose Material 3 | `1.5.0-alpha27` | `1.5.0-alpha27` | ✅ Verified Online | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/maven-metadata.xml) |
| **Async** | Kotlinx Coroutines | `1.11.0` | `1.11.0` | ✅ Verified Online | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml) |
| **Serialization** | Kotlinx Serialization | `1.12.0-RC` | `1.12.0-RC` | ✅ Verified Online | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml) |
| **Networking** | Ktor Client (Core & CIO) | `3.5.2` | `3.5.2` | ✅ Verified Online | [Maven Central](https://repo1.maven.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml) |
| **Security** | AndroidX Security Crypto | `1.1.0` | `1.1.0` | ✅ Verified Online | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/maven-metadata.xml) |
| **AI SDK** | Google GenAI SDK | `1.70.0` | `1.70.0` | ✅ Verified Online | [Maven Central](https://repo1.maven.org/maven2/com/google/genai/google-genai/maven-metadata.xml) |

### Архитектурные метаданные:
- **Целевая платформа:** Android 16 (API 36)
- **Минимальный SDK:** Android 14 (API 34)
- **Целевое устройство:** Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2 for Galaxy)
- **Модель Gemini:** `gemini-3.8-flash` (Endpoint `v1beta`)
