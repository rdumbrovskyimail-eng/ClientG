package com.clientg.network

import android.net.TrafficStats
import android.os.SystemClock
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil

// ====================================================================
// 1. Доменные контракты (Domain Contracts)
// ====================================================================

enum class ThinkingLevel(val apiValue: String) {
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH")
}

enum class ChatRole(val apiValue: String) {
    USER("user"),
    MODEL("model")
}

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val attachments: List<TextAttachment> = emptyList(),
    val thoughtSignature: String? = null
)

data class TextAttachment(
    val fileName: String,
    val content: String,
    val extension: String = fileName.substringAfterLast('.', "txt").lowercase().trim()
) {
    init {
        require(content.isNotBlank()) {
            "Вложение '$fileName' не может быть пустым."
        }
        require(content.length <= MAX_ATTACHMENT_CHARS) {
            "Размер файла '$fileName' превышает лимит (${MAX_ATTACHMENT_CHARS / 1024} КБ)."
        }
    }

    companion object {
        const val MAX_ATTACHMENT_CHARS = 1_500_000
    }
}

data class GroundingSource(
    val title: String,
    val url: String
)

data class InlineCitation(
    val startIndex: Int,
    val endIndex: Int,
    val source: GroundingSource
)

enum class FinishReason {
    STOP,
    MAX_TOKENS,
    SAFETY,
    RECITATION,
    LANGUAGE,
    BLOCKLIST,
    PROHIBITED_CONTENT,
    UNKNOWN
}

sealed interface GeminiStreamEvent {
    data object Connecting : GeminiStreamEvent
    data object ThinkingStarted : GeminiStreamEvent
    data class ThinkingDelta(val text: String) : GeminiStreamEvent

    data class ThinkingCompleted(
        val durationMs: Long,
        val totalChars: Int
    ) : GeminiStreamEvent

    data object ContentStarted : GeminiStreamEvent
    data class ContentDelta(val text: String) : GeminiStreamEvent

    data class SearchQueriesDiscovered(val queries: List<String>) : GeminiStreamEvent
    data class SourcesDiscovered(val newSources: List<GroundingSource>) : GeminiStreamEvent
    data class CitationsDiscovered(val citations: List<InlineCitation>) : GeminiStreamEvent
    data class SearchEntryPointRendered(val htmlContent: String) : GeminiStreamEvent

    data class UsageReported(
        val promptTokens: Int,
        val candidateTokens: Int,
        val thoughtsTokens: Int,
        val cachedTokens: Int,
        val totalTokens: Int,
        val cacheHitPercentage: Float
    ) : GeminiStreamEvent

    data class Completed(
        val finishReason: FinishReason,
        val totalDurationMs: Long,
        val thoughtSignature: String? = null
    ) : GeminiStreamEvent
}

class GeminiApiException(
    val httpStatusCode: HttpStatusCode,
    val googleErrorCode: String,
    val userFriendlyMessage: String,
    val retryAfterSeconds: Long? = null
) : Exception("Gemini API Error [$httpStatusCode | $googleErrorCode]: $userFriendlyMessage")

// ====================================================================
// 2. Wire-DTO спецификации Google Generative Language REST v1beta
// ====================================================================

@Serializable
internal data class GeminiWireRequest(
    @SerialName("systemInstruction") val systemInstruction: SystemInstructionDto? = null,
    val contents: List<ContentDto>,
    @SerialName("generationConfig") val generationConfig: GenerationConfigDto,
    val tools: List<ToolDto>? = null,
    val safetySettings: List<SafetySettingDto>
)

@Serializable
internal data class SystemInstructionDto(
    val parts: List<PartDto>
)

@Serializable
internal data class ContentDto(
    val role: String,
    val parts: List<PartDto>
)

@Serializable
internal data class PartDto(
    val text: String? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null
)

@Serializable
internal data class ToolDto(
    @SerialName("googleSearch") val googleSearch: Map<String, String> = emptyMap()
)

@Serializable
internal data class GenerationConfigDto(
    val maxOutputTokens: Int = 65536,
    @SerialName("thinkingConfig") val thinkingConfig: ThinkingConfigDto
)

