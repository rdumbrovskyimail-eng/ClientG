package com.clientg.presentation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clientg.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.util.UUID

// ====================================================================
// 1. Модели состояния и одноразовых эффектов (Presentation Layer)
// ====================================================================

data class UiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String = "",
    val attachments: List<TextAttachment> = emptyList(),
    // Блок рассуждений (Thinking Engine)
    val thoughtText: String = "",
    val thinkingDurationMs: Long = 0L,
    val isThinkingActive: Boolean = false,
    val isThinkingExpanded: Boolean = false,
    // Блок поиска Google Search Grounding
    val searchQueries: List<String> = emptyList(),
    val sources: List<GroundingSource> = emptyList(),
    val citations: List<InlineCitation> = emptyList(),
    val searchSuggestionsHtml: String? = null,
    // Метаданные токенизации и статус
    val usage: GeminiStreamEvent.UsageReported? = null,
    val finishReason: FinishReason? = null,
    val isStreaming: Boolean = false
)

data class ChatUiState(
    val messages: List<UiChatMessage> = emptyList(),
    val inputText: String = "",
    val attachedFiles: List<TextAttachment> = emptyList(),
    val isGenerating: Boolean = false,
    val enableSearch: Boolean = true, // По умолчанию веб-поиск ВКЛЮЧЕН
    val thinkingLevel: ThinkingLevel = ThinkingLevel.HIGH, // По умолчанию HIGH THINK
    val apiKey: String = "",
    val isApiKeyDialogOpen: Boolean = false,
    val errorMessage: String? = null,
    val retryCountdownSeconds: Long? = null
)

sealed interface ChatUiSideEffect {
    data object ScrollToBottom : ChatUiSideEffect
    data object HapticLightTick : ChatUiSideEffect
    data object HapticThinkingCompleted : ChatUiSideEffect
    data object HapticGenerationFinished : ChatUiSideEffect
    data class ShowToast(val message: String) : ChatUiSideEffect
}

// ====================================================================
// 2. ViewModel: ChatViewModel
// ====================================================================

