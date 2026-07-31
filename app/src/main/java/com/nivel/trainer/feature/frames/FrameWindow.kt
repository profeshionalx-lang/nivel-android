package com.nivel.trainer.feature.frames

/**
 * Чистая логика окна скрабера (A6, #100) — вынесена из ViewModel, чтобы не зависеть от
 * Android-фреймворка (единственная причина держать её отдельно — легче проверить глазами).
 *
 * Момент карточки ([FrameSlot]) — секунды в таймлайне **аудио/транскрипта**, не видео:
 * когда тренер снимает видео, звук пишет отдельный параллельный `MediaRecorder`, стартующий
 * позже видео на `audioStartOffsetMs` (см. [com.nivel.trainer.data.local.VideoRecord]).
 * Поэтому момент нужно сдвинуть на этот оффсет, прежде чем открывать окно в видео.
 */
object FrameWindow {
    /** Окно режется НАЗАД от фразы — удар почти всегда ДО того, как тренер о нём заговорил. */
    const val DEFAULT_BEFORE_SEC = 8.0
    const val DEFAULT_AFTER_SEC = 2.0

    /** Ручное расширение (issue: без него LLM-галлюцинация таймкода делает карточку некадрируемой). */
    const val EXPANDED_RANGE_SEC = 30.0

    /** Шаг плёнки превью. */
    const val THUMBNAIL_STEP_MS = 250L

    /** Переводит момент из таймлайна аудио/транскрипта в позицию видео (мс). */
    fun momentToVideoMs(momentSeconds: Double, audioStartOffsetMs: Long?): Long =
        (momentSeconds * 1_000).toLong() + (audioStartOffsetMs ?: 0L)

    /** Окно `[-8с…+2с]` от момента, в мс видео, зажатое `[0, durationMs]` (если длительность известна). */
    fun default(momentVideoMs: Long, durationMs: Long?): LongRange = window(
        momentVideoMs = momentVideoMs,
        beforeMs = (DEFAULT_BEFORE_SEC * 1_000).toLong(),
        afterMs = (DEFAULT_AFTER_SEC * 1_000).toLong(),
        durationMs = durationMs,
    )

    /** Расширенное окно `±30с» от момента, тоже зажатое длительностью видео. */
    fun expanded(momentVideoMs: Long, durationMs: Long?): LongRange = window(
        momentVideoMs = momentVideoMs,
        beforeMs = (EXPANDED_RANGE_SEC * 1_000).toLong(),
        afterMs = (EXPANDED_RANGE_SEC * 1_000).toLong(),
        durationMs = durationMs,
    )

    private fun window(momentVideoMs: Long, beforeMs: Long, afterMs: Long, durationMs: Long?): LongRange {
        val start = (momentVideoMs - beforeMs).coerceAtLeast(0L)
        var end = momentVideoMs + afterMs
        if (durationMs != null && durationMs > 0) end = end.coerceAtMost(durationMs)
        return start..end.coerceAtLeast(start)
    }
}
