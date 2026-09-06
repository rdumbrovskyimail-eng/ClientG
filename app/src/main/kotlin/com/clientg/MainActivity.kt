package com.clientg

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.clientg.presentation.ChatViewModel
import android.graphics.Color as AndroidColor

/**
 * Главная точка входа приложения ClientG на платформе Android 16 (API 36).
 * Оптимизирована под процессор Snapdragon 8 Gen 2 for Galaxy и дисплей Dynamic AMOLED 2X.
 *
 * Интегрирует:
 * - Абсолютный Edge-to-Edge без системных подложек и затемнений;
 * - Аппаратное использование зоны выреза фронтальной камеры (Punch-Hole Cutout);
 * - Приоритет развертки 120 Гц (Frame Rate Category High);
 * - Системный прием текста и файлов (.txt) через Intent.ACTION_SEND и ACTION_SEND_MULTIPLE;
 * - Бесшовную работу в режимах разделения экрана One UI и Samsung DeX.
 */
class MainActivity : ComponentActivity() {

    // Инициализация ViewModel через стандартную фабрику платформы AndroidViewModelFactory
    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Аппаратная защита от белого/серого мерцания при холодном старте на AMOLED
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))

        // 2. Сквозной режим Edge-to-Edge без системных полос (scrims)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)

        // 3. Конфигурация окна под дисплей Samsung Galaxy S23 Ultra
        configureDisplayWindow()

        // 4. Обработка входящих данных из системного меню «Поделиться»
        handleIncomingIntent(intent)

        // 5. Запуск UI-слоя Jetpack Compose
        setContent {
            ChatGptScreen(viewModel = chatViewModel)
        }
    }

    /**
     * Обработка новых Intent без пересоздания Activity (SingleTop / SingleTask).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Системный перехват данных, отправленных в ClientG из других приложений (Браузер, Заметки, Файлы).
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            // Одиночный шаринг (Текст и/или один файл)
            Intent.ACTION_SEND -> {
                // А. Перехват прикрепленного файла (если есть)
                val fileUri: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (fileUri != null) {
                    chatViewModel.onAttachFileUri(fileUri)
                }

                // Б. Перехват сопутствующего текста или ссылки
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    chatViewModel.onInputTextChanged(sharedText)
                }
            }

            // Множественный шаринг (Пакет файлов из Samsung My Files)
            Intent.ACTION_SEND_MULTIPLE -> {
                val fileUris: ArrayList<Uri>? = IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )
                fileUris?.forEach { uri ->
                    chatViewModel.onAttachFileUri(uri)
                }
            }
        }
    }

    /**
     * Низкоуровневая конфигурация параметров дисплея и оконного менеджера:
     * - Отключение системных разделителей и принудительного контраста навигации;
     * - Сквозное заполнение зоны вокруг фронтальной камеры в любой ориентации;
     * - Принудительно белые иконки статусной строки на черном фоне;
     * - Аппаратная подсказка оконному менеджеру о частоте 120 FPS на Android 15/16.
     */
    private fun configureDisplayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Принудительно отключаем серые системные подложки One UI
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false

        // Использование площади вокруг выреза камеры
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        // Белые иконки часов, батареи и полоски жестов над черным AMOLED фоном
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        // Системный сигнал высокой частоты кадров (120 Гц) для Android 15/16
        if (Build.VERSION.SDK_INT >= 35) {
            window.setFrameRateCategory(WindowManager.LayoutParams.FRAME_RATE_CATEGORY_HIGH)
        }
    }
}