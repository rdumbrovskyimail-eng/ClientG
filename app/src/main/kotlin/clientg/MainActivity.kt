package clientg

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Настройка абсолютного погружения (Edge-to-Edge) под AMOLED-экран S23 Ultra.
        // Отключает системные подложки (scrim), делая строку состояния и навигацию прозрачными.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)

        setContent {
            ChatGptScreen(
                onSendMessage = { prompt ->
                    // Точка подключения отправки запроса в Gemini 3.8 Flash
                },
                onAttachFile = {
                    // Точка вызова системного выбора файлов (Photo Picker / Документы)
                }
            )
        }
    }
}