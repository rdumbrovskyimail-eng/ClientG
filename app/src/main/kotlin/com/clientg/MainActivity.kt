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

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Устранение Overdraw: аппаратная заливка окна черным цветом (#000000)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))

        // Сквозной режим Edge-to-Edge без затемнений
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)

        configureDisplayWindow()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            ChatGptScreen(viewModel = chatViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
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

    private fun configureDisplayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false

        val params = window.attributes
        // Без принудительного форсирования 120 Гц: контроллер экрана сам работает на своей физической частоте
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = params

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }
}