package com.nivel.trainer.service.upload

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/**
 * RequestBody файла, отдающий прогресс выгрузки (C4, #13). По мере записи байтов в
 * сокет дёргает [onProgress] с долей `0f..1f` — это питает прогресс-бар экрана
 * статусов (C5): «заливка X %». Без этого OkHttp льёт файл «вслепую» и UI не знает,
 * сколько уже ушло.
 *
 * Прогресс троттлится по шагу [MIN_STEP] (1 %), чтобы не спамить колбэком на каждый
 * 8-КБ сегмент при многомегабайтной записи.
 */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType,
    private val onProgress: (fraction: Float) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length().coerceAtLeast(1L)
        var uploaded = 0L
        var lastReported = -1f
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                uploaded += read
                val fraction = (uploaded.toFloat() / total).coerceIn(0f, 1f)
                if (fraction - lastReported >= MIN_STEP || fraction >= 1f) {
                    lastReported = fraction
                    onProgress(fraction)
                }
            }
        }
        // Гарантируем финальный 100 % (последний чанк мог не дотянуть до шага).
        if (lastReported < 1f) onProgress(1f)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MIN_STEP = 0.01f
    }
}
