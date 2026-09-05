# ====================================================================
# Метаданные JVM и читаемость стек-трейсов (Stack Traces)
# ====================================================================
# Сохраняем аннотации и номера строк, чтобы видеть точное место ошибки в коде
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses, EnclosingMethod
-keepattributes SourceFile, LineNumberTable

# ====================================================================
# R8 Full Mode: Оптимизация под Snapdragon 8 Gen 2 / Android 16
# ====================================================================
# Разрешаем оптимизатору уплотнять пакеты и делать методы публичными для инлайнинга
-allowaccessmodification
-repackageclasses ''

# ====================================================================
# Jetpack Compose (BOM 2026.x / Kotlin 2.4.x)
# ====================================================================
# Защита объектов состояния Compose от оптимизации полей
-keepclassmembers class * extends androidx.compose.runtime.State { *; }
-dontwarn androidx.compose.**

# ====================================================================
# Kotlinx Coroutines (1.11.x)
# ====================================================================
# Сохраняем фабрику Dispatchers.Main для плавной отрисовки на 120 Гц
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# ====================================================================
# Задел под Gemini API (Kotlinx Serialization)
# ====================================================================
# Защищаем поля DTO-моделей с аннотацией @SerialName от переименования
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}