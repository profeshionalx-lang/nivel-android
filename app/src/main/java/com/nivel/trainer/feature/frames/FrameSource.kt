package com.nivel.trainer.feature.frames

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Один кадр плёнки-превью — время в видео (мс) + уже уменьшенный bitmap. */
data class FilmstripFrame(val videoTimeMs: Long, val bitmap: Bitmap)

/**
 * Источник кадров видео (A6, #100) — спрятан за интерфейсом, чтобы ExoPlayer/Media3 мог
 * стать планом Б, если декод `MediaMetadataRetriever` окажется слишком медленным на слабых
 * устройствах (см. issue). Единственная реализация сегодня — [MediaMetadataFrameSource].
 *
 * Все методы — suspend/IO: `MediaMetadataRetriever` синхронный и блокирующий.
 */
interface FrameSource {
    /** Открывает видеофайл. false — файл битый/недоступен (например, удалён вручную). */
    suspend fun prepare(): Boolean

    /**
     * Строит плёнку превью в диапазоне `[startMs, endMs]` с шагом [stepMs] —
     * `getScaledFrameAtTime(OPTION_CLOSEST_SYNC)`. [onProgress] зовётся после каждого
     * кадра (`built`/`total`) — экран показывает индикатор построения. Кооперативно
     * отменяемо: уход с экрана посреди построения останавливает декод, не долистывая
     * до конца диапазона (см. класс-докблок [FrameScrubberViewModel]).
     */
    suspend fun buildFilmstrip(
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        thumbnailWidthPx: Int,
        onProgress: (built: Int, total: Int) -> Unit,
    ): List<FilmstripFrame>

    /**
     * Точный кадр на [timeMs] — `getFrameAtTime(OPTION_CLOSEST)` (НЕ `CLOSEST_SYNC`,
     * см. класс-докблок ниже). Используется и для крупного превью текущего выбора, и
     * для итогового JPEG — один и тот же вызов, чтобы превью гарантированно совпадало
     * с тем, что уедет в файл (acceptance issue #100).
     */
    suspend fun exactFrameAt(timeMs: Long): Bitmap?

    /** Освобождает `MediaMetadataRetriever`. Идемпотентно, безопасно звать повторно. */
    fun release()
}

/**
 * `MediaMetadataRetriever`-реализация [FrameSource] (A6, #100).
 *
 * - Плёнка: `getScaledFrameAtTime(..., OPTION_CLOSEST_SYNC, width, height)` — API 27+.
 *   На API 26 (минимальный minSdk проекта) метода нет — фолбэк: полнокадровый
 *   `getFrameAtTime(OPTION_CLOSEST_SYNC)` + ручной `Bitmap.createScaledBitmap`.
 *   `CLOSEST_SYNC` для превью нормален и быстр (ищет от ближайшего sync-кадра), но
 *   **непригоден для точного выбора** — может прыгнуть на key-фрейм за секунды до
 *   искомого момента (issue). Поэтому точный кадр — отдельным методом на `CLOSEST`
 *   (декодирует честно к нужной позиции, дороже, но здесь это ровно один вызов, не 40).
 * - Высота превью считается из реальных width/height/rotation видео (метаданные), а не
 *   захардкожена — иначе плёнка исказится на нестандартном соотношении сторон.
 * - [MediaMetadataRetriever] не документирован как потокобезопасный для параллельных
 *   вызовов на одном инстансе — экран одновременно строит плёнку (Dispatchers.IO) и
 *   декодирует крупное превью по тапу/слайдеру, которые могут прилететь, пока плёнка
 *   ещё строится. [lock] сериализует все обращения к [retriever], включая [release] —
 *   без этого `release()` из `onCleared()` мог бы выполниться конкурентно с ещё не
 *   успевшей отмениться (отмена корутины асинхронна) декодирующей операцией на другом
 *   потоке пула Dispatchers.IO. `synchronized` тут безопасен: внутри блоков нет suspend
 *   вызовов — только синхронные нативные вызовы ретривера.
 */
class MediaMetadataFrameSource(private val videoPath: String) : FrameSource {

    private val retriever = MediaMetadataRetriever()
    private val lock = Any()
    private var prepared = false
    private var thumbnailAspectRatio = 9.0 / 16.0 // высота/ширина, дефолт — портретное видео с телефона

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                retriever.setDataSource(videoPath)
                readAspectRatio()
                prepared = true
                true
            }.getOrElse { false }
        }
    }

    private fun readAspectRatio() {
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        if (rawWidth == null || rawHeight == null || rawWidth <= 0 || rawHeight <= 0) return
        val swapped = rotation == 90 || rotation == 270
        val width = if (swapped) rawHeight else rawWidth
        val height = if (swapped) rawWidth else rawHeight
        thumbnailAspectRatio = height.toDouble() / width.toDouble()
    }

    override suspend fun buildFilmstrip(
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        thumbnailWidthPx: Int,
        onProgress: (built: Int, total: Int) -> Unit,
    ): List<FilmstripFrame> = withContext(Dispatchers.IO) {
        if (!prepared || stepMs <= 0 || endMs < startMs) return@withContext emptyList()
        val times = buildList {
            var t = startMs
            while (t < endMs) {
                add(t)
                t += stepMs
            }
            add(endMs)
        }
        val height = (thumbnailWidthPx * thumbnailAspectRatio).roundToInt().coerceAtLeast(1)
        val result = ArrayList<FilmstripFrame>(times.size)
        for ((index, timeMs) in times.withIndex()) {
            currentCoroutineContext().ensureActive() // уход с экрана отменяет job — не долистываем впустую
            scaledFrameAt(timeMs, thumbnailWidthPx, height)?.let { result += FilmstripFrame(timeMs, it) }
            onProgress(index + 1, times.size)
        }
        result
    }

    private fun scaledFrameAt(timeMs: Long, widthPx: Int, heightPx: Int): Bitmap? = synchronized(lock) {
        runCatching {
            val timeUs = timeMs * 1_000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, widthPx, heightPx)
            } else {
                val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                full?.let { Bitmap.createScaledBitmap(it, widthPx, heightPx, true) }
            }
        }.getOrNull()
    }

    override suspend fun exactFrameAt(timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (!prepared) return@withContext null
        synchronized(lock) {
            runCatching { retriever.getFrameAtTime(timeMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
        }
    }

    override fun release() {
        synchronized(lock) { runCatching { retriever.release() } }
    }
}

/** Фабрика [FrameSource] — уровень непрямого обращения для DI/подмены реализации (план Б — ExoPlayer). */
interface FrameSourceFactory {
    fun create(videoPath: String): FrameSource
}

@Singleton
class MediaMetadataFrameSourceFactory @Inject constructor() : FrameSourceFactory {
    override fun create(videoPath: String): FrameSource = MediaMetadataFrameSource(videoPath)
}