@Serializable
internal data class ThinkingConfigDto(
    val thinkingLevel: String,
    val includeThoughts: Boolean = true
)

@Serializable
internal data class SafetySettingDto(
    val category: String,
    val threshold: String
)

@Serializable
internal data class GeminiResponseChunk(
    val candidates: List<CandidateDto>? = null,
    val promptFeedback: PromptFeedbackDto? = null,
    val usageMetadata: UsageMetadataDto? = null,
    val error: GoogleApiErrorDto? = null
)

@Serializable
internal data class PromptFeedbackDto(
    val blockReason: String? = null,
    val blockReasonMessage: String? = null,
    val safetyRatings: List<SafetyRatingDto>? = null
)

@Serializable
internal data class SafetyRatingDto(
    val category: String? = null,
    val probability: String? = null
)

@Serializable
internal data class CandidateDto(
    val content: ContentResponseDto? = null,
    val finishReason: String? = null,
    val groundingMetadata: GroundingMetadataDto? = null
)

@Serializable
internal data class ContentResponseDto(
    val parts: List<PartResponseDto>? = null,
    val role: String? = null
)

@Serializable
internal data class PartResponseDto(
    val text: String? = null,
    val thought: Boolean? = false,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null
)

@Serializable
internal data class GroundingMetadataDto(
    val webSearchQueries: List<String>? = null,
    val groundingChunks: List<GroundingChunkDto>? = null,
    val groundingSupports: List<GroundingSupportDto>? = null,
    val searchEntryPoint: SearchEntryPointDto? = null
)

@Serializable
internal data class SearchEntryPointDto(
    val renderedContent: String? = null
)

@Serializable
internal data class GroundingChunkDto(
    val web: WebDto? = null
)

@Serializable
internal data class WebDto(
    val uri: String? = null,
    val title: String? = null
)

@Serializable
internal data class GroundingSupportDto(
    val segment: SegmentDto? = null,
    val groundingChunkIndices: List<Int>? = null
)

@Serializable
internal data class SegmentDto(
    val startIndex: Int = 0,
    val endIndex: Int = 0
)

@Serializable
internal data class UsageMetadataDto(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val thoughtsTokenCount: Int = 0,
    val cachedContentTokenCount: Int = 0,
    val totalTokenCount: Int = 0
)

@Serializable
internal data class GoogleApiErrorContainer(
    val error: GoogleApiErrorDto
)

@Serializable
internal data class GoogleApiErrorDto(
    val code: Int = 0,
    val message: String = "",
    val status: String = "",
    val details: List<JsonElement>? = null
)

// ====================================================================
// 3. ThreadContextElement для безопасной телеметрии TrafficStats
// ====================================================================

private class TrafficStatsElement(private val tag: Int) : ThreadContextElement<Int> {
    companion object Key : CoroutineContext.Key<TrafficStatsElement>
    override val key: CoroutineContext.Key<*> = Key

    override fun updateThreadContext(context: CoroutineContext): Int {
        val oldTag = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(tag)
        return oldTag
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int) {
        if (oldState > 0) {
            TrafficStats.setThreadStatsTag(oldState)
        } else {
            TrafficStats.clearThreadStatsTag()
        }
    }
}

// ====================================================================
// 4. Сетевое ядро: GeminiClient
// ====================================================================

class GeminiClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val shouldCloseHttpClient: Boolean = true
) : Closeable {

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val DEFAULT_MODEL_NAME = "gemini-3.8-flash"
        private const val ANDROID_NET_TAG = 0x0000FA01

        const val IMMUTABLE_SYSTEM_INSTRUCTION =
            "Ты — интеллектуальный ассистент ClientG на базе Gemini 3.8 Flash. " +
            "Твоя задача — давать логически выверенные, фактологически точные ответы. " +
            "Используй чистый Markdown, оформляй код в блоки с указанием языка синтаксиса. " +
            "При включенном поиске опирайся на свежие авторитетные источники."

        fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
            engine {
                maxConnectionsCount = 32
                endpoint {
                    maxConnectionsPerRoute = 8
                    pipelineMaxSize = 1
                    keepAliveTime = 30_000
                    connectTimeout = 15_000
                }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 180_000
                connectTimeoutMillis = 15_000
            }
        }

        private fun escapeXmlAttribute(value: String): String {
            val sb = StringBuilder(value.length + 16)
            for (i in 0 until value.length) {
                when (val c = value[i]) {
                    '&' -> sb.append("&amp;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&apos;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    in '\u0000'..'\u001F' -> {
                        if (c == '\t' || c == '\n' || c == '\r') {
                            sb.append(c)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun escapeXmlText(value: String): String {
            val sb = StringBuilder(value.length + 32)
            for (i in 0 until value.length) {
                when (val c = value[i]) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    in '\u0000'..'\u001F' -> {
                        if (c == '\t' || c == '\n' || c == '\r') {
                            sb.append(c)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun normalizeUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            return runCatching {
                val uri = URI(trimmed)
                val scheme = (uri.scheme ?: "https").lowercase(Locale.US)
                val host = (uri.host ?: "").lowercase(Locale.US)
                val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) {
                    ""
                } else {
                    ":${uri.port}"
                }
                var path = uri.rawPath ?: ""
                if (path.endsWith('/') && path.length > 1) {
                    path = path.dropLast(1)
                }
                val query = uri.rawQuery?.split('&')?.filterNot {
                    it.startsWith("utm_", ignoreCase = true) || it.startsWith("gclid", ignoreCase = true)
                }?.joinToString("&")?.takeIf { it.isNotEmpty() }

                val queryPart = if (query != null) "?$query" else ""
                "$scheme://$host$port$path$queryPart"
            }.getOrDefault(trimmed.trimEnd('/'))
        }

        private fun utf8ByteOffsetToCharIndex(text: String, byteOffset: Int): Int {
            if (byteOffset <= 0) return 0
            var currentBytes = 0
            var charIndex = 0
            val length = text.length
            while (charIndex < length && currentBytes < byteOffset) {
                val codePoint = text.codePointAt(charIndex)
                val bytesForCodePoint = when {
                    codePoint <= 0x7F -> 1
                    codePoint <= 0x7FF -> 2
                    codePoint <= 0xFFFF -> 3
                    else -> 4
                }
                if (currentBytes + bytesForCodePoint > byteOffset) {
                    break
                }
                currentBytes += bytesForCodePoint
                charIndex += Character.charCount(codePoint)
            }
            return charIndex.coerceIn(0, length)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun streamContent(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        attachments: List<TextAttachment> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.HIGH,
        enableSearch: Boolean = true,
        modelName: String = DEFAULT_MODEL_NAME,
        systemInstruction: String = IMMUTABLE_SYSTEM_INSTRUCTION
    ): Flow<GeminiStreamEvent> = flow {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            throw GeminiApiException(
                httpStatusCode = HttpStatusCode.Unauthorized,
                googleErrorCode = "API_KEY_EMPTY",
                userFriendlyMessage = "API-ключ Gemini не задан. Введите действующий ключ в настройках."
            )
        }

        emit(GeminiStreamEvent.Connecting)
        val startTime = SystemClock.elapsedRealtime()
        val endpointUrl = "$BASE_URL/$modelName:streamGenerateContent?alt=sse"

        // 1. Формирование истории диалога
        val rawTurns = ArrayList<ContentDto>(history.size + 1)
        for (msg in history) {
            val historyParts = buildList {
                msg.attachments.forEach { att ->
                    val safeName = escapeXmlAttribute(att.fileName)
                    val safeContent = escapeXmlText(att.content)
                    add(PartDto(text = "<attachment name=\"$safeName\">\n$safeContent\n</attachment>"))
                }
                if (msg.text.isNotBlank()) {
                    add(PartDto(text = msg.text, thoughtSignature = msg.thoughtSignature))
                }
            }
            if (historyParts.isNotEmpty()) {
                rawTurns.add(ContentDto(role = msg.role.apiValue, parts = historyParts))
            }
        }

        // 2. Формирование текущего хода
        val currentParts = buildList {
            attachments.forEach { att ->
                val safeName = escapeXmlAttribute(att.fileName)
                val safeContent = escapeXmlText(att.content)
                add(PartDto(text = "<attachment name=\"$safeName\">\n$safeContent\n</attachment>"))
            }
            if (prompt.isNotBlank()) {
                add(PartDto(text = prompt))
            }
        }

        if (currentParts.isEmpty()) {
            throw GeminiApiException(
                httpStatusCode = HttpStatusCode.BadRequest,
                googleErrorCode = "EMPTY_PROMPT",
                userFriendlyMessage = "Запрос не может быть пустым (введите текст или прикрепите файл)."
            )
        }
        rawTurns.add(ContentDto(role = ChatRole.USER.apiValue, parts = currentParts))

        // 3. Отсечение ведущих сообщений модели
        while (rawTurns.isNotEmpty() && rawTurns.first().role != ChatRole.USER.apiValue) {
            rawTurns.removeAt(0)
        }

        // 4. Склеивание сообщений с одинаковой ролью
        val sanitizedContents = ArrayList<ContentDto>(rawTurns.size)
        for (turn in rawTurns) {
            val last = sanitizedContents.lastOrNull()
            if (last != null && last.role == turn.role) {
                sanitizedContents[sanitizedContents.lastIndex] = ContentDto(
                    role = last.role,
                    parts = last.parts + turn.parts
                )
            } else {
                sanitizedContents.add(turn)
            }
        }

        // 5. Системная инструкция
        val systemDto = if (systemInstruction.isNotBlank()) {
            SystemInstructionDto(listOf(PartDto(text = systemInstruction)))
        } else {
            null
        }

        val requestBody = GeminiWireRequest(
            systemInstruction = systemDto,
            contents = sanitizedContents,
            generationConfig = GenerationConfigDto(
                maxOutputTokens = 65536,
                thinkingConfig = ThinkingConfigDto(
                    thinkingLevel = thinkingLevel.apiValue,
                    includeThoughts = true
                )
            ),
            tools = if (enableSearch) listOf(ToolDto()) else null,
            safetySettings = listOf(
                SafetySettingDto("HARM_CATEGORY_HARASSMENT", "BLOCK_ONLY_HIGH"),
                SafetySettingDto("HARM_CATEGORY_HATE_SPEECH", "BLOCK_ONLY_HIGH"),
                SafetySettingDto("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_ONLY_HIGH"),
                SafetySettingDto("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_ONLY_HIGH")
            )
        )

        val serializedJson = json.encodeToString(GeminiWireRequest.serializer(), requestBody)

        var isThinkingPhase = false
        var hasContentStarted = false
        var currentThinkingStartTime = 0L
        var totalThoughtChars = 0

        val discoveredQueries = mutableSetOf<String>()
        val discoveredSourceUrls = mutableSetOf<String>()
        val discoveredCitations = mutableSetOf<InlineCitation>()
        val accumulatedGroundingChunks = mutableListOf<GroundingChunkDto>()
        val accumulatedContentText = StringBuilder()
        var emittedSearchEntryPoint = false
        var finalFinishReason = FinishReason.UNKNOWN
        var latestThoughtSignature: String? = null
        var hasReceivedTerminalFinishReason = false
        var isStreamGracefullyClosed = false

        // 6. Сетевой вызов
        try {
            httpClient.preparePost(endpointUrl) {
                header("x-goog-api-key", apiKey)
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.CacheControl, "no-cache")
                contentType(ContentType.Application.Json)
                setBody(serializedJson)
            }.execute { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    val errorBody = httpResponse.bodyAsText()
                    val parsedError = runCatching {
                        json.decodeFromString(GoogleApiErrorContainer.serializer(), errorBody).error
                    }.getOrNull()

                    val headerRetrySeconds = parseRetryAfter(httpResponse.headers[HttpHeaders.RetryAfter])
                    val bodyRetrySeconds = extractRetryDelaySeconds(parsedError)
                    val retrySeconds = headerRetrySeconds ?: bodyRetrySeconds

                    val rawFallback = errorBody.take(300).trim()
                    val message = when (httpResponse.status) {
                        HttpStatusCode.TooManyRequests -> {
                            if (retrySeconds != null) {
                                "Превышен лимит запросов к Gemini API. Повторите через $retrySeconds сек."
                            } else {
                                "Превышен лимит запросов (429 Quota Exceeded). Подождите несколько секунд."
                            }
                        }
                        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                            "Неверный API-ключ Gemini или доступ запрещен."
                        HttpStatusCode.BadRequest ->
                            parsedError?.message?.takeIf { it.isNotBlank() }
                                ?: rawFallback.ifBlank { "Некорректные параметры запроса." }
                        else ->
                            parsedError?.message?.takeIf { it.isNotBlank() }
                                ?: rawFallback.ifBlank { "Ошибка сервера Google (${httpResponse.status.value})." }
                    }

                    throw GeminiApiException(
                        httpStatusCode = httpResponse.status,
                        googleErrorCode = parsedError?.status ?: "HTTP_${httpResponse.status.value}",
                        userFriendlyMessage = message,
                        retryAfterSeconds = retrySeconds
                    )
                }

                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                val sseEventDataBuffer = StringBuilder()

                // 7. Обработка неделимого W3C JSON-пакета
                suspend fun processCompleteSsePayload(dataPayload: String) {
                    if (dataPayload.isEmpty()) return
                    if (dataPayload == "[DONE]") {
                        isStreamGracefullyClosed = true
                        return
                    }

                    val chunk = try {
                        json.decodeFromString(GeminiResponseChunk.serializer(), dataPayload)
                    } catch (_: SerializationException) {
                        return
                    } catch (_: IllegalArgumentException) {
                        return
                    }

                    chunk.error?.let { sseError ->
                        val retrySec = extractRetryDelaySeconds(sseError)
                        val statusCode = runCatching {
                            HttpStatusCode.fromValue(if (sseError.code in 100..599) sseError.code else 500)
                        }.getOrDefault(HttpStatusCode.InternalServerError)

                        throw GeminiApiException(
                            httpStatusCode = statusCode,
                            googleErrorCode = sseError.status,
                            userFriendlyMessage = sseError.message,
                            retryAfterSeconds = retrySec
                        )
                    }

                    chunk.promptFeedback?.let { feedback ->
                        if (!feedback.blockReason.isNullOrBlank()) {
                            throw GeminiApiException(
                                httpStatusCode = HttpStatusCode.BadRequest,
                                googleErrorCode = "PROMPT_BLOCKED_${feedback.blockReason}",
                                userFriendlyMessage = feedback.blockReasonMessage
                                    ?: "Запрос отклонен политикой безопасности (${feedback.blockReason})."
                            )
                        }
                    }

                    val candidate = chunk.candidates?.firstOrNull()

                    candidate?.finishReason?.let { reason ->
                        hasReceivedTerminalFinishReason = true
                        isStreamGracefullyClosed = true
                        finalFinishReason = when (reason) {
                            "STOP" -> FinishReason.STOP
                            "MAX_TOKENS" -> FinishReason.MAX_TOKENS
                            "SAFETY" -> FinishReason.SAFETY
                            "RECITATION" -> FinishReason.RECITATION
                            "LANGUAGE" -> FinishReason.LANGUAGE
                            "BLOCKLIST" -> FinishReason.BLOCKLIST
                            "PROHIBITED_CONTENT" -> FinishReason.PROHIBITED_CONTENT
                            else -> FinishReason.UNKNOWN
                        }
                    }

                    // НЮАНС 1: Парсинг текстовых фрагментов выполняется ДО groundingMetadata,
                    // чтобы accumulatedContentText содержал текст текущего чанка при расчете смещений
                    candidate?.content?.parts?.forEach { part ->
                        part.thoughtSignature?.let { signature ->
                            if (signature.isNotBlank()) {
                                latestThoughtSignature = signature
                            }
                        }

                        val text = part.text ?: return@forEach
                        if (text.isEmpty()) return@forEach

                        if (part.thought == true) {
                            if (!isThinkingPhase) {
                                isThinkingPhase = true
                                currentThinkingStartTime = SystemClock.elapsedRealtime()
                                emit(GeminiStreamEvent.ThinkingStarted)
                            }
                            totalThoughtChars += text.length
                            emit(GeminiStreamEvent.ThinkingDelta(text))
                        } else {
                            if (isThinkingPhase) {
                                isThinkingPhase = false
                                val duration = (SystemClock.elapsedRealtime() - currentThinkingStartTime).coerceAtLeast(0L)
                                emit(GeminiStreamEvent.ThinkingCompleted(duration, totalThoughtChars))
                            }
                            if (!hasContentStarted) {
                                hasContentStarted = true
                                emit(GeminiStreamEvent.ContentStarted)
                            }
                            accumulatedContentText.append(text)
                            emit(GeminiStreamEvent.ContentDelta(text))
                        }
                    }

                    candidate?.groundingMetadata?.let { meta ->
                        meta.webSearchQueries?.let { queries ->
                            val newQueries = queries.filter { discoveredQueries.add(it) }
                            if (newQueries.isNotEmpty()) {
                                emit(GeminiStreamEvent.SearchQueriesDiscovered(newQueries))
                            }
                        }

                        val currentChunkList = meta.groundingChunks ?: emptyList()
                        if (currentChunkList.isNotEmpty()) {
                            if (currentChunkList.size > accumulatedGroundingChunks.size) {
                                accumulatedGroundingChunks.clear()
                                accumulatedGroundingChunks.addAll(currentChunkList)
                            } else if (!accumulatedGroundingChunks.containsAll(currentChunkList)) {
                                accumulatedGroundingChunks.addAll(currentChunkList)
                            }

                            val newSourcesBatch = mutableListOf<GroundingSource>()
                            currentChunkList.forEach { chunkItem ->
                                val web = chunkItem.web ?: return@forEach
                                val uri = web.uri ?: return@forEach
                                val normalized = normalizeUrl(uri)
                                if (discoveredSourceUrls.add(normalized)) {
                                    newSourcesBatch.add(
                                        GroundingSource(
                                            title = web.title?.takeIf { it.isNotBlank() } ?: uri,
                                            url = uri
                                        )
                                    )
                                }
                            }
                            if (newSourcesBatch.isNotEmpty()) {
                                emit(GeminiStreamEvent.SourcesDiscovered(newSourcesBatch))
                            }
                        }

                        meta.groundingSupports?.let { supports ->
                            val chunkPool = accumulatedGroundingChunks
                            if (chunkPool.isNotEmpty()) {
                                val currentTextSnapshot = accumulatedContentText.toString()
                                val citations = supports.flatMap { sup ->
                                    val seg = sup.segment ?: return@flatMap emptyList<InlineCitation>()
                                    val indices = sup.groundingChunkIndices ?: return@flatMap emptyList<InlineCitation>()

                                    val startCharIndex = utf8ByteOffsetToCharIndex(currentTextSnapshot, seg.startIndex)
                                    val endCharIndex = utf8ByteOffsetToCharIndex(currentTextSnapshot, seg.endIndex)

                                    indices.mapNotNull { srcIndex ->
                                        val chunkItem = chunkPool.getOrNull(srcIndex) ?: return@mapNotNull null
                                        val web = chunkItem.web ?: return@mapNotNull null
                                        val uri = web.uri ?: return@mapNotNull null

                                        InlineCitation(
                                            startIndex = startCharIndex,
                                            endIndex = endCharIndex,
                                            source = GroundingSource(
                                                title = web.title?.takeIf { it.isNotBlank() } ?: uri,
                                                url = uri
                                            )
                                        )
                                    }
                                }
                                val uniqueCitations = citations.filter { discoveredCitations.add(it) }
                                if (uniqueCitations.isNotEmpty()) {
                                    emit(GeminiStreamEvent.CitationsDiscovered(uniqueCitations))
                                }
                            }
                        }

                        if (!emittedSearchEntryPoint) {
                            meta.searchEntryPoint?.renderedContent?.let { htmlSnippet ->
                                if (htmlSnippet.isNotBlank()) {
                                    emit(GeminiStreamEvent.SearchEntryPointRendered(htmlSnippet))
                                    emittedSearchEntryPoint = true
                                }
                            }
                        }
                    }

                    chunk.usageMetadata?.let { usage ->
                        val cached = usage.cachedContentTokenCount
                        val promptTotal = usage.promptTokenCount
                        val hitRate = if (promptTotal > 0) (cached.toFloat() / promptTotal.toFloat()) * 100f else 0f

                        emit(
                            GeminiStreamEvent.UsageReported(
                                promptTokens = promptTotal,
                                candidateTokens = usage.candidatesTokenCount,
                                thoughtsTokens = usage.thoughtsTokenCount,
                                cachedTokens = cached,
                                totalTokens = usage.totalTokenCount,
                                cacheHitPercentage = hitRate
                            )
                        )
                    }
                }

                // 8. Разбор потока W3C SSE
                while (!channel.isClosedForRead) {
                    currentCoroutineContext().ensureActive()

                    val rawLine = channel.readUTF8Line() ?: break
                    val line = rawLine.trim()

                    if (line.isEmpty()) {
                        if (sseEventDataBuffer.isNotEmpty()) {
                            val completePayload = sseEventDataBuffer.toString()
                            sseEventDataBuffer.setLength(0)
                            processCompleteSsePayload(completePayload)
                        }
                        continue
                    }

                    if (line.startsWith("data:")) {
                        val dataPart = line.removePrefix("data:").trimStart()
                        if (sseEventDataBuffer.isNotEmpty()) {
                            sseEventDataBuffer.append("\n")
                        }
                        sseEventDataBuffer.append(dataPart)
                    }
                }

                if (sseEventDataBuffer.isNotEmpty()) {
                    val remainingPayload = sseEventDataBuffer.toString()
                    sseEventDataBuffer.setLength(0)
                    processCompleteSsePayload(remainingPayload)
                }

                if (!isStreamGracefullyClosed && !hasReceivedTerminalFinishReason) {
                    throw GeminiApiException(
                        httpStatusCode = HttpStatusCode.GatewayTimeout,
                        googleErrorCode = "PREMATURE_STREAM_DISCONNECT",
                        userFriendlyMessage = "Сетевое соединение прервалось до завершения ответа. Повторите запрос."
                    )
                }

                if (isThinkingPhase) {
                    val duration = (SystemClock.elapsedRealtime() - currentThinkingStartTime).coerceAtLeast(0L)
                    emit(GeminiStreamEvent.ThinkingCompleted(duration, totalThoughtChars))
                }

                val totalDuration = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L)
                emit(GeminiStreamEvent.Completed(finalFinishReason, totalDuration, latestThoughtSignature))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw GeminiApiException(
                httpStatusCode = HttpStatusCode.RequestTimeout,
                googleErrorCode = "REQUEST_TIMEOUT",
                userFriendlyMessage = "Время ожидания ответа от Gemini истекло. Повторите запрос."
            )
        } catch (e: IOException) {
            throw GeminiApiException(
                httpStatusCode = HttpStatusCode.GatewayTimeout,
                googleErrorCode = "NETWORK_UNAVAILABLE",
                userFriendlyMessage = "Сетевая ошибка: проверьте интернет-соединение на устройстве."
            )
        }
    }.flowOn(Dispatchers.IO + TrafficStatsElement(ANDROID_NET_TAG))

    private fun parseRetryAfter(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        headerValue.toLongOrNull()?.let { return it }

        return runCatching {
            val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
            val instant = formatter.parse(headerValue, Instant::from)
            val seconds = Duration.between(Instant.now(), instant).seconds
            seconds.coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun extractRetryDelaySeconds(errorDto: GoogleApiErrorDto?): Long? {
        if (errorDto?.details == null) return null
        for (element in errorDto.details) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            val type = obj["@type"]?.jsonPrimitive?.content ?: ""
            if (type.contains("RetryInfo")) {
                val delayStr = obj["retryDelay"]?.jsonPrimitive?.content ?: continue
                val secondsDouble = delayStr.removeSuffix("s").trim().toDoubleOrNull()
                if (secondsDouble != null) {
                    return ceil(secondsDouble).toLong()
                }
            }
        }
        return null
    }

    override fun close() {
        if (shouldCloseHttpClient) {
            httpClient.close()
        }
    }
}