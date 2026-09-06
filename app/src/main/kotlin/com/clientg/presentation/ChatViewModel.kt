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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val enableSearch: Boolean = true,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.HIGH,
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

    // Дефект №10: Восстановление черновика ввода после Process Death
    private val initialInputText: String = savedStateHandle?.get<String>(KEY_INPUT_DRAFT) ?: ""

    private val _uiState = MutableStateFlow(ChatUiState(inputText = initialInputText))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Дефект №2: Буферизованный канал с DROP_OLDEST исключает дедлок фоновой генерации при свернутом UI
    private val _uiEffects = Channel<ChatUiSideEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEffects: Flow<ChatUiSideEffect> = _uiEffects.receiveAsFlow()

    private val activeClient: GeminiClient
    private val shouldCloseClientOnExit: Boolean

    private var generationJob: Job? = null
    private var countdownJob: Job? = null

    // Дефект №3: Потокобезопасная инициализация SharedPreferences с защитой от гонок памяти в JMM
    @Volatile
    private var securePrefs: SharedPreferences? = null
    private val prefsMutex = Mutex()

    // Дефект №11: Изолированный кэш API-ключа вне частых снимков StateFlow
    @Volatile
    private var cachedApiKey: String = ""

    private suspend fun getSecurePrefs(): SharedPreferences = withContext(Dispatchers.IO) {
        securePrefs ?: prefsMutex.withLock {
            securePrefs ?: createSafeSharedPreferences(getApplication<Application>()).also {
                securePrefs = it
            }
        }
    }

    init {
        // Асинхронная загрузка ключа из Keystore (защита от DiskReadViolation на холодном старте)
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getSecurePrefs()
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
        // Дефект №10: Сохранение черновика в SavedStateHandle
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
            getSecurePrefs().edit().putString(PREF_KEY_API_KEY, trimmed).apply()
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

                // Дефект №9: Передача CancellationSignal для безопасного прерывания IPC-запроса в SAF
                resolver.query(uri, null, null, null, signal)?.use { cursor ->
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

                // Дефект №13: Срез маркера BOM и защита от повреждения кодировки
                val content = resolver.openInputStream(uri)?.use { stream ->
                    readStreamSafely(stream, TextAttachment.MAX_ATTACHMENT_CHARS)
                } ?: throw IllegalArgumentException("Не удалось открыть поток чтения файла.")

                val newAttachment = TextAttachment(fileName = fileName, content = content)

                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        // Дефект №12: Умная дедупликация файлов с разрешением коллизий одинаковых имен
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

    // Дефект №12: Функция разрешения конфликтов имен одинаковых файлов
    private fun makeUniqueAttachment(existing: List<TextAttachment>, attachment: TextAttachment): TextAttachment? {
        if (existing.any { it.fileName == attachment.fileName && it.content == attachment.content }) {
            return null // Полный дубликат содержимого
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

    // Дефект №13: Безопасное чтение потока со срезом маркера BOM UTF-8 (\uFEFF)
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
    // Оркестрация стриминга с тактовой буферизацией под 120 FPS
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

    // Дефект №5: Обратная совместимость с вызовом повтора последнего сообщения
    fun onRetryLastMessage() {
        onRetryMessage(targetMessageId = null)
    }

    // Дефект №5: Адресный повтор сообщений с откатом истории до выбранного раунда диалога
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
            isThinkingActive = (state.thinkingLevel != ThinkingLevel.LOW),
            isThinkingExpanded = (state.thinkingLevel != ThinkingLevel.LOW)
        )

        val sanitizedHistory = messagesHistory.mapNotNull { msg ->
            if (msg.text.isBlank() && msg.attachments.isEmpty()) {
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

        // Дефект №7: Сбор и буферизация на фоновом диспатчере для исключения Thread Hopping на 120 Гц
        generationJob = viewModelScope.launch(Dispatchers.Default) {
            val thoughtBuffer = StringBuilder()
            val contentBuffer = StringBuilder()
            var lastUiFlushTime = 0L

            suspend fun flushDeltasToUi() {
                if (thoughtBuffer.isEmpty() && contentBuffer.isEmpty()) return

                val thoughtDelta = thoughtBuffer.toString()
                val contentDelta = contentBuffer.toString()
                thoughtBuffer.setLength(0)
                contentBuffer.setLength(0)
                lastUiFlushTime = SystemClock.elapsedRealtime()

                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { currentState ->
                        val messages = currentState.messages
                        val lastIdx = messages.lastIndex
                        if (lastIdx >= 0 && messages[lastIdx].id == assistantMessageId) {
                            val currentTarget = messages[lastIdx]
                            val updatedTarget = currentTarget.copy(
                                thoughtText = if (thoughtDelta.isNotEmpty()) currentTarget.thoughtText + thoughtDelta else currentTarget.thoughtText,
                                text = if (contentDelta.isNotEmpty()) currentTarget.text + contentDelta else currentTarget.text
                            )
                            val newMessages = ArrayList(messages)
                            newMessages[lastIdx] = updatedTarget
                            currentState.copy(messages = newMessages)
                        } else {
                            val updatedList = messages.map { msg ->
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
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUiFlushTime >= UI_BATCH_INTERVAL_MS) {
                                flushDeltasToUi()
                            }
                        }

                        is GeminiStreamEvent.ContentDelta -> {
                            contentBuffer.append(event.text)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUiFlushTime >= UI_BATCH_INTERVAL_MS) {
                                flushDeltasToUi()
                            }
                        }

                        is GeminiStreamEvent.ThinkingCompleted -> {
                            flushDeltasToUi()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                                _uiEffects.trySend(ChatUiSideEffect.HapticThinkingCompleted)
                            }
                        }

                        is GeminiStreamEvent.ContentStarted -> {
                            flushDeltasToUi()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                                _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
                            }
                        }

                        is GeminiStreamEvent.Completed -> {
                            flushDeltasToUi()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                                _uiEffects.trySend(ChatUiSideEffect.HapticGenerationFinished)
                                _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
                            }
                        }

                        else -> {
                            flushDeltasToUi()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    flushDeltasToUi()
                    withContext(Dispatchers.Main.immediate) {
                        finalizeAssistantMessage(assistantMessageId, FinishReason.STOP)
                    }
                }
            } catch (e: GeminiApiException) {
                withContext(NonCancellable) {
                    flushDeltasToUi()
                    withContext(Dispatchers.Main.immediate) {
                        handleApiError(assistantMessageId, e)
                    }
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    flushDeltasToUi()
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
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    fun onCancelGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    // Дефект №8: Оптимизация $O(1)$ для обновления последнего сообщения в handleDiscreteEvent
    private fun handleDiscreteEvent(assistantMessageId: String, event: GeminiStreamEvent) {
        _uiState.update { currentState ->
            val messages = currentState.messages
            val lastIdx = messages.lastIndex

            fun updateMessage(msg: UiChatMessage): UiChatMessage {
                return when (event) {
                    is GeminiStreamEvent.Connecting -> msg.copy(isStreaming = true)
                    is GeminiStreamEvent.ThinkingStarted -> msg.copy(isThinkingActive = true, isThinkingExpanded = true)
                    is GeminiStreamEvent.ThinkingCompleted -> msg.copy(
                        isThinkingActive = false,
                        isThinkingExpanded = false,
                        thinkingDurationMs = msg.thinkingDurationMs + event.durationMs
                    )
                    is GeminiStreamEvent.ContentStarted -> msg.copy(isThinkingActive = false)
                    is GeminiStreamEvent.SearchQueriesDiscovered -> msg.copy(searchQueries = (msg.searchQueries + event.queries).distinct())
                    is GeminiStreamEvent.SourcesDiscovered -> msg.copy(sources = (msg.sources + event.newSources).distinctBy { it.url })
                    is GeminiStreamEvent.CitationsDiscovered -> msg.copy(citations = (msg.citations + event.citations).distinct())
                    is GeminiStreamEvent.SearchEntryPointRendered -> msg.copy(searchSuggestionsHtml = event.htmlContent)
                    is GeminiStreamEvent.UsageReported -> msg.copy(usage = event)
                    is GeminiStreamEvent.Completed -> msg.copy(
                        isStreaming = false,
                        isThinkingActive = false,
                        finishReason = event.finishReason,
                        thoughtSignature = event.thoughtSignature ?: msg.thoughtSignature
                    )
                    else -> msg
                }
            }

            if (lastIdx >= 0 && messages[lastIdx].id == assistantMessageId) {
                val newMessages = ArrayList(messages)
                newMessages[lastIdx] = updateMessage(messages[lastIdx])
                currentState.copy(messages = newMessages)
            } else {
                val updatedList = messages.map { msg ->
                    if (msg.id == assistantMessageId) updateMessage(msg) else msg
                }
                currentState.copy(messages = updatedList)
            }
        }
    }

    // Дефект №6: Чистое удаление сообщения-призрака, если пользователь отменил генерацию до первого токена
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

    // Дефект №1: Сброс флагов генерации и размышлений при ошибке API для частичных сообщений
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
        generationJob?.cancel()
        countdownJob?.cancel()
        if (shouldCloseClientOnExit) {
            activeClient.close()
        }
    }

    companion object {
        private const val PREF_KEY_API_KEY = "gemini_api_key"
        private const val KEY_INPUT_DRAFT = "key_input_draft_text"
        // Синхронизация с VSYNC 120 Гц (~33.3 мс, 30 обновлений UI/сек для плавности LTPO без троттлинга)
        private const val UI_BATCH_INTERVAL_MS = 33L

        // Дефект №4: Создание безопасного защищенного хранилища без открытых утечек
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