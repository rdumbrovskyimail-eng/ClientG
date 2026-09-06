package com.clientg.presentation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clientg.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
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
    val thoughtSignature: String? = null,
    // Блок рассуждений (Thinking Engine)
    val thoughtText: String = "",
    val thinkingDurationMs: Long = 0L,
    val isThinkingActive: Boolean = false,
    val isThinkingExpanded: Boolean = false,
    val hasThoughts: Boolean = false, // Постоянный якорь: карточка мыслей больше никогда не исчезает
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
    val enableSearch: Boolean = false,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
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

class ChatViewModel @JvmOverloads constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle? = null,
    private val externalClient: GeminiClient? = null
) : AndroidViewModel(application) {

    private val initialInputText: String = savedStateHandle?.get<String>(KEY_INPUT_DRAFT) ?: ""

    private val _uiState = MutableStateFlow(ChatUiState(inputText = initialInputText))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _uiEffects = Channel<ChatUiSideEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEffects: Flow<ChatUiSideEffect> = _uiEffects.receiveAsFlow()

    private val activeClient: GeminiClient
    private val shouldCloseClientOnExit: Boolean

    private var generationJob: Job? = null
    private var countdownJob: Job? = null
    private var activeTypewriter: TypewriterEngine? = null

    @Volatile
    private var securePrefs: SharedPreferences? = null

    @Volatile
    private var cachedApiKey: String = ""

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: createSafeSharedPreferences(context).also { securePrefs = it }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getSecurePrefs(getApplication())
            val savedKey = prefs.getString(PREF_KEY_API_KEY, "") ?: ""
            cachedApiKey = savedKey
            _uiState.update { it.copy(apiKey = savedKey) }
        }

        if (externalClient != null) {
            activeClient = externalClient
            shouldCloseClientOnExit = false
        } else {
            activeClient = GeminiClient(
                apiKeyProvider = { cachedApiKey.ifBlank { _uiState.value.apiKey } }
            )
            shouldCloseClientOnExit = true
        }
    }

    // ================================================================
    // Пользовательские интенты (User Actions)
    // ================================================================

    fun onInputTextChanged(newText: String) {
        savedStateHandle?.set(KEY_INPUT_DRAFT, newText)
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
        cachedApiKey = trimmed
        viewModelScope.launch(Dispatchers.IO) {
            getSecurePrefs(getApplication()).edit().putString(PREF_KEY_API_KEY, trimmed).apply()
            _uiState.update {
                it.copy(
                    apiKey = trimmed,
                    isApiKeyDialogOpen = false,
                    errorMessage = null
                )
            }
            _uiEffects.trySend(ChatUiSideEffect.ShowToast("API-ключ сохранен в защищенном хранилище"))
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun onToggleThinkingAccordion(messageId: String) {
        _uiState.update { state ->
            val messages = state.messages
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val newMessages = ArrayList(messages)
                val target = newMessages[idx]
                newMessages[idx] = target.copy(isThinkingExpanded = !target.isThinkingExpanded)
                state.copy(messages = newMessages)
            } else {
                state
            }
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
            val signal = CancellationSignal()
            val job = coroutineContext.job
            val handle = job.invokeOnCompletion { signal.cancel() }

            try {
                val context = getApplication<Application>()
                val resolver = context.contentResolver

                var fileName = "attachment.txt"
                var fileSize = 0L

                resolver.query(uri, null, null, null, null, signal)?.use { cursor ->
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

                val maxBytes = TextAttachment.MAX_ATTACHMENT_CHARS.toLong() * 4L
                if (fileSize > 0L && fileSize > maxBytes) {
                    val sizeMb = fileSize / (1024 * 1024)
                    val limitMb = maxBytes / (1024 * 1024)
                    throw IllegalArgumentException("Файл '$fileName' ($sizeMb МБ) превышает допустимый лимит ($limitMb МБ).")
                }

                val content = resolver.openInputStream(uri)?.use { stream ->
                    readStreamSafely(stream, TextAttachment.MAX_ATTACHMENT_CHARS)
                } ?: throw IllegalArgumentException("Не удалось открыть поток чтения файла.")

                val newAttachment = TextAttachment(fileName = fileName, content = content)

                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        val uniqueAttachment = makeUniqueAttachment(current.attachedFiles, newAttachment)
                        if (uniqueAttachment != null) {
                            current.copy(attachedFiles = current.attachedFiles + uniqueAttachment)
                        } else {
                            current
                        }
                    }
                    _uiEffects.trySend(ChatUiSideEffect.HapticLightTick)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "Ошибка вложения: ${e.localizedMessage}") }
                }
            } finally {
                handle.dispose()
            }
        }
    }

    fun onRemoveAttachment(attachment: TextAttachment) {
        _uiState.update { it.copy(attachedFiles = it.attachedFiles - attachment) }
    }

    private fun makeUniqueAttachment(existing: List<TextAttachment>, attachment: TextAttachment): TextAttachment? {
        if (existing.any { it.fileName == attachment.fileName && it.content == attachment.content }) {
            return null
        }
        if (existing.none { it.fileName == attachment.fileName }) {
            return attachment
        }
        val baseName = attachment.fileName.substringBeforeLast('.', attachment.fileName)
        val ext = if (attachment.fileName.contains('.')) ".${attachment.fileName.substringAfterLast('.')}" else ""
        var counter = 1
        var candidateName: String
        do {
            candidateName = "$baseName ($counter)$ext"
            counter++
        } while (existing.any { it.fileName == candidateName })

        return TextAttachment(fileName = candidateName, content = attachment.content)
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
                throw IllegalArgumentException("Текстовый файл превышает допустимый лимит ($maxChars символов).")
            }
            builder.append(buffer, 0, read)
        }

        var result = builder.toString()
        if (result.startsWith("\uFEFF")) {
            result = result.removePrefix("\uFEFF")
        }
        return result
    }

    // ================================================================
    // Оркестрация стриминга с интеграцией TypewriterEngine
    // ================================================================

    fun onSendMessage() {
        val state = _uiState.value
        val prompt = state.inputText.trim()
        val attachments = state.attachedFiles

        if (prompt.isBlank() && attachments.isEmpty()) return
        if (state.isGenerating) return

        if (state.apiKey.isBlank() && cachedApiKey.isBlank()) {
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
            isThinkingActive = true,
            isThinkingExpanded = true,
            hasThoughts = true
        )

        // Предотвращение обрыва генерации: сохраняем thoughtSignature от предыдущих ходов!
        val sanitizedHistory = state.messages.mapNotNull { msg ->
            if (msg.text.isBlank() && msg.attachments.isEmpty() && msg.thoughtSignature == null) {
                null
            } else {
                ChatMessage(
                    role = msg.role,
                    text = msg.text,
                    attachments = msg.attachments,
                    thoughtSignature = msg.thoughtSignature
                )
            }
        }

        savedStateHandle?.set(KEY_INPUT_DRAFT, "")
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
        onRetryMessage(targetMessageId = null)
    }

    fun onRetryMessage(targetMessageId: String? = null) {
        val state = _uiState.value
        if (state.isGenerating) return

        val targetModelIndex = if (targetMessageId != null) {
            state.messages.indexOfLast { it.id == targetMessageId }
        } else {
            state.messages.indexOfLast { it.role == ChatRole.MODEL }
        }

        val searchList = if (targetModelIndex != -1) state.messages.take(targetModelIndex + 1) else state.messages
        val targetUserMessage = searchList.lastOrNull { it.role == ChatRole.USER } ?: return
        val targetUserIndex = state.messages.indexOfLast { it.id == targetUserMessage.id }
        if (targetUserIndex == -1) return

        val messagesHistory = state.messages.take(targetUserIndex)

        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = UiChatMessage(
            id = assistantMessageId,
            role = ChatRole.MODEL,
            isStreaming = true,
            isThinkingActive = true,
            isThinkingExpanded = true,
            hasThoughts = true
        )

        val sanitizedHistory = messagesHistory.mapNotNull { msg ->
            if (msg.text.isBlank() && msg.attachments.isEmpty() && msg.thoughtSignature == null) {
                null
            } else {
                ChatMessage(
                    role = msg.role,
                    text = msg.text,
                    attachments = msg.attachments,
                    thoughtSignature = msg.thoughtSignature
                )
            }
        }

        _uiState.update { current ->
            current.copy(
                messages = messagesHistory + targetUserMessage + initialAssistantMessage,
                isGenerating = true,
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }

        executeStream(
            assistantMessageId = assistantMessageId,
            prompt = targetUserMessage.text,
            history = sanitizedHistory,
            attachments = targetUserMessage.attachments,
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
        _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
        _uiEffects.trySend(ChatUiSideEffect.HapticLightTick)

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            // Инициализация кадрового интерполятора
            val typewriter = TypewriterEngine(
                onFrame = { thoughtDelta, contentDelta ->
                    if (thoughtDelta.isEmpty() && contentDelta.isEmpty()) return@TypewriterEngine
                    _uiState.update { currentState ->
                        val messages = currentState.messages
                        val idx = messages.indexOfLast { it.id == assistantMessageId }
                        if (idx == -1) return@update currentState

                        val target = messages[idx]
                        val updated = target.copy(
                            thoughtText = if (thoughtDelta.isNotEmpty()) target.thoughtText + thoughtDelta else target.thoughtText,
                            text = if (contentDelta.isNotEmpty()) target.text + contentDelta else target.text,
                            hasThoughts = target.hasThoughts || thoughtDelta.isNotEmpty() || target.thoughtText.isNotEmpty()
                        )
                        val newMessages = ArrayList(messages)
                        newMessages[idx] = updated
                        currentState.copy(messages = newMessages)
                    }
                },
                onComplete = {
                    _uiState.update { currentState ->
                        val messages = currentState.messages
                        val idx = messages.indexOfLast { it.id == assistantMessageId }
                        if (idx == -1) {
                            currentState.copy(isGenerating = false)
                        } else {
                            val target = messages[idx]
                            val updated = target.copy(
                                isStreaming = false,
                                isThinkingActive = false
                            )
                            val newMessages = ArrayList(messages)
                            newMessages[idx] = updated
                            currentState.copy(messages = newMessages, isGenerating = false)
                        }
                    }
                    _uiEffects.trySend(ChatUiSideEffect.HapticGenerationFinished)
                    _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
                }
            )
            activeTypewriter = typewriter
            typewriter.start(this)

            try {
                activeClient.streamContent(
                    prompt = prompt,
                    history = history,
                    attachments = attachments,
                    thinkingLevel = thinkingLevel,
                    enableSearch = enableSearch
                ).collect { event ->
                    when (event) {
                        is GeminiStreamEvent.ThinkingStarted -> {
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                            }
                        }

                        is GeminiStreamEvent.ThinkingDelta -> {
                            typewriter.enqueueThought(event.text)
                        }

                        is GeminiStreamEvent.ThinkingCompleted -> {
                            typewriter.markThinkingEnded()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                                _uiEffects.trySend(ChatUiSideEffect.HapticThinkingCompleted)
                            }
                        }

                        is GeminiStreamEvent.ContentStarted -> {
                            typewriter.markThinkingEnded()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                                _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
                            }
                        }

                        is GeminiStreamEvent.ContentDelta -> {
                            typewriter.enqueueContent(event.text)
                        }

                        is GeminiStreamEvent.Completed -> {
                            typewriter.markStreamEnded()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                            }
                        }

                        else -> {
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    typewriter.stopAndFlush()
                    withContext(Dispatchers.Main.immediate) {
                        finalizeAssistantMessage(assistantMessageId, FinishReason.STOP)
                    }
                }
            } catch (e: GeminiApiException) {
                withContext(NonCancellable) {
                    typewriter.stopAndFlush()
                    withContext(Dispatchers.Main.immediate) {
                        handleApiError(assistantMessageId, e)
                    }
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    typewriter.stopAndFlush()
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                errorMessage = "Непредвиденная ошибка: ${e.localizedMessage}"
                            )
                        }
                        finalizeAssistantMessage(assistantMessageId, FinishReason.UNKNOWN)
                    }
                }
            }
        }
    }

    fun onCancelGeneration() {
        activeTypewriter?.stopAndFlush()
        activeTypewriter = null
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(isGenerating = false) }
    }

    private fun handleDiscreteEvent(assistantMessageId: String, event: GeminiStreamEvent) {
        _uiState.update { currentState ->
            val messages = currentState.messages
            val idx = messages.indexOfLast { it.id == assistantMessageId }
            if (idx == -1) return@update currentState

            val currentMsg = messages[idx]

            val updatedMessage = when (event) {
                is GeminiStreamEvent.Connecting -> currentMsg.copy(isStreaming = true)
                is GeminiStreamEvent.ThinkingStarted -> currentMsg.copy(
                    isThinkingActive = true,
                    isThinkingExpanded = true,
                    hasThoughts = true
                )
                is GeminiStreamEvent.ThinkingCompleted -> currentMsg.copy(
                    isThinkingActive = false,
                    isThinkingExpanded = false, // Плавно сворачиваем, но не удаляем!
                    hasThoughts = true,
                    thinkingDurationMs = currentMsg.thinkingDurationMs + event.durationMs
                )
                is GeminiStreamEvent.ContentStarted -> currentMsg.copy(
                    isThinkingActive = false,
                    isThinkingExpanded = false
                )
                is GeminiStreamEvent.SearchQueriesDiscovered -> currentMsg.copy(
                    searchQueries = (currentMsg.searchQueries + event.queries).distinct()
                )
                is GeminiStreamEvent.SourcesDiscovered -> currentMsg.copy(
                    sources = (currentMsg.sources + event.newSources).distinctBy { it.url }
                )
                is GeminiStreamEvent.CitationsDiscovered -> currentMsg.copy(
                    citations = (currentMsg.citations + event.citations).distinct()
                )
                is GeminiStreamEvent.SearchEntryPointRendered -> currentMsg.copy(
                    searchSuggestionsHtml = event.htmlContent
                )
                is GeminiStreamEvent.UsageReported -> currentMsg.copy(usage = event)
                is GeminiStreamEvent.Completed -> currentMsg.copy(
                    finishReason = event.finishReason,
                    thoughtSignature = event.thoughtSignature ?: currentMsg.thoughtSignature
                )
                else -> currentMsg
            }

            val newMessages = ArrayList(messages)
            newMessages[idx] = updatedMessage
            currentState.copy(messages = newMessages)
        }
    }

    private fun finalizeAssistantMessage(assistantMessageId: String, reason: FinishReason) {
        _uiState.update { state ->
            val filteredMessages = state.messages.filterNot { msg ->
                msg.id == assistantMessageId && msg.text.isEmpty() && msg.thoughtText.isEmpty()
            }
            state.copy(
                isGenerating = false,
                messages = filteredMessages.map { msg ->
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
            val filteredMessages = state.messages.filterNot { msg ->
                msg.id == assistantMessageId && msg.text.isEmpty() && msg.thoughtText.isEmpty()
            }
            val finalizedMessages = filteredMessages.map { msg ->
                if (msg.id == assistantMessageId) {
                    msg.copy(
                        isStreaming = false,
                        isThinkingActive = false,
                        finishReason = FinishReason.UNKNOWN
                    )
                } else {
                    msg
                }
            }
            state.copy(
                isGenerating = false,
                errorMessage = error.userFriendlyMessage,
                retryCountdownSeconds = error.retryAfterSeconds,
                messages = finalizedMessages
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
        activeTypewriter?.stopAndFlush()
        activeTypewriter = null
        generationJob?.cancel()
        countdownJob?.cancel()
        if (shouldCloseClientOnExit) {
            activeClient.close()
        }
    }

    companion object {
        private const val PREF_KEY_API_KEY = "gemini_api_key"
        private const val KEY_INPUT_DRAFT = "key_input_draft_text"

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
            } catch (_: Throwable) {
                context.getSharedPreferences("clientg_prefs_fallback", Context.MODE_PRIVATE)
            }
        }
    }
}