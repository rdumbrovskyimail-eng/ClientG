package com.clientg

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clientg.network.ChatRole
import com.clientg.network.GroundingSource
import com.clientg.network.TextAttachment
import com.clientg.network.ThinkingLevel
import com.clientg.presentation.ChatUiSideEffect
import com.clientg.presentation.ChatViewModel
import com.clientg.presentation.UiChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ====================================================================
// Палитра True Dark AMOLED под Samsung Galaxy S23 Ultra
// ====================================================================

private val AmoledBg = Color(0xFF000000)
private val AmoledCardBg = Color(0xFF101010)
private val AmoledSurface = Color(0xFF171717)
private val AmoledInputBarBg = Color(0xFF1E1E1E)
private val AmoledButtonBg = Color(0xFF282828)
private val TextPrimary = Color(0xFFF2F2F2)
private val TextSecondary = Color(0xFF8E8E93)
private val TextMuted = Color(0xFF555555)

private val ThinkingGlowStart = Color(0xFF4C8DFF)
private val ThinkingGlowEnd = Color(0xFF6C5CE7)
private val ThinkingCardBg = Color(0xFF0E1218)
private val ThinkingBorder = Color(0xFF1A2230)

private val SearchActiveBg = Color(0xFF0F1E2E)
private val SearchActiveBorder = Color(0xFF183B5E)
private val SearchActiveText = Color(0xFF8AB4F8)

private val CodeBlockBg = Color(0xFF080808)
private val CodeBlockHeaderBg = Color(0xFF121212)
private val InlineCodeBg = Color(0xFF222222)
private val InlineCodeText = Color(0xFFFFD54F)

private val ArrowUpwardIcon: ImageVector by lazy {
    ImageVector.Builder("ArrowUp", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 12f)
            lineToRelative(1.41f, 1.41f)
            lineTo(11f, 7.83f)
            verticalLineTo(20f)
            horizontalLineToRelative(2f)
            verticalLineTo(7.83f)
            lineToRelative(5.58f, 5.59f)
            lineTo(20f, 12f)
            lineToRelative(-8f, -8f)
            close()
        }
    }.build()
}

private val StopSquareIcon: ImageVector by lazy {
    ImageVector.Builder("StopSquare", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 6f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(12f)
            horizontalLineTo(6f)
            close()
        }
    }.build()
}

private val GlobeIcon: ImageVector by lazy {
    ImageVector.Builder("Globe", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveToRelative(0f, 5.52f, 4.48f, 10f, 10f, 10f)
            curveToRelative(5.52f, 0f, 10f, -4.48f, 10f, -10f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(11f, 19.93f)
            curveTo(7.05f, 19.44f, 4f, 16.08f, 4f, 12f)
            curveToRelative(0f, -0.94f, 0.16f, -1.84f, 0.45f, -2.68f)
            lineToRelative(4.55f, 4.55f)
            verticalLineToRelative(1.06f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            verticalLineToRelative(2.93f)
            close()
        }
    }.build()
}

private val KeyIcon: ImageVector by lazy {
    ImageVector.Builder("Key", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 11f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            curveToRelative(0f, 1.31f, 0.84f, 2.41f, 2f, 2.83f)
            verticalLineTo(21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(1.17f)
            curveToRelative(1.16f, -0.42f, 2f, -1.52f, 2f, -2.83f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
            horizontalLineTo(7f)
            close()
        }
    }.build()
}

private val CopyIcon: ImageVector by lazy {
    ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }
    }.build()
}

// ====================================================================
// Главный экран: ChatGptScreen
// ====================================================================

@Composable
fun ChatGptScreen(
    viewModel: ChatViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= total - 2
        }
    }

    BackHandler(enabled = uiState.isApiKeyDialogOpen) {
        viewModel.onCloseApiKeyDialog()
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffects.collect { effect ->
            when (effect) {
                is ChatUiSideEffect.ScrollToBottom -> {
                    if (isAtBottom && uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.size - 1)
                    }
                }
                is ChatUiSideEffect.HapticLightTick -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                is ChatUiSideEffect.HapticThinkingCompleted -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is ChatUiSideEffect.HapticGenerationFinished -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is ChatUiSideEffect.ShowToast -> {
                    android.widget.Toast.makeText(context, effect.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onAttachFileUri(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBg)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatTopBar(
                currentLevel = uiState.thinkingLevel,
                onClearChat = { viewModel.onClearChat() },
                onOpenApiKeyDialog = { viewModel.onOpenApiKeyDialog() },
                onThinkingLevelSelected = { viewModel.onThinkingLevelChanged(it) }
            )

            if (uiState.errorMessage != null) {
                ErrorBanner(
                    message = uiState.errorMessage ?: "",
                    retrySeconds = uiState.retryCountdownSeconds,
                    onRetry = { viewModel.onRetryLastMessage() },
                    onDismiss = { viewModel.onDismissError() }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.messages.isEmpty()) {
                    EmptyStateHero(
                        onSuggestionClick = { prompt ->
                            viewModel.onInputTextChanged(prompt)
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(
                            items = uiState.messages,
                            key = { it.id }
                        ) { message ->
                            ChatMessageItem(
                                message = message,
                                onToggleThinking = { viewModel.onToggleThinkingAccordion(message.id) },
                                onOpenUrl = { url ->
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                },
                                onRegenerate = { viewModel.onRetryLastMessage() },
                                onShareText = { textToShare ->
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, textToShare)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Поделиться"))
                                }
                            )
                        }
                    }

                    // Обернуто в явный Column для устранения DSL_SCOPE_VIOLATION компилятора K2
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AnimatedVisibility(
                            visible = !isAtBottom && uiState.messages.isNotEmpty(),
                            enter = scaleIn(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)) + fadeIn(),
                            exit = scaleOut() + fadeOut()
                        ) {
                            FloatingScrollBottomButton(
                                onClick = {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(uiState.messages.size - 1)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (uiState.attachedFiles.isNotEmpty()) {
                AttachmentChipsBar(
                    attachments = uiState.attachedFiles,
                    onRemove = { viewModel.onRemoveAttachment(it) }
                )
            }

            ChatGptInputBar(
                text = uiState.inputText,
                isGenerating = uiState.isGenerating,
                enableSearch = uiState.enableSearch,
                onTextChanged = { viewModel.onInputTextChanged(it) },
                onToggleSearch = { viewModel.onToggleSearch(!uiState.enableSearch) },
                onSendMessage = {
                    viewModel.onSendMessage()
                    coroutineScope.launch {
                        delay(60)
                        if (uiState.messages.isNotEmpty()) {
                            listState.animateScrollToItem(uiState.messages.size)
                        }
                    }
                },
                onCancelGeneration = { viewModel.onCancelGeneration() },
                onAttachClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "text/*",
                            "application/json",
                            "application/xml",
                            "application/javascript",
                            "application/x-yaml",
                            "application/octet-stream"
                        )
                    )
                }
            )
        }

        if (uiState.isApiKeyDialogOpen) {
            ApiKeyDialog(
                currentKey = uiState.apiKey,
                onSave = { viewModel.onSaveApiKey(it) },
                onDismiss = { viewModel.onCloseApiKeyDialog() }
            )
        }
    }
}

// ====================================================================
// Стартовый экран (Empty State Hero)
// ====================================================================

@Composable
private fun EmptyStateHero(
    onSuggestionClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF1E2A38), Color(0xFF0D1217))
                        )
                    )
                    .border(1.dp, Color(0xFF283A4E), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✦",
                    color = SearchActiveText,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Чем я могу помочь?",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Gemini 3.8 Flash • Deep Reasoning • Grounding",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
            )

            val suggestions = listOf(
                "🔍 Свежие новости о релизе Android 16",
                "⚡ Архитектура корутин без дропов кадров на 120 Гц",
                "📄 Проанализируй прикрепленный файл логов"
            )

            suggestions.forEach { prompt ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AmoledSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222222)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSuggestionClick(prompt) }
                ) {
                    Text(
                        text = prompt,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}

// ====================================================================
// Верхняя панель (Top Bar)
// ====================================================================

@Composable
private fun ChatTopBar(
    currentLevel: ThinkingLevel,
    onClearChat: () -> Unit,
    onOpenApiKeyDialog: () -> Unit,
    onThinkingLevelSelected: (ThinkingLevel) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ClientG",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.width(6.dp))

                Box {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF192230),
                        modifier = Modifier.clickable { menuExpanded = true }
                    ) {
                        Text(
                            text = "3.8 FLASH • ${currentLevel.name}",
                            color = SearchActiveText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(AmoledSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("HIGH (Максимальное рассуждение)", color = TextPrimary, fontSize = 13.sp) },
                            onClick = {
                                onThinkingLevelSelected(ThinkingLevel.HIGH)
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("MEDIUM (Сбалансированное)", color = TextPrimary, fontSize = 13.sp) },
                            onClick = {
                                onThinkingLevelSelected(ThinkingLevel.MEDIUM)
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("LOW (Минимальная задержка)", color = TextPrimary, fontSize = 13.sp) },
                            onClick = {
                                onThinkingLevelSelected(ThinkingLevel.LOW)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
            Text(
                text = "Snapdragon 8 Gen 2 • 120Hz LTPO AMOLED",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClearChat,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AmoledSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Очистить диалог",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onOpenApiKeyDialog,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AmoledSurface)
            ) {
                Icon(
                    imageVector = KeyIcon,
                    contentDescription = "Настройки API ключа",
                    tint = TextSecondary,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

// ====================================================================
// Баннер ошибок и квот
// ====================================================================

@Composable
private fun ErrorBanner(
    message: String,
    retrySeconds: Long?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF261212)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4A2020))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    color = Color(0xFFFFB4A9),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                if (retrySeconds != null) {
                    Text(
                        text = "Повтор доступен через $retrySeconds сек...",
                        color = Color(0xFFFFDAD4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (retrySeconds == null || retrySeconds <= 0) {
                TextButton(onClick = onRetry) {
                    Text("Повторить", color = Color(0xFFFF897D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFFFFB4A9))
            }
        }
    }
}

// ====================================================================
// Сообщение чата
// ====================================================================

@Composable
private fun ChatMessageItem(
    message: UiChatMessage,
    onToggleThinking: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRegenerate: () -> Unit,
    onShareText: (String) -> Unit
) {
    val isUser = message.role == ChatRole.USER
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1500)
            isCopied = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            if (message.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    message.attachments.forEach { att ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AmoledSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282828))
                        ) {
                            Text(
                                text = "📄 ${att.fileName}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(Color(0xFF242424))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (message.thoughtText.isNotEmpty() || message.isThinkingActive) {
                    ThinkingAccordionCard(
                        thoughtText = message.thoughtText,
                        durationMs = message.thinkingDurationMs,
                        isActive = message.isThinkingActive,
                        isExpanded = message.isThinkingExpanded,
                        onToggle = onToggleThinking
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (message.searchQueries.isNotEmpty() || message.sources.isNotEmpty()) {
                    SearchGroundingBlock(
                        queries = message.searchQueries,
                        sources = message.sources,
                        onOpenUrl = onOpenUrl
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (message.text.isNotEmpty()) {
                    SelectionContainer {
                        NativeMarkdownContent(text = message.text)
                    }
                } else if (message.isStreaming && !message.isThinkingActive) {
                    Text(
                        text = "Генерирует ответ...",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic
                    )
                }

                if (!message.isStreaming && message.text.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        message.usage?.let { usage ->
                            Text(
                                text = "Вход: ${usage.promptTokens} • Мысли: ${usage.thoughtsTokens} • Выход: ${usage.candidateTokens} • Кэш: ${usage.cacheHitPercentage.toInt()}%",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isCopied = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCopied) Icons.Default.Check else CopyIcon,
                                    contentDescription = "Скопировать ответ",
                                    tint = if (isCopied) Color(0xFF81C784) else TextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            IconButton(
                                onClick = { onShareText(message.text) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Поделиться",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Повторить",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// Thinking Accordion
// ====================================================================

@Composable
private fun ThinkingAccordionCard(
    thoughtText: String,
    durationMs: Long,
    isActive: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val glowAlpha = if (isActive) {
        val infiniteTransition = rememberInfiniteTransition(label = "ThinkingPulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowAlpha"
        )
        alpha
    } else {
        1f
    }

    val gradientBrush = remember(glowAlpha) {
        Brush.horizontalGradient(
            listOf(
                ThinkingGlowStart.copy(alpha = glowAlpha),
                ThinkingGlowEnd.copy(alpha = glowAlpha)
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isActive) {
                    Modifier.border(width = 1.dp, brush = gradientBrush, shape = RoundedCornerShape(14.dp))
                } else {
                    Modifier.border(1.dp, ThinkingBorder, RoundedCornerShape(14.dp))
                }
            )
            .clickable(onClick = onToggle, role = Role.Button)
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = ThinkingCardBg)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .graphicsLayer { alpha = if (isActive) glowAlpha else 1f }
                            .clip(CircleShape)
                            .background(if (isActive) Color(0xFF4C8DFF) else Color(0xFF757575))
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = if (isActive) "Размышляет над задачей..." else "Ход мыслей (${String.format(java.util.Locale.US, "%.1f", durationMs / 1000f)}с)",
                        color = if (isActive) Color(0xFF8AB4F8) else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Divider(color = ThinkingBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = thoughtText.ifEmpty { "Анализ контекста и синтез ответа..." },
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ====================================================================
// Google Search Grounding: карточки источников
// ====================================================================

@Composable
private fun SearchGroundingBlock(
    queries: List<String>,
    sources: List<GroundingSource>,
    onOpenUrl: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (queries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                queries.forEach { q ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SearchActiveBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SearchActiveBorder)
                    ) {
                        Text(
                            text = "🔍 $q",
                            color = SearchActiveText,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEachIndexed { index, source ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AmoledCardBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222222)),
                        modifier = Modifier
                            .clickable { onOpenUrl(source.url) }
                            .widthIn(max = 210.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "[${index + 1}] ${source.title}",
                                color = Color(0xFFE2E2E2),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = source.url.removePrefix("https://").substringBefore('/'),
                                color = TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// Нативный Markdown-парсер
// ====================================================================

@Composable
private fun NativeMarkdownContent(text: String) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    Text(
                        text = block.text,
                        color = TextPrimary,
                        fontSize = when (block.level) {
                            1 -> 20.sp
                            2 -> 18.sp
                            else -> 16.sp
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.Bullet -> {
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Text(text = "• ", color = SearchActiveText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = remember(block.content) { parseInlineMarkdown(block.content) },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = remember(block.content) { parseInlineMarkdown(block.content) },
                        color = TextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
                is MarkdownBlock.Code -> {
                    CodeBlockCard(language = block.language, content = block.content)
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(language: String, content: String) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1500)
            isCopied = false
        }
    }

    val langColor = when (language.lowercase()) {
        "kotlin", "kt" -> Color(0xFF7F52FF)
        "python", "py" -> Color(0xFF3776AB)
        "json", "xml" -> Color(0xFFFFD54F)
        "sh", "bash" -> Color(0xFF4CAF50)
        else -> Color(0xFFAAAAAA)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CodeBlockBg)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeBlockHeaderBg)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(langColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = language.ifEmpty { "code" }.uppercase(),
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(content))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isCopied = true
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isCopied) Icons.Default.Check else CopyIcon,
                        contentDescription = "Скопировать",
                        tint = if (isCopied) Color(0xFF81C784) else Color(0xFFAAAAAA),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isCopied) "Скопировано" else "Скопировать",
                        color = if (isCopied) Color(0xFF81C784) else Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Text(
                text = content,
                color = Color(0xFFE0E0E0),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp)
            )
        }
    }
}

private sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val content: String) : MarkdownBlock
    data class Paragraph(val content: String) : MarkdownBlock
    data class Code(val language: String, val content: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val fence = "```"
    var cursor = 0

    while (cursor < input.length) {
        val startFence = input.indexOf(fence, cursor)
        if (startFence == -1) {
            parseLinesIntoBlocks(input.substring(cursor), blocks)
            break
        }

        if (startFence > cursor) {
            parseLinesIntoBlocks(input.substring(cursor, startFence), blocks)
        }

        val afterStartFence = startFence + fence.length
        val langEnd = input.indexOf('\n', afterStartFence)
        if (langEnd == -1) {
            parseLinesIntoBlocks(input.substring(cursor), blocks)
            break
        }

        val language = input.substring(afterStartFence, langEnd).trim()
        val endFence = input.indexOf(fence, langEnd + 1)
        if (endFence == -1) {
            parseLinesIntoBlocks(input.substring(cursor), blocks)
            break
        }

        val code = input.substring(langEnd + 1, endFence)
        blocks.add(MarkdownBlock.Code(language, code))
        cursor = endFence + fence.length
    }
    return blocks
}

private fun parseLinesIntoBlocks(text: String, out: MutableList<MarkdownBlock>) {
    val lines = text.lines()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        when {
            trimmed.startsWith("### ") -> out.add(MarkdownBlock.Header(3, trimmed.removePrefix("### ").trim()))
            trimmed.startsWith("## ") -> out.add(MarkdownBlock.Header(2, trimmed.removePrefix("## ").trim()))
            trimmed.startsWith("# ") -> out.add(MarkdownBlock.Header(1, trimmed.removePrefix("# ").trim()))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> out.add(MarkdownBlock.Bullet(trimmed.substring(2).trim()))
            else -> out.add(MarkdownBlock.Paragraph(line))
        }
    }
}

private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                val nextTick = text.indexOf('`', i + 1)
                if (nextTick != -1) {
                    val codeSnippet = text.substring(i + 1, nextTick)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = InlineCodeBg,
                            color = InlineCodeText,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" $codeSnippet ")
                    }
                    i = nextTick + 1
                    continue
                }
            }

            if (text.startsWith("**", i)) {
                val nextStars = text.indexOf("**", i + 2)
                if (nextStars != -1) {
                    val boldText = text.substring(i + 2, nextStars)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                        append(boldText)
                    }
                    i = nextStars + 2
                    continue
                }
            }

            append(text[i])
            i++
        }
    }
}

// ====================================================================
// Панель вложений
// ====================================================================

@Composable
private fun AttachmentChipsBar(
    attachments: List<TextAttachment>,
    onRemove: (TextAttachment) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { att ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AmoledSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📄 ${att.fileName}",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Удалить вложение",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onRemove(att) }
                    )
                }
            }
        }
    }
}

// ====================================================================
// Парящая кнопка скролла вниз (Smart Scroll FAB)
// ====================================================================

@Composable
private fun FloatingScrollBottomButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .size(38.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = AmoledSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Вниз",
                tint = TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ====================================================================
// Капсула ввода с фокусом BringIntoView
// ====================================================================

@Composable
fun ChatGptInputBar(
    text: String,
    isGenerating: Boolean,
    enableSearch: Boolean,
    onTextChanged: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onSendMessage: () -> Unit,
    onCancelGeneration: () -> Unit,
    onAttachClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isSendEnabled = text.isNotBlank() && !isGenerating
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val sendButtonBg by animateColorAsState(
        targetValue = if (isGenerating) Color.White else if (isSendEnabled) Color.White else Color(0xFF333333),
        animationSpec = tween(150),
        label = "btnBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(AmoledInputBarBg)
            .border(1.dp, Color(0xFF292929), RoundedCornerShape(26.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(AmoledButtonBg)
                .clickable(onClick = onAttachClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Прикрепить файл",
                tint = Color(0xFFD1D1D1),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (enableSearch) SearchActiveBg else AmoledButtonBg)
                .border(
                    width = 1.dp,
                    color = if (enableSearch) SearchActiveBorder else Color.Transparent,
                    shape = CircleShape
                )
                .semantics { role = Role.Switch }
                .clickable(onClick = onToggleSearch),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = GlobeIcon,
                contentDescription = if (enableSearch) "Веб-поиск включен" else "Веб-поиск выключен",
                tint = if (enableSearch) SearchActiveText else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp, top = 4.dp)
                .heightIn(min = 24.dp, max = 120.dp)
                .verticalScroll(scrollState)
                .bringIntoViewRequester(bringIntoViewRequester),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Спросить Gemini 3.8...",
                    color = Color(0xFF757575),
                    fontSize = 15.sp
                )
            }

            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusEvent { event ->
                        if (event.isFocused) {
                            coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                        }
                    },
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(sendButtonBg)
                .clickable {
                    if (isGenerating) {
                        onCancelGeneration()
                    } else if (isSendEnabled) {
                        onSendMessage()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isGenerating) {
                Icon(
                    imageVector = StopSquareIcon,
                    contentDescription = "Остановить генерацию",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Icon(
                    imageVector = ArrowUpwardIcon,
                    contentDescription = "Отправить запрос",
                    tint = if (isSendEnabled) Color.Black else Color(0xFF6E6E6E),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ====================================================================
// Диалог API-ключа
// ====================================================================

@Composable
private fun ApiKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmoledSurface,
        title = {
            Text("Gemini API Key", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "Ключ защищен аппаратным модулем Knox Vault (AES-256) и используется для вызовов модели Gemini 3.8 Flash.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = {
                            passwordVisible = !passwordVisible
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Check else KeyIcon,
                                contentDescription = if (passwordVisible) "Скрыть ключ" else "Показать ключ",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF383838)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(key) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("Сохранить", color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondary)
            }
        }
    )
}