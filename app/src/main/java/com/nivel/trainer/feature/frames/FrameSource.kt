package com.nivel.trainer.feature.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Один кадр плёнки-превью — время в видео (мс) + уже уменьшенный bitmap, готовый к рисованию. */
data class FilmstripFrame(val videoTimeMs: Long, val bitmap: Bitmap)

/**
 * Кадр из предварительного разбора окна (issue #117) — время в видео (мс) + сжатый JPEG,
 * а не сырой [Bitmap]. Окно даёт ~50-100 таких кадров (шаг [MediaMetadataFrameSource]'а —
 * 100мс) — сырыми `Bitmap` (даже в `RGB_565`) это десятки мегабайт, JPEG держит тот же
 * кэш в единицах мегабайт ценой decode на ~1мс при показе (см. [decode]).
 */
class CachedScrubFrame(val timeMs: Long, private val jpeg: ByteArray) {
    fun decode(): Bitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
}

/**
 * Откуда брать байты видео (A10, #115) — записанное приложением видео лежит File-путём
 * в `filesDir`, импортированное из галереи — `content://` (SAF, доступ пережил
 * перезапуск через `takePersistableUriPermission`). [MediaMetadataRetriever] умеет
 * оба варианта, но через разные перегрузки `setDataSource` — эта абстракция прячет
 * разницу от [FrameScrubberViewModel][com.nivel.trainer.feature.frames.FrameScrubberViewModel].
 */
sealed interface FrameVideoSource {
    data class LocalPath(val path: String) : FrameVideoSource
    data class ContentUri(val uri: Uri) : FrameVideoSource
}

/**
 * Источник кадров видео (A6, #100; переработан в #117). Единственная реализация —
 * [MediaMetadataFrameSource]. Все методы — suspend/IO: и `MediaMetadataRetriever`,
 * и `MediaCodec`/`MediaExtractor` синхронны и блокирующие.
 */
interface FrameSource {
    /** Открывает видеофайл. false — файл битый/недоступен (например, удалён вручную). */
    suspend fun prepare(): Boolean

    /**
     * Разбирает окно `[startMs, endMs]` ОДНИМ линейным проходом декодера с шагом [stepMs] —
     * без единой независимой перемотки внутри окна. [onFrame] зовётся по мере готовности
     * каждого кадра — экран строит плёнку и получает мгновенный плейсхолдер для скраба
     * прогрессивно, не дожидаясь конца прохода. Кооперативно отменяемо (см. класс-докблок
     * [FrameScrubberViewModel]) — цикл декодера ограничен таймаутом на итерацию, поэтому
     * отмена не залипает на блокирующем нативном вызове.
     *
     * Почему не N вызовов `getFrameAtTime` (было до #117): дорога не распаковка кадра,
     * а именно ПЕРЕМОТКА — каждый независимый вызов ищет опорный кадр и заново
     * инициализирует сессию декодера. Линейный проход этого не делает вообще, поэтому
     * окно в 10с разбирается за секунду-две, а не за N отдельных операций.
     */
    suspend fun scanWindow(
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        targetWidthPx: Int,
        onFrame: (CachedScrubFrame) -> Unit,
    ): List<CachedScrubFrame>

    /**
     * Точный кадр на [timeMs] — `getFrameAtTime(OPTION_CLOSEST)` на отдельном инстансе
     * `MediaMetadataRetriever`, не связанном с [scanWindow] (issue #117: раньше плёнка и
     * точный кадр делили один ретривер под общим локом — отсюда очередь и вечный спиннер).
     * Используется и для крупного превью после отпускания слайдера, и для итогового JPEG —
     * один и тот же вызов, чтобы превью гарантированно совпадало с тем, что уедет в файл
     * (acceptance issue #100).
     */
    suspend fun exactFrameAt(timeMs: Long): Bitmap?

    /** Освобождает нативные ресурсы. Идемпотентно, безопасно звать повторно. */
    fun release()
}

/**
 * Реализация [FrameSource] (A6/#100, линейный разбор — #117).
 *
 * - [scanWindow] — `MediaExtractor` + `MediaCodec` decode-to-`Surface`, кадры читаются из
 *   `ImageReader` в формате `YUV_420_888` (единственный формат, который MediaCodec гарантированно
 *   умеет рендерить в `ImageReader` без GPU/`SurfaceTexture` — `RGBA_8888` для видео-декодера
 *   не гарантирован на уровне платформы). Кадр репакуется в NV21 и сразу кодируется в JPEG
 *   через `YuvImage` (никакой ручной YUV→RGB математики) — то же самое приведение, что и
 *   нужно для компактного кэша. Реальный шаг между кадрами — минимум [stepMs] (кадры
 *   декодера не подгоняются под сетку точно, шаг равен ближайшему кадру декодера ≥ tick).
 *   Ошибка/недоступный кодек на устройстве — не должны ронять экран: [scanWindow] ловит
 *   исключение и отдаёт то, что успело насканиться (может быть пустой список — экран это
 *   переживёт, просто без плёнки/кэша, точный кадр по-прежнему работает).
 * - [exactFrameAt] — как в #100, `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)` на
 *   ОТДЕЛЬНОМ инстансе ретривера — не имеет общих ресурсов с [scanWindow], поэтому не может
 *   встать в очередь за ним (issue #117, причина №1).
 * - Высота превью считается из реальных width/height/rotation видео (метаданные ретривера),
 *   а не захардкожена — иначе кадр исказится на нестандартном соотношении сторон.
 */
class MediaMetadataFrameSource(
    private val source: FrameVideoSource,
    private val context: Context,
) : FrameSource {

    private val exactRetriever = MediaMetadataRetriever()
    private val exactLock = Any()
    private var prepared = false

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        synchronized(exactLock) {
            runCatching {
                when (val s = source) {
                    is FrameVideoSource.LocalPath -> exactRetriever.setDataSource(s.path)
                    is FrameVideoSource.ContentUri -> exactRetriever.setDataSource(context, s.uri)
                }
                prepared = true
                true
            }.getOrElse { false }
        }
    }

    override suspend fun scanWindow(
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        targetWidthPx: Int,
        onFrame: (CachedScrubFrame) -> Unit,
    ): List<CachedScrubFrame> = withContext(Dispatchers.IO) {
        if (!prepared || stepMs <= 0 || endMs < startMs) return@withContext emptyList()
        try {
            linearScan(startMs, endMs, stepMs, targetWidthPx, onFrame)
        } catch (e: CancellationException) {
            // ВАЖНО: не глотать отмену — `runCatching`/`catch (e: Exception)` ловит и её тоже,
            // из-за чего отменённый scanJob не переставал бы выполняться (он просто "успешно"
            // возвращал бы пустой список и продолжал жить дальше как будто ничего не случилось —
            // ровно тот баг с зависшей отменой, который #117 и должен был исправить).
            throw e
        } catch (e: Exception) {
            // Битый файл/недоступный кодек на устройстве — не роняем экран, отдаём то, что успели.
            emptyList()
        }
    }

    private suspend fun linearScan(
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        targetWidthPx: Int,
        onFrame: (CachedScrubFrame) -> Unit,
    ): List<CachedScrubFrame> {
        val results = mutableListOf<CachedScrubFrame>()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        try {
            when (source) {
                is FrameVideoSource.LocalPath -> extractor.setDataSource(source.path)
                is FrameVideoSource.ContentUri -> extractor.setDataSource(context, source.uri, null)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val srcWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotationDegrees = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }

            val initialReader = ImageReader.newInstance(srcWidth, srcHeight, ImageFormat.YUV_420_888, 2)
            reader = initialReader
            val mediaCodec = MediaCodec.createDecoderByType(mime)
            codec = mediaCodec
            mediaCodec.configure(format, initialReader.surface, null, 0)
            mediaCodec.start()

            val startUs = startMs * 1_000
            val endUs = endMs * 1_000
            val stepUs = stepMs * 1_000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var nextSampleUs = startUs

            while (!outputDone) {
                currentCoroutineContext().ensureActive() // цикл ограничен CODEC_TIMEOUT_US на итерацию — отмена не залипает

                if (!inputDone) {
                    val inIndex = mediaCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = mediaCodec.getInputBuffer(inIndex)
                        val sampleSize = inBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0 || extractor.sampleTime > endUs) {
                            mediaCodec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            mediaCodec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Контейнер иногда врёт про реальные width/height декодированного кадра
                    // (padding под макроблоки/выравнивание у некоторых OEM-пайплайнов) —
                    // ImageReader создан заранее под КОНТЕЙНЕРНЫЕ размеры (доступны только
                    // они на старте, до первого выхода кодека), если декодер после запуска
                    // сообщает другие — пересоздаём consumer под реальный размер, не пытаясь
                    // читать несовпадающий буфер (лучше пересоздать, чем поймать краш/мусор
                    // в acquireLatestImage на непроверенном на устройстве пути).
                    val outFormat = mediaCodec.outputFormat
                    val actualWidth = outFormat.getInteger(MediaFormat.KEY_WIDTH, srcWidth)
                    val actualHeight = outFormat.getInteger(MediaFormat.KEY_HEIGHT, srcHeight)
                    val activeReader = requireNotNull(reader)
                    if (actualWidth != activeReader.width || actualHeight != activeReader.height) {
                        val resized = ImageReader.newInstance(actualWidth, actualHeight, ImageFormat.YUV_420_888, 2)
                        mediaCodec.setOutputSurface(resized.surface)
                        activeReader.close()
                        reader = resized
                    }
                } else if (outIndex >= 0) {
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val presentationUs = bufferInfo.presentationTimeUs
                    val shouldRender = bufferInfo.size > 0 && presentationUs >= nextSampleUs && presentationUs <= endUs
                    mediaCodec.releaseOutputBuffer(outIndex, shouldRender)
                    if (shouldRender) {
                        acquireImageWithRetry(requireNotNull(reader))?.use { image ->
                            imageToJpeg(image, rotationDegrees, targetWidthPx)?.let { jpeg ->
                                val frame = CachedScrubFrame(presentationUs / 1_000, jpeg)
                                results += frame
                                onFrame(frame)
                            }
                        }
                        nextSampleUs += stepUs
                    }
                    if (isEos) outputDone = true
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { reader?.close() }
            runCatching { extractor.release() }
        }
        return results
    }

    /**
     * `releaseOutputBuffer(index, render = true)` ставит кадр в очередь на рендер в
     * `Surface` асинхронно — `acquireLatestImage()` сразу после вызова иногда ещё не видит
     * буфер (гонка между кодеком и `ImageReader`). Несколько коротких попыток — стандартная
     * защита от этой гонки (без выделенного `HandlerThread` под `OnImageAvailableListener`).
     */
    private fun acquireImageWithRetry(reader: ImageReader): Image? {
        repeat(IMAGE_ACQUIRE_ATTEMPTS) {
            reader.acquireLatestImage()?.let { return it }
            Thread.sleep(IMAGE_ACQUIRE_RETRY_DELAY_MS)
        }
        return null
    }

    override suspend fun exactFrameAt(timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (!prepared) return@withContext null
        synchronized(exactLock) {
            runCatching { exactRetriever.getFrameAtTime(timeMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
        }
    }

    override fun release() {
        synchronized(exactLock) { runCatching { exactRetriever.release() } }
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val IMAGE_ACQUIRE_ATTEMPTS = 20
        const val IMAGE_ACQUIRE_RETRY_DELAY_MS = 2L
    }
}

private const val INTERMEDIATE_JPEG_QUALITY = 90
private const val SCAN_JPEG_QUALITY = 70

/**
 * `YUV_420_888` → NV21 → JPEG (через `YuvImage`, без ручной YUV→RGB математики) → decode →
 * поворот по метаданным трека → уменьшение до [targetWidthPx] → JPEG кэша. Двойное
 * JPEG-кодирование (полный размер, затем уменьшенный) — сознательный компромисс: `YuvImage`
 * не умеет отдавать сразу уменьшенный кадр, а перекодирование маленькой картинки — доли
 * миллисекунды, не узкое место на фоне самого декода.
 */
private fun imageToJpeg(image: Image, rotationDegrees: Int, targetWidthPx: Int): ByteArray? {
    val nv21 = yuv420888ToNv21(image) ?: return null
    val fullJpeg = ByteArrayOutputStream()
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), INTERMEDIATE_JPEG_QUALITY, fullJpeg)) return null
    val fullBitmap = runCatching {
        BitmapFactory.decodeByteArray(fullJpeg.toByteArray(), 0, fullJpeg.size())
    }.getOrNull() ?: return null

    val rotated = if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, matrix, true).also {
            if (it !== fullBitmap) fullBitmap.recycle()
        }
    } else {
        fullBitmap
    }

    val targetHeight = (rotated.height.toDouble() / rotated.width * targetWidthPx).roundToInt().coerceAtLeast(1)
    val scaled = if (rotated.width == targetWidthPx) {
        rotated
    } else {
        Bitmap.createScaledBitmap(rotated, targetWidthPx, targetHeight, true).also {
            if (it !== rotated) rotated.recycle()
        }
    }

    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, SCAN_JPEG_QUALITY, out)
    scaled.recycle()
    return out.toByteArray()
}

/**
 * Репакует три плоскости `YUV_420_888` (Y, U, V — каждая со своими `rowStride`/`pixelStride`,
 * они не обязаны совпадать с шириной кадра из-за выравнивания памяти) в плоский NV21-массив
 * (`Y`, затем чередующиеся `V`,`U` на половинном разрешении по обеим осям — формат, который
 * ожидает [YuvImage]). Устройство-независимая, документированная схема доступа к плоскостям
 * `Image` — единственный вход без GPU/`SurfaceTexture`.
 */
private fun yuv420888ToNv21(image: Image): ByteArray? {
    val width = image.width
    val height = image.height
    val planes = image.planes
    if (planes.size < 3) return null
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val nv21 = ByteArray(width * height * 3 / 2)
    var pos = 0

    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    for (row in 0 until height) {
        yBuffer.position(row * yRowStride)
        yBuffer.get(nv21, pos, width)
        pos += width
    }

    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        val uRowStart = row * uRowStride
        val vRowStart = row * vRowStride
        for (col in 0 until chromaWidth) {
            nv21[pos++] = vBuffer.get(vRowStart + col * vPixelStride)
            nv21[pos++] = uBuffer.get(uRowStart + col * uPixelStride)
        }
    }
    return nv21
}

/** Фабрика [FrameSource] — уровень непрямого обращения для DI/подмены реализации. */
interface FrameSourceFactory {
    fun create(source: FrameVideoSource): FrameSource
}

@Singleton
class MediaMetadataFrameSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : FrameSourceFactory {
    override fun create(source: FrameVideoSource): FrameSource = MediaMetadataFrameSource(source, context)
}
