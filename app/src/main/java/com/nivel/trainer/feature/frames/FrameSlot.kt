package com.nivel.trainer.feature.frames

/**
 * Слот кадра на инсайт-карточке (A5, #99 — `momentBeforeSeconds`/`momentAfterSeconds`,
 * `frameBeforeUrl`/`frameAfterUrl` на [com.nivel.trainer.domain.InsightCard]). Ровно два
 * именованных слота — «до» (ошибка) и «после» (исправление), см. NIVEL#235.
 */
enum class FrameSlot {
    BEFORE,
    AFTER,
    ;

    /** Значение слота в маршруте навигации ([com.nivel.trainer.feature.NivelRoutes.frame]). */
    val routeValue: String
        get() = when (this) {
            BEFORE -> "before"
            AFTER -> "after"
        }

    companion object {
        /** Разбор аргумента маршрута; неизвестное/отсутствующее значение — [BEFORE] (безопасный дефолт). */
        fun fromRouteValue(raw: String?): FrameSlot = when (raw) {
            "after" -> AFTER
            else -> BEFORE
        }
    }
}

/**
 * Результат выбора кадра (A6, #100) — путь к сохранённому JPEG в `cacheDir/frames/` и
 * выбранное время (сек., таймлайн аудио/транскрипта — тот же, что [FrameSlot] момента
 * карточки, НЕ позиция в видеофайле). Заливка на сервер — задача A7 (#101), здесь не
 * делается: экран лишь возвращает результат вызывающему через `savedStateHandle`
 * (см. [FrameScrubberResultKeys] и `NivelNavHost`).
 */
data class FrameSelectionResult(
    val cardId: String,
    val slot: FrameSlot,
    val jpegPath: String,
    val selectedSeconds: Double,
)

/** Ключи `savedStateHandle`, которыми `NivelNavHost` передаёт [FrameSelectionResult] назад вызывающему экрану. */
object FrameScrubberResultKeys {
    const val CARD_ID = "frame_result_card_id"
    const val SLOT = "frame_result_slot"
    const val JPEG_PATH = "frame_result_jpeg_path"
    const val SELECTED_SECONDS = "frame_result_selected_seconds"
}
