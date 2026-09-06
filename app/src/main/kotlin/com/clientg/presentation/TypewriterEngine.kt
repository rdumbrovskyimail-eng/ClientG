package com.clientg.presentation

import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Высокоточный кадровый движок интерполяции потока токенов (Typewriter Engine).
 * 
 * Преобразует дискретные сетевые куски TCP/SSE в непрерывную, визуально гладкую
 * струю текста слева направо (аналогично Google AI Studio).
 * 
 * Адаптирован под дисплеи с любой частотой обновления (60 Гц, 90 Гц, 120 Гц).
 */
class TypewriterEngine(
    private val onFrame: (thoughtDelta: String, contentDelta: String) -> Unit,
    private val onComplete: () -> Unit = {}
) {
    // Входящие сетевые буферы (целевой текст, пришедший от Gemini)
    private val targetThought = StringBuilder()
    private val targetContent = StringBuilder()

    // Индексы уже выведенных на экран символов
    private var renderedThoughtLength = 0
    private var renderedContentLength = 0

    // Флаги управления потоком
    @Volatile
    private var isStreamEnded = false

    @Volatile
    private var isThinkingPhaseEnded = false

    private var animationJob: Job? = null

    /**
     * Помещение пришедшего куска рассуждений (thought) в очередь интерполятора.
     */
    @Synchronized
    fun enqueueThought(delta: String) {
        if (delta.isEmpty()) return
        targetThought.append(delta)
    }

    /**
     * Помещение пришедшего куска полезного ответа (content) в очередь интерполятора.
     */
    @Synchronized
    fun enqueueContent(delta: String) {
        if (delta.isEmpty()) return
        targetContent.append(delta)
    }

    /**
     * Сигнал о завершении фазы рассуждений модели.
     */
    fun markThinkingEnded() {
        isThinkingPhaseEnded = true
    }

    /**
     * Сигнал о получении финального SSE-чанка от сервера.
     */
    fun markStreamEnded() {
        isThinkingPhaseEnded = true
        isStreamEnded = true
    }

    /**
     * Запуск кадрового цикла рендеринга на главном потоке (Dispatchers.Main.immediate).
     */
    fun start(scope: CoroutineScope) {
        if (animationJob?.isActive == true) return

        animationJob = scope.launch(Dispatchers.Main.immediate) {
            var lastFrameTimeNanos = SystemClock.elapsedRealtimeNanos()

            while (isActive) {
                // 1. Ожидание аппаратного такта VSYNC текущего экрана (60 / 90 / 120 Гц)
                val frameTimeNanos = awaitDisplayFrame()
                val deltaNanos = (frameTimeNanos - lastFrameTimeNanos).coerceAtLeast(1_000_000L)
                lastFrameTimeNanos = frameTimeNanos

                // Коэффициент времени относительно базовых 60 Гц (16.6 мс)
                val deltaRatio = deltaNanos / 16_666_666f

                var thoughtSlice = ""
                var contentSlice = ""

                // 2. Снятие квантов текста под синхронизацией
                synchronized(this@TypewriterEngine) {
                    val thoughtQueue = targetThought.length - renderedThoughtLength
                    val contentQueue = targetContent.length - renderedContentLength

                    // Фаза вывода мыслей
                    if (thoughtQueue > 0) {
                        // Адаптивная скорость: если очередь растет, скорость плавно разгоняется
                        val speedFactor = calculateAdaptiveSpeed(thoughtQueue, isThinkingPhaseEnded)
                        val requestedChars = max(1, ceil(speedFactor * deltaRatio).toInt())
                        val safeLen = computeSafeSliceLength(
                            text = targetThought,
                            currentLength = renderedThoughtLength,
                            requestedChars = requestedChars
                        )
                        if (safeLen > 0) {
                            thoughtSlice = targetThought.substring(renderedThoughtLength, renderedThoughtLength + safeLen)
                            renderedThoughtLength += safeLen
                        }
                    }

                    // Фаза вывода полезного контента
                    if (contentQueue > 0) {
                        val speedFactor = calculateAdaptiveSpeed(contentQueue, isStreamEnded)
                        val requestedChars = max(1, ceil(speedFactor * deltaRatio).toInt())
                        val safeLen = computeSafeSliceLength(
                            text = targetContent,
                            currentLength = renderedContentLength,
                            requestedChars = requestedChars
                        )
                        if (safeLen > 0) {
                            contentSlice = targetContent.substring(renderedContentLength, renderedContentLength + safeLen)
                            renderedContentLength += safeLen
                        }
                    }

                    // 3. Проверка на полное завершение всей очереди после окончания сети
                    if (isStreamEnded && thoughtQueue == 0 && contentQueue == 0) {
                        if (thoughtSlice.isNotEmpty() || contentSlice.isNotEmpty()) {
                            onFrame(thoughtSlice, contentSlice)
                        }
                        onComplete()
                        return@launch
                    }
                }

                // 4. Отправка кадров в UI-поток
                if (thoughtSlice.isNotEmpty() || contentSlice.isNotEmpty()) {
                    onFrame(thoughtSlice, contentSlice)
                }
            }
        }
    }

    /**
     * Остановка движка и немедленный сброс остатка очереди (при нажатии пользователем кнопки "Стоп").
     */
    fun stopAndFlush() {
        animationJob?.cancel()
        animationJob = null

        synchronized(this) {
            var thoughtRemaining = ""
            var contentRemaining = ""

            if (renderedThoughtLength < targetThought.length) {
                thoughtRemaining = targetThought.substring(renderedThoughtLength)
                renderedThoughtLength = targetThought.length
            }
            if (renderedContentLength < targetContent.length) {
                contentRemaining = targetContent.substring(renderedContentLength)
                renderedContentLength = targetContent.length
            }

            if (thoughtRemaining.isNotEmpty() || contentRemaining.isNotEmpty()) {
                onFrame(thoughtRemaining, contentRemaining)
            }
        }
        onComplete()
    }

    /**
     * Расчет адаптивного ускорения (P-Controller):
     * Позволяет тексту не отставать от быстро отвечающей модели на длинном коде.
     */
    private fun calculateAdaptiveSpeed(backlog: Int, isFlushing: Boolean): Float {
        if (isFlushing) {
            // Если сеть уже всё прислала, сбрасываем остаток за 6-10 кадров
            return max(3f, backlog / 6f)
        }
        return when {
            backlog <= 4 -> 1.2f   // Плавное посимвольное чтение
            backlog <= 15 -> 2.5f  // Обычный диалоговый темп
            backlog <= 60 -> 6.0f  // Модель выдает быструю мысль
            else -> backlog / 10f  // Модель выдала огромный кусок кода на 200 строк
        }
    }

    /**
     * Гарантия безопасности кодировки Unicode (UTF-16):
     * Запрещает разрез строки между верхним и нижним суррогатом (эмодзи и спецсимволы).
     */
    private fun computeSafeSliceLength(
        text: CharSequence,
        currentLength: Int,
        requestedChars: Int
    ): Int {
        val targetLength = min(text.length, currentLength + requestedChars)
        if (targetLength >= text.length) return text.length - currentLength

        var safeEnd = targetLength
        if (Character.isHighSurrogate(text[safeEnd - 1])) {
            if (safeEnd < text.length && Character.isLowSurrogate(text[safeEnd])) {
                safeEnd++ // Забираем пару целиком
            } else {
                safeEnd-- // Откатываемся до границы пары
            }
        }
        return max(0, safeEnd - currentLength)
    }

    /**
     * Безопасное ожидание VSYNC экрана с защитой от двойного resume при отмене.
     */
    private suspend fun awaitDisplayFrame(): Long {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            delay(16)
            return SystemClock.elapsedRealtimeNanos()
        }

        return suspendCancellableCoroutine { continuation ->
            val callback = Choreographer.FrameCallback { frameTimeNanos ->
                if (continuation.isActive) {
                    continuation.resume(frameTimeNanos)
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
            continuation.invokeOnCancellation {
                Choreographer.getInstance().removeFrameCallback(callback)
            }
        }
    }
}