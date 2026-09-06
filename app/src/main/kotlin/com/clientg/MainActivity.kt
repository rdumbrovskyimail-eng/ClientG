package com.clientg

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import com.clientg.presentation.ChatViewModel
import android.graphics.Color as AndroidColor

/**
 * Главная точка входа приложения ClientG на платформе Android 16 (API 36).
 * Оптимизирована под процессор Snapdragon 8 Gen 2 for Galaxy и дисплей Dynamic AMOLED 2X.
 */
class MainActivity : ComponentActivity() {

    // Инициализация ViewModel через AndroidViewModelFactory
    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Устранение Overdraw: аппаратная заливка окна черным цветом (#000000)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))

        // 2. Сквозной режим Edge-to-Edge без системных полос (scrims)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)

        // 3. Аппаратная конфигурация окна под дисплей Samsung Galaxy S23 Ultra
        configureDisplayWindow()

        // 4. Обработка входящих данных только при первичном запуске
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        // 5. Запуск UI-слоя Jetpack Compose
        setContent {
            ChatGptScreen(viewModel = chatViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Дефект №10: Извлечение данных из Intent с обязательной поддержкой Android 13–16 ClipData
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                // Извлечение URI из EXTRA_STREAM либо резервное извлечение из clipData
                val fileUri: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

                if (fileUri != null) {
                    chatViewModel.onAttachFileUri(fileUri)
                }

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

                if (!sharedText.isNullOrBlank()) {
                    chatViewModel.onInputTextChanged(sharedText)
                }
                intent.action = null
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val fileUris: ArrayList<Uri>? = IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )

                if (!fileUris.isNullOrEmpty()) {
                    fileUris.forEach { uri -> chatViewModel.onAttachFileUri(uri) }
                } else {
                    intent.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let { uri ->
                                chatViewModel.onAttachFileUri(uri)
                            }
                        }
                    }
                }
                intent.action = null
            }
        }
    }

    /**
     * Конфигурация параметров дисплея под Samsung S23 Ultra:
     * - Сквозное заполнение зоны вокруг фронтальной камеры (Cutout Short Edges);
     * - Отключение системных разделителей навигации;
     * - Принудительно белые иконки статусной строки на черном фоне.
     */
    private fun configureDisplayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false

        val params = window.attributes
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = params

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }
}