class ChatViewModel(
    application: Application,
    private val externalClient: GeminiClient? = null
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _uiEffects = Channel<ChatUiSideEffect>(Channel.BUFFERED)
    val uiEffects: Flow<ChatUiSideEffect> = _uiEffects.receiveAsFlow()

    private val activeClient: GeminiClient
    private val shouldCloseClientOnExit: Boolean

    private var generationJob: Job? = null
    private var countdownJob: Job? = null

    private val securePrefs: SharedPreferences = createSafeSharedPreferences(application)

    init {
        val savedKey = securePrefs.getString(PREF_KEY_API_KEY, "") ?: ""
        _uiState.update { it.copy(apiKey = savedKey) }

        if (externalClient != null) {
            activeClient = externalClient
            shouldCloseClientOnExit = false
        } else {
            activeClient = GeminiClient(
                apiKeyProvider = { _uiState.value.apiKey }
            )
            shouldCloseClientOnExit = true
        }
    }

    // ================================================================
    // Пользовательские интенты (User Actions)
    // ================================================================

    fun onInputTextChanged(newText: String) {
        _uiState.update { it.copy(inputText = newText) }
    }

    fun onToggleSearch(enabled: Boolean) {
        _uiState.update { it.copy(enableSearch = enabled) }
    }

    fun onThinkingLevelChanged(level: ThinkingLevel) {
        _uiState.update { it.copy(thinkingLevel = level) }
    }

    fun onOpenApiKeyDialog() {
        _uiState.update { it.copy(isApiKeyDialogOpen = true) }
    }

    fun onCloseApiKeyDialog() {
        _uiState.update { it.copy(isApiKeyDialogOpen = false) }
    }

    fun onSaveApiKey(newKey: String) {
        val trimmed = newKey.trim()
        securePrefs.edit().putString(PREF_KEY_API_KEY, trimmed).apply()
        _uiState.update {
            it.copy(
                apiKey = trimmed,
                isApiKeyDialogOpen = false,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            _uiEffects.send(ChatUiSideEffect.ShowToast("API-ключ сохранен в защищенном хранилище"))
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun onToggleThinkingAccordion(messageId: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(isThinkingExpanded = !msg.isThinkingExpanded)
                    } else {
                        msg
                    }
                }
            )
        }
    }

    fun onClearChat() {
        onCancelGeneration()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                attachedFiles = emptyList(),
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }
    }

    // ================================================================
    // Безопасная работа с вложениями (Storage Access Framework)
    // ================================================================

    fun onAttachFileUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val resolver = context.contentResolver

                var fileName = "attachment.txt"
                var fileSize = 0L

                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                            fileName = cursor.getString(nameIdx) ?: fileName
                        }
                        if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                            fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                }

                val maxBytes = TextAttachment.MAX_ATTACHMENT_CHARS.toLong() * 2L
                if (fileSize > maxBytes) {
                    val sizeKb = fileSize / 1024
                    val limitKb = TextAttachment.MAX_ATTACHMENT_CHARS / 1024
                    throw IllegalArgumentException("Файл '$fileName' ($sizeKb КБ) превышает лимит ($limitKb КБ).")
                }

                val content = resolver.openInputStream(uri)?.use { stream ->
                    readStreamSafely(stream, TextAttachment.MAX_ATTACHMENT_CHARS)
                } ?: throw IllegalArgumentException("Не удалось открыть поток чтения файла.")

                val newAttachment = TextAttachment(fileName = fileName, content = content)

                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        if (current.attachedFiles.any { it.fileName == newAttachment.fileName }) {
                            current
                        } else {
                            current.copy(attachedFiles = current.attachedFiles + newAttachment)
                        }
                    }
                    _uiEffects.send(ChatUiSideEffect.HapticLightTick)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "Ошибка вложения: ${e.localizedMessage}") }
                }
            }
        }
    }

    fun onRemoveAttachment(attachment: TextAttachment) {
        _uiState.update { it.copy(attachedFiles = it.attachedFiles - attachment) }
    }

    private fun readStreamSafely(stream: InputStream, maxChars: Int): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(8192)
        val builder = StringBuilder()
        var totalCharsRead = 0

        while (true) {
            val read = reader.read(buffer)
            if (read == -1) break
            totalCharsRead += read
            if (totalCharsRead > maxChars) {
                throw IllegalArgumentException("Текстовый файл превышает допустимый лимит (${maxChars / 1024} КБ).")
            }
            builder.append(buffer, 0, read)
        }
        return builder.toString()
    }

    // ================================================================
    // Оркестрация стриминга с тактовой буферизацией под 120 FPS
    // ================================================================

    fun onSendMessage() {
        val state = _uiState.value
        val prompt = state.inputText.trim()
        val attachments = state.attachedFiles

        if (prompt.isBlank() && attachments.isEmpty()) return
        if (state.isGenerating) return

        if (state.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    isApiKeyDialogOpen = true,
                    errorMessage = "Для отправки запроса укажите Gemini API Key."
                )
            }
            return
        }

        countdownJob?.cancel()

        val userMessage = UiChatMessage(
            role = ChatRole.USER,
            text = prompt,
            attachments = attachments
        )

        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = UiChatMessage(
            id = assistantMessageId,
            role = ChatRole.MODEL,
            isStreaming = true,
            isThinkingActive = (state.thinkingLevel != ThinkingLevel.LOW),
            isThinkingExpanded = (state.thinkingLevel != ThinkingLevel.LOW)
        )

        val sanitizedHistory = state.messages.mapNotNull { msg ->
            if (msg.text.isBlank() && msg.attachments.isEmpty()) {
                null
            } else {
                ChatMessage(
                    role = msg.role,
                    text = msg.text,
                    attachments = msg.attachments
                )
            }
        }

        _uiState.update { current ->
            current.copy(
                messages = current.messages + userMessage + initialAssistantMessage,
                inputText = "",
                attachedFiles = emptyList(),
                isGenerating = true,
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }

        executeStream(
            assistantMessageId = assistantMessageId,
            prompt = prompt,
            history = sanitizedHistory,
            attachments = attachments,
            thinkingLevel = state.thinkingLevel,
            enableSearch = state.enableSearch
        )
    }

    fun onRetryLastMessage() {
        val state = _uiState.value
        if (state.isGenerating) return

        val lastUserMessage = state.messages.lastOrNull { it.role == ChatRole.USER } ?: return
        val messagesWithoutLastTurn = state.messages.dropLastWhile { it.role == ChatRole.MODEL || it.id == lastUserMessage.id }

        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = UiChatMessage(
            id = assistantMessageId,
            role = ChatRole.MODEL,
            isStreaming = true,
            isThinkingActive = (state.thinkingLevel != ThinkingLevel.LOW),
            isThinkingExpanded = (state.thinkingLevel != ThinkingLevel.LOW)
        )

        val sanitizedHistory = messagesWithoutLastTurn.mapNotNull { msg ->
            if (msg.text.isBlank() && msg.attachments.isEmpty()) {
                null
            } else {
                ChatMessage(
                    role = msg.role,
                    text = msg.text,
                    attachments = msg.attachments
                )
            }
        }

        _uiState.update { current ->
            current.copy(
                messages = messagesWithoutLastTurn + lastUserMessage + initialAssistantMessage,
                isGenerating = true,
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }

        executeStream(
            assistantMessageId = assistantMessageId,
            prompt = lastUserMessage.text,
            history = sanitizedHistory,
            attachments = lastUserMessage.attachments,
            thinkingLevel = state.thinkingLevel,
            enableSearch = state.enableSearch
        )
    }

    private fun executeStream(
        assistantMessageId: String,
        prompt: String,
        history: List<ChatMessage>,
        attachments: List<TextAttachment>,
        thinkingLevel: ThinkingLevel,
        enableSearch: Boolean
    ) {
        viewModelScope.launch {
            _uiEffects.send(ChatUiSideEffect.ScrollToBottom)
            _uiEffects.send(ChatUiSideEffect.HapticLightTick)
        }

        generationJob = viewModelScope.launch {
            val thoughtBuffer = StringBuilder()
            val contentBuffer = StringBuilder()
            var lastUiFlushTime = 0L

            suspend fun flushDeltasToUi() {
                if (thoughtBuffer.isEmpty() && contentBuffer.isEmpty()) return

                val thoughtDelta = thoughtBuffer.toString()
                val contentDelta = contentBuffer.toString()
                thoughtBuffer.setLength(0)
                contentBuffer.setLength(0)
                lastUiFlushTime = System.currentTimeMillis()

                _uiState.update { currentState ->
                    val updatedList = currentState.messages.map { msg ->
                        if (msg.id == assistantMessageId) {
                            msg.copy(
                                thoughtText = if (thoughtDelta.isNotEmpty()) msg.thoughtText + thoughtDelta else msg.thoughtText,
                                text = if (contentDelta.isNotEmpty()) msg.text + contentDelta else msg.text
                            )
                        } else {
                            msg
                        }
                    }
                    currentState.copy(messages = updatedList)
                }
            }

            try {
                activeClient.streamContent(
                    prompt = prompt,
                    history = history,
                    attachments = attachments,
                    thinkingLevel = thinkingLevel,
                    enableSearch = enableSearch
                ).collect { event ->
                    when (event) {
                        is GeminiStreamEvent.ThinkingDelta -> {
                            thoughtBuffer.append(event.text)
                            val now = System.currentTimeMillis()
                            if (now - lastUiFlushTime >= UI_BATCH_INTERVAL_MS) {
                                flushDeltasToUi()
                            }
                        }

                        is GeminiStreamEvent.ContentDelta -> {
                            contentBuffer.append(event.text)
                            val now = System.currentTimeMillis()
                            if (now - lastUiFlushTime >= UI_BATCH_INTERVAL_MS) {
                                flushDeltasToUi()
                            }
                        }

                        is GeminiStreamEvent.ThinkingCompleted -> {
                            flushDeltasToUi()
                            handleDiscreteEvent(assistantMessageId, event)
                            _uiEffects.send(ChatUiSideEffect.HapticThinkingCompleted)
                        }

                        is GeminiStreamEvent.ContentStarted -> {
                            flushDeltasToUi()
                            handleDiscreteEvent(assistantMessageId, event)
                            _uiEffects.send(ChatUiSideEffect.ScrollToBottom)
                        }

                        is GeminiStreamEvent.Completed -> {
                            flushDeltasToUi()
                            handleDiscreteEvent(assistantMessageId, event)
                            _uiEffects.send(ChatUiSideEffect.HapticGenerationFinished)
                            _uiEffects.send(ChatUiSideEffect.ScrollToBottom)
                        }

                        else -> {
                            flushDeltasToUi()
                            handleDiscreteEvent(assistantMessageId, event)
                        }
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    flushDeltasToUi()
                    finalizeAssistantMessage(assistantMessageId, FinishReason.STOP)
                }
            } catch (e: GeminiApiException) {
                withContext(NonCancellable) {
                    flushDeltasToUi()
                    handleApiError(assistantMessageId, e)
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    flushDeltasToUi()
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            errorMessage = "Непредвиденная ошибка: ${e.localizedMessage}"
                        )
                    }
                    finalizeAssistantMessage(assistantMessageId, FinishReason.UNKNOWN)
                }
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    fun onCancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(isGenerating = false) }
    }

    private fun handleDiscreteEvent(assistantMessageId: String, event: GeminiStreamEvent) {
        _uiState.update { currentState ->
            val updated = currentState.messages.map { msg ->
                if (msg.id != assistantMessageId) return@map msg

                when (event) {
                    is GeminiStreamEvent.Connecting -> {
                        msg.copy(isStreaming = true)
                    }

                    is GeminiStreamEvent.ThinkingStarted -> {
                        msg.copy(isThinkingActive = true, isThinkingExpanded = true)
                    }

                    is GeminiStreamEvent.ThinkingCompleted -> {
                        msg.copy(
                            isThinkingActive = false,
                            isThinkingExpanded = false,
                            thinkingDurationMs = msg.thinkingDurationMs + event.durationMs
                        )
                    }

                    is GeminiStreamEvent.ContentStarted -> {
                        msg.copy(isThinkingActive = false)
                    }

                    is GeminiStreamEvent.SearchQueriesDiscovered -> {
                        msg.copy(searchQueries = (msg.searchQueries + event.queries).distinct())
                    }

                    is GeminiStreamEvent.SourcesDiscovered -> {
                        msg.copy(sources = (msg.sources + event.newSources).distinctBy { it.url })
                    }

                    is GeminiStreamEvent.CitationsDiscovered -> {
                        msg.copy(citations = (msg.citations + event.citations).distinct())
                    }

                    is GeminiStreamEvent.SearchEntryPointRendered -> {
                        msg.copy(searchSuggestionsHtml = event.htmlContent)
                    }

                    is GeminiStreamEvent.UsageReported -> {
                        msg.copy(usage = event)
                    }

                    is GeminiStreamEvent.Completed -> {
                        msg.copy(
                            isStreaming = false,
                            isThinkingActive = false,
                            finishReason = event.finishReason
                        )
                    }

                    else -> msg
                }
            }
            currentState.copy(messages = updated)
        }
    }

    private fun finalizeAssistantMessage(assistantMessageId: String, reason: FinishReason) {
        _uiState.update { state ->
            state.copy(
                isGenerating = false,
                messages = state.messages.map { msg ->
                    if (msg.id == assistantMessageId) {
                        msg.copy(isStreaming = false, isThinkingActive = false, finishReason = reason)
                    } else {
                        msg
                    }
                }
            )
        }
    }

    private fun handleApiError(assistantMessageId: String, error: GeminiApiException) {
        _uiState.update { state ->
            state.copy(
                isGenerating = false,
                errorMessage = error.userFriendlyMessage,
                retryCountdownSeconds = error.retryAfterSeconds,
                // Удаляем пустой плейсхолдер сообщения, если генерация упала до первого токена
                messages = state.messages.filterNot { msg ->
                    msg.id == assistantMessageId && msg.text.isEmpty() && msg.thoughtText.isEmpty()
                }
            )
        }
        error.retryAfterSeconds?.let { seconds ->
            startRetryCountdown(seconds)
        }
    }

    private fun startRetryCountdown(totalSeconds: Long) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && isActive) {
                delay(1000)
                remaining--
                _uiState.update { it.copy(retryCountdownSeconds = remaining) }
            }
            _uiState.update { it.copy(retryCountdownSeconds = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        countdownJob?.cancel()
        if (shouldCloseClientOnExit) {
            activeClient.close()
        }
    }

    companion object {
        private const val PREF_KEY_API_KEY = "gemini_api_key"
        private const val UI_BATCH_INTERVAL_MS = 25L // 40 FPS порог для идеальной синхронизации со 120 Гц

        private fun createSafeSharedPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "clientg_secure_settings",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                context.getSharedPreferences("clientg_prefs_fallback", Context.MODE_PRIVATE)
            }
        }
    }
}