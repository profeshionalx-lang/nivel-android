package com.nivel.trainer.service.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Извлекает звуковую дорожку из импортированного видео (A10, #115) — **ремукс, не
 * перекодирование**: `MediaExtractor` читает исходные сжатые сэмплы, `MediaMuxer`
 * перекладывает их в отдельный `.m4a` с теми же PTS, не трогая кодек. Быстро (секунды
 * на часовое видео) и без потери качества — в отличие от полного декод/энкод цикла.
 *
 * Результат ложится в тот же каталог и с тем же именем, что и аудио-сайдкар записи
 * приложением ([VideoFileNaming.RECORDINGS_DIR], `session-<id>-<epochMs>.m4a`) —
 * дальше существующий `AudioUploadScheduler.enqueue` и конвейер заливки/транскрипции
 * работают с ним без единой правки: для них это обычный File-путь.
 *
 * Дорожка не AAC (редко, но бывает у скачанных видео — например, видео с MP3/PCM
 * звуком) → [Result.failure] с понятным сообщением, не крэш и не тихий провал:
 * `MediaMuxer` в MPEG_4-контейнере рассчитан на AAC, перекодирование — вне acceptance
 * этой задачи (issue «Не делаем»).
 *
 * Кооперативно отменяемо ([ensureActive] в цикле копирования сэмплов) — уход с экрана
 * посреди извлечения на большом файле останавливает работу, не долистывая впустую;
 * `finally` закрывает `MediaExtractor`/`MediaMuxer` и стирает недописанный файл.
 */
@Singleton
class AudioTrackExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * [uri] — `content://` импортированного видео (persistable-доступ уже должен быть
     * получен вызывающей стороной, см. `takePersistableUriPermission` в RecorderScreen).
     * [onProgress] — 0..100, зовётся по мере продвижения по временной шкале дорожки
     * (если исходный контейнер не сообщает длительность — прогресс не репортится,
     * работа всё равно доводится до конца).
     */
    suspend fun extract(
        uri: Uri,
        sessionId: String,
        onProgress: (percent: Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val outFile = outputFile(sessionId)
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            try {
                extractor.setDataSource(context, uri, null)

                val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("В этом видео нет звуковой дорожки")

                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                    error("Звук в этом видео не в формате AAC — такой формат пока не поддерживается")
                }
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                val bufferCapacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    DEFAULT_BUFFER_BYTES
                }

                extractor.selectTrack(trackIndex)

                val mx = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mx
                val outTrack = mx.addTrack(format)
                mx.start()

                val buffer = ByteBuffer.allocate(bufferCapacity)
                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    currentCoroutineContext().ensureActive() // уход с экрана отменяет job — не долистываем впустую
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    mx.writeSampleData(outTrack, buffer, bufferInfo)
                    if (durationUs > 0) {
                        onProgress(((bufferInfo.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100))
                    }
                    extractor.advance()
                }
                onProgress(100)
                outFile
            } catch (e: Throwable) {
                outFile.delete() // недописанный файл — не оставляем мусор ни при ошибке, ни при отмене
                throw e
            } finally {
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                runCatching { extractor.release() }
            }
        }
    }

    private fun outputFile(sessionId: String): File {
        val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(context.filesDir, VideoFileNaming.RECORDINGS_DIR).apply { mkdirs() }
        return File(dir, "session-$safeId-${System.currentTimeMillis()}.m4a")
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 256 * 1024
    }
}
