package com.clientg

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Фирменная палитра ChatGPT (Dark AMOLED)
private val GptBgColor = Color(0xFF212121)          // Основной темный фон экрана
private val GptInputBarBg = Color(0xFF2F2F2F)       // Фон капсулы ввода
private val GptPlusButtonBg = Color(0xFF383838)     // Фон кружка плюсика
private val GptTextPrimary = Color(0xFFECECEC)      // Цвет набираемого текста
private val GptTextPlaceholder = Color(0xFF8E8E93)  // Текст подсказки "Сообщение"
private val GptButtonInactive = Color(0xFF424242)   // Фон неактивной кнопки отправки
private val GptIconInactive = Color(0xFF6E6E6E)     // Иконка неактивной кнопки отправки
private val GptIconPlus = Color(0xFFD1D1D1)         // Цвет плюсика

@Composable
fun ChatGptScreen(
    onSendMessage: (String) -> Unit = {},
    onAttachFile: () -> Unit = {}
) {
    // Главный контейнер экрана с аппаратным учетом клавиатуры Android 16
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GptBgColor)
            // Плавный подъем всей планки над клавиатурой на 120 Гц
            .imePadding()
            // Отступ от нижней системной полоски жестов Samsung One UI
            .navigationBarsPadding()
    ) {
        // Пространство будущего чата
        Spacer(modifier = Modifier.fillMaxSize())

        // Нижняя строка ввода сообщений
        ChatGptInputBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            onSendMessage = onSendMessage,
            onAttachFile = onAttachFile
        )
    }
}

@Composable
fun ChatGptInputBar(
    modifier: Modifier = Modifier,
    onSendMessage: (String) -> Unit,
    onAttachFile: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // Автоматическая докрутка вниз при наборе длинного текста
    LaunchedEffect(text) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val isSendEnabled = text.isNotBlank()

    // Анимация кнопки отправки (переход серый -> белый)
    val sendButtonBg by animateColorAsState(
        targetValue = if (isSendEnabled) Color.White else GptButtonInactive,
        animationSpec = tween(durationMillis = 180),
        label = "sendButtonBgAnim"
    )
    val sendButtonIconColor by animateColorAsState(
        targetValue = if (isSendEnabled) Color.Black else GptIconInactive,
        animationSpec = tween(durationMillis = 180),
        label = "sendButtonIconAnim"
    )

    // Внешняя капсула в стиле GPT
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(GptInputBarBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        // Элементы всегда выровнены по нижнему краю при расширении текста
        verticalAlignment = Alignment.Bottom
    ) {
        // 1. Кнопка «+» слева (Прикрепление файлов)
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(GptPlusButtonBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = Color.White),
                    onClick = onAttachFile
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Прикрепить файл",
                tint = GptIconPlus,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 2. Окно ввода с внутренним скроллом вверх-вниз
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp, top = 2.dp)
                // Растет от 1 строки (~24dp) до ~6 строк (135dp), далее включается плавный скролл
                .heightIn(min = 24.dp, max = 135.dp)
                .verticalScroll(scrollState),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Сообщение",
                    color = GptTextPlaceholder,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
            }

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = GptTextPrimary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                )
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 3. Кнопка «Отправить» справа (Стрелка вверх)
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(sendButtonBg)
                .clickable(
                    enabled = isSendEnabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = Color.Black),
                    onClick = {
                        onSendMessage(text.trim())
                        text = ""
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Отправить сообщение",
                tint = sendButtonIconColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}