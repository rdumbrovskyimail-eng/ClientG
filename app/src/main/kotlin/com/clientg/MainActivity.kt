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

    // Инициализация ViewModel через AndroidViewModelFactory (с поддержкой @JvmOverloads)
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

        // 3. Аппаратная конфигурация окна под дисплей Samsung Galaxy S23 Ultra
        configureDisplayWindow()

        // 4. Обработка входящих данных только при первичном запуске (защита от дублирования при повороте)
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
     * Системный перехват данных, отправленных в ClientG из других приложений.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val fileUri: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (fileUri != null) {
                    chatViewModel.onAttachFileUri(fileUri)
                }

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    chatViewModel.onInputTextChanged(sharedText)
                }
                // Сбрасываем действие, предотвращая повторную обработку тем же экземпляром
                intent.action = null
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val fileUris: ArrayList<Uri>? = IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )
                fileUris?.forEach { uri ->
                    chatViewModel.onAttachFileUri(uri)
                }
                intent.action = null
            }
        }
    }

    /**
     * Конфигурация параметров дисплея и оконного менеджера под Samsung S23 Ultra:
     * - Отключение системных разделителей и принудительного контраста навигации;
     * - Корректное сквозное заполнение зоны вокруг фронтальной камеры (Cutout Short Edges);
     * - Принудительно белые иконки статусной строки на черном фоне.
     */
    private fun configureDisplayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false

        // Корректное переприсвоение LayoutParams для вызова dispatchWindowAttributesChanged()
        val params = window.attributes
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = params

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }
}