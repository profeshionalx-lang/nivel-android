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

    /**
     * Сколько кадров держим в памяти разом (#117, финальная версия) — владелец решил, что
     * точность до кадра не нужна («в рамках полсекунды нормально»): слайдер листает готовые
     * кадры БЕЗ декодирования на каждое движение, поэтому и плёнка, и позиции слайдера — это
     * один и тот же список из ~[TARGET_FRAME_COUNT] кадров, не отдельный плотный кэш.
     */
    const val TARGET_FRAME_COUNT = 20

    /** Нижняя граница шага — секунда удара короче полусекунды теряется, дальше мельчить смысла нет. */
    const val MIN_SCAN_STEP_MS = 500L

    /**
     * Шаг линейного разбора окна — подбирается так, чтобы кадров всегда было ~[TARGET_FRAME_COUNT]
     * независимо от длины окна: для дефолтного окна (10с) это 500мс (owner's own math), для
     * расширенного (60с) — 3000мс. Без этого «Расширить окно» раздуло бы память в разы (121
     * кадр на 500мс-сетке против 21 на дефолтной длине) — компромисс осознанно в сторону
     * бюджета памяти, не детализации: расширенное окно и так для грубого поиска.
     */
    fun scanStepMs(windowStartMs: Long, windowEndMs: Long): Long {
        val durationMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)
        return (durationMs / TARGET_FRAME_COUNT).coerceAtLeast(MIN_SCAN_STEP_MS)
    }

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
