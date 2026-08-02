package com.nivel.trainer.feature.frames

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.local.LocalVideoStore
import com.nivel.trainer.data.local.VideoRecord
import com.nivel.trainer.data.local.VideoSource
import com.nivel.trainer.data.repository.SessionDetailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние экрана скрабера кадра (A6, #100). */
sealed interface FrameScrubberUiState {
    data object Loading : FrameScrubberUiState

    /** Видео для сессии нет (не записывалось / удалено после разбора) — экран не открывается, только объяснение. */
    data class NoVideo(val message: String) : FrameScrubberUiState

    /** Карточка/момент не нашлись, файл видео повреждён и т.п. — тоже без крэша. */
    data class Error(val message: String) : FrameScrubberUiState

    data class Ready(
        val windowStartMs: Long,
        val windowEndMs: Long,
        val selectedTimeMs: Long,
        val filmstrip: List<FilmstripFrame> = emptyList(),
        // Плотный кэш линейного разбора (#117, шаг FrameWindow.SCAN_STEP_MS) — по нему
        // скраб слайдера ищет ближайший готовый кадр во время перетаскивания, без единого
        // обращения к ретриверу на каждое микродвижение пальца.
        val scanCache: List<CachedScrubFrame> = emptyList(),
        val buildingFilmstrip: Boolean = true,
        val buildProgress: Pair<Int, Int>? = null,
        val previewBitmap: Bitmap? = null,
        // false — previewBitmap это приближённый кадр кэша скана (во время перетаскивания)
        // или мгновенный плейсхолдер при открытии окна, не то, что должно уехать в файл.
        val previewIsExact: Boolean = false,
        val previewLoading: Boolean = false,
        val expanded: Boolean = false,
        val saving: Boolean = false,
        /**
         * Ошибка сохранения кадра (декод/диск) — fix «после загрузки не сохраняется
         * выбранный кадр»: раньше [onConfirm] звал `onError` колбэком экрана, который
         * НИЧЕГО не делал (см. старый `FrameScrubberScreen.onConfirm { onError = {} }`),
         * тренер тапал «Выбрать кадр», ошибка проглатывалась молча, кадр не сохранялся
         * и экран выглядел так, будто ничего не произошло. Теперь ошибка — часть
         * состояния, экран обязан её показать ([com.nivel.trainer.ui.state.ErrorBanner]).
         */
        val saveError: String? = null,
    ) : FrameScrubberUiState
}

/**
 * ViewModel скрабера кадра (A6, #100) — сердце эпика видео-инсайтов: снимает риск
 * «таймкод фразы ≠ таймкод удара», позволяя тренеру глазами найти точный кадр в
 * коротком окне вокруг момента карточки.
 *
 * Источники данных:
 *  - [SessionDetailRepository.getOverview] — карточка (момент слота), без отдельного
 *    эндпоинта «одна карточка»: A5 (#99) добавил моменты только в общий агрегат.
 *  - [LocalVideoStore] — путь к локальному `.mp4` и `audioStartOffsetMs`; видео на
 *    сервер не уезжает (эпик NIVEL#235), поэтому источник ровно один — телефон.
 *  - [FrameSourceFactory] — декод кадров, за интерфейсом (план Б — ExoPlayer).
 *
 * Память/декодеры: [scanJob] (линейный разбор окна, #117) живёт в [viewModelScope] —
 * [onCleared] отменяет его структурной конкурентностью, `finally` в [FrameSource] отпускает
 * нативные ресурсы при отмене так же, как и при обычном завершении. Уход с экрана посреди
 * разбора не оставляет «горячих» декодеров (acceptance issue #100/#117).
 *
 * Скраб слайдера (#117) — во время перетаскивания [onSliderDragging] берёт ближайший
 * готовый кадр из уже насканенного окна (мгновенно, без обращения к диску), точный кадр
 * (`OPTION_CLOSEST`) декодируется только при отпускании ([onSliderReleased]) или тапе по
 * миниатюре ([onThumbnailSelected]) — там, где секундная неточность уже недопустима.
 */
@HiltViewModel
class FrameScrubberViewModel @Inject constructor(
    private val sessionDetailRepository: SessionDetailRepository,
    private val localVideoStore: LocalVideoStore,
    private val frameSourceFactory: FrameSourceFactory,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<FrameScrubberUiState>(FrameScrubberUiState.Loading)
    val state: StateFlow<FrameScrubberUiState> = _state.asStateFlow()

    private var frameSource: FrameSource? = null
    private var videoRecord: VideoRecord? = null
    private var momentSeconds: Double? = null
    private var scanJob: Job? = null
    private var previewJob: Job? = null

    private var loaded = false

    /** Первичная загрузка — screen зовёт из `LaunchedEffect(Unit)`, идемпотентна. */
    fun load(sessionId: String, cardId: String, slot: FrameSlot) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val overview = sessionDetailRepository.getOverview(sessionId).getOrElse {
                _state.value = FrameScrubberUiState.Error("Не удалось загрузить карточку. Проверьте связь и попробуйте снова.")
                return@launch
            }
            val card = overview.cards.find { it.id == cardId }
            if (card == null) {
                _state.value = FrameScrubberUiState.Error("Карточка не найдена.")
                return@launch
            }
            val moment = when (slot) {
                FrameSlot.BEFORE -> card.momentBeforeSeconds
                FrameSlot.AFTER -> card.momentAfterSeconds
            }
            if (moment == null) {
                _state.value = FrameScrubberUiState.Error("У этой карточки нет момента «${if (slot == FrameSlot.BEFORE) "до" else "после"}» — LLM не выделила таймкод.")
                return@launch
            }
            val record = localVideoStore.get(sessionId)
            if (record == null) {
                _state.value = FrameScrubberUiState.NoVideo(
                    "Видео этой тренировки не найдено на телефоне — оно либо не записывалось, либо уже удалено после завершения разбора.",
                )
                return@launch
            }
            val videoSource = record.toFrameVideoSource()
            if (videoSource == null || !isAvailable(videoSource)) {
                val message = when (record.source) {
                    VideoSource.RECORDED -> "Файл видео на телефоне отсутствует — возможно, он был удалён вручную."
                    VideoSource.IMPORTED -> "Видео из галереи недоступно — возможно, файл был удалён или перемещён."
                }
                _state.value = FrameScrubberUiState.NoVideo(message)
                return@launch
            }

            momentSeconds = moment
            videoRecord = record
            val source = frameSourceFactory.create(videoSource)
            val prepared = withContext(Dispatchers.IO) { source.prepare() }
            if (!prepared) {
                source.release() // retriever уже аллоцировал нативные ресурсы к моменту неудачного prepare()
                _state.value = FrameScrubberUiState.Error("Не удалось открыть видеофайл — возможно, он повреждён.")
                return@launch
            }
            frameSource = source

            val momentVideoMs = FrameWindow.momentToVideoMs(moment, record.audioStartOffsetMs)
            val window = FrameWindow.default(momentVideoMs, record.durationMs.takeIf { it > 0 })
            openWindow(window, expanded = false, initialSelection = momentVideoMs.coerceIn(window.first, window.last))
        }
    }

    /** Кнопка «Расширить окно (±30 с)» — issue: без неё галлюцинированный таймкод делает карточку некадрируемой. */
    fun onExpandWindow() {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        if (ready.expanded) return
        val moment = momentSeconds ?: return
        val record = videoRecord ?: return
        val momentVideoMs = FrameWindow.momentToVideoMs(moment, record.audioStartOffsetMs)
        val window = FrameWindow.expanded(momentVideoMs, record.durationMs.takeIf { it > 0 })
        openWindow(window, expanded = true, initialSelection = ready.selectedTimeMs.coerceIn(window.first, window.last))
    }

    private fun openWindow(window: LongRange, expanded: Boolean, initialSelection: Long) {
        scanJob?.cancel()
        _state.value = FrameScrubberUiState.Ready(
            windowStartMs = window.first,
            windowEndMs = window.last,
            selectedTimeMs = initialSelection,
            buildingFilmstrip = true,
            previewLoading = true,
            expanded = expanded,
        )
        val source = frameSource ?: return
        val totalTicks = ((window.last - window.first) / FrameWindow.SCAN_STEP_MS + 1).toInt().coerceAtLeast(1)
        scanJob = viewModelScope.launch {
            val cache = mutableListOf<CachedScrubFrame>()
            var lastFilmstripMs = window.first - FrameWindow.THUMBNAIL_STEP_MS // первый кадр всегда попадает в плёнку
            var placeholderShown = false
            source.scanWindow(
                startMs = window.first,
                endMs = window.last,
                stepMs = FrameWindow.SCAN_STEP_MS,
                targetWidthPx = SCAN_WIDTH_PX,
            ) { frame ->
                cache += frame
                val onFilmstripTick = frame.timeMs - lastFilmstripMs >= FrameWindow.THUMBNAIL_STEP_MS
                // Плёнка держит десятки raw Bitmap'ов на весь срок жизни окна (не JPEG, как
                // scanCache) — декодируем в её собственном, заметно более узком разрешении,
                // а не в SCAN_WIDTH_PX (тот рассчитан на крупное превью при драге, не на
                // ряд миниатюр по 64dp — раздувать его сюда было бы чистой тратой памяти).
                val filmstripFrame = if (onFilmstripTick) decodeFilmstripBitmap(frame)?.let { FilmstripFrame(frame.timeMs, it) } else null
                if (filmstripFrame != null) lastFilmstripMs = frame.timeMs
                _state.update { current ->
                    if (current !is FrameScrubberUiState.Ready) return@update current
                    var next = current.copy(
                        scanCache = cache.toList(),
                        filmstrip = if (filmstripFrame != null) current.filmstrip + filmstripFrame else current.filmstrip,
                        buildProgress = cache.size to totalTicks,
                    )
                    // Мгновенный плейсхолдер (issue: даже с предзагрузкой первый кадр не появляется
                    // мгновенно) — как только скан проходит рядом с выбранной позицией, показываем
                    // его, не дожидаясь окончания всего окна; точный кадр придёт следом отдельно.
                    if (!placeholderShown && kotlin.math.abs(frame.timeMs - initialSelection) <= FrameWindow.SCAN_STEP_MS) {
                        placeholderShown = true
                        next = next.copy(previewBitmap = frame.decode(), previewIsExact = false)
                    }
                    next
                }
            }
            _state.update { current ->
                if (current !is FrameScrubberUiState.Ready) return@update current
                current.copy(buildingFilmstrip = false, buildProgress = null)
            }
            requestExactPreview(initialSelection)
        }
    }

    /** Тап по миниатюре плёнки — сразу точный тайминг этого кадра (issue: должен совпасть с превью). */
    fun onThumbnailSelected(timeMs: Long) = selectExact(timeMs)

    /**
     * Движение слайдера ВО ВРЕМЯ перетаскивания (#117) — берёт ближайший уже насканенный
     * кадр из кэша окна ([FrameScrubberUiState.Ready.scanCache]), без обращения к диску:
     * никакого спиннера, реакция мгновенная. Точный кадр — только при отпускании
     * ([onSliderReleased]), см. класс-докблок.
     */
    fun onSliderDragging(timeMs: Long) {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        val clamped = timeMs.coerceIn(ready.windowStartMs, ready.windowEndMs)
        // Новый выбор кадра — прошлая ошибка сохранения больше не актуальна.
        _state.value = ready.copy(selectedTimeMs = clamped, saveError = null)
        val cached = nearestCachedFrame(ready.scanCache, clamped) ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            val bitmap = cached.decode() ?: return@launch
            _state.update { current ->
                if (current !is FrameScrubberUiState.Ready || current.selectedTimeMs != clamped) return@update current
                current.copy(previewBitmap = bitmap, previewIsExact = false, previewLoading = false)
            }
        }
    }

    /** Слайдер отпущен — точный кадр этой позиции (issue: не ближайший опорный, честный `OPTION_CLOSEST`). */
    fun onSliderReleased(timeMs: Long) = selectExact(timeMs)

    private fun selectExact(timeMs: Long) {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        val clamped = timeMs.coerceIn(ready.windowStartMs, ready.windowEndMs)
        // Новый выбор кадра — прошлая ошибка сохранения больше не актуальна.
        _state.value = ready.copy(selectedTimeMs = clamped, saveError = null)
        requestExactPreview(clamped)
    }

    private fun requestExactPreview(timeMs: Long) {
        val source = frameSource ?: return
        previewJob?.cancel()
        // previewBitmap НЕ сбрасываем — уже показанный кэш-кадр/плейсхолдер остаётся на
        // экране под спиннером, а не пропадает в чёрный экран на время точного декода.
        _state.update { current -> if (current is FrameScrubberUiState.Ready) current.copy(previewLoading = true) else current }
        previewJob = viewModelScope.launch {
            val bitmap = source.exactFrameAt(timeMs)
            _state.update { current ->
                if (current !is FrameScrubberUiState.Ready || current.selectedTimeMs != timeMs) return@update current
                if (bitmap != null) {
                    current.copy(previewBitmap = bitmap, previewIsExact = true, previewLoading = false)
                } else {
                    current.copy(previewLoading = false) // не удалось — оставляем последний показанный кадр как есть
                }
            }
        }
    }

    /** Ближайший по времени готовый кадр кэша скана — линейный поиск: кэш ≤~130 кадров, дёшево. */
    private fun nearestCachedFrame(cache: List<CachedScrubFrame>, timeMs: Long): CachedScrubFrame? =
        cache.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }

    /** JPEG кэша (SCAN_WIDTH_PX) декодируем и сразу уменьшаем до узкой ширины плёнки — держать
     * в [FrameScrubberUiState.Ready.filmstrip] десятки raw Bitmap'ов в полном SCAN_WIDTH_PX
     * не нужно, миниатюра рисуется на 64dp. */
    private fun decodeFilmstripBitmap(frame: CachedScrubFrame): Bitmap? {
        val full = frame.decode() ?: return null
        if (full.width <= FILMSTRIP_WIDTH_PX) return full
        val height = (full.height.toDouble() / full.width * FILMSTRIP_WIDTH_PX).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(full, FILMSTRIP_WIDTH_PX, height, true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    /**
     * «Выбрать кадр» — декодирует точный кадр на `selectedTimeMs` (тот же `OPTION_CLOSEST`,
     * что и превью — см. [FrameSource.exactFrameAt]), даунскейлит до 1280px по длинной
     * стороне и сохраняет JPEG q=85 в `cacheDir/frames/`. Результат отдаётся [onResult] —
     * экран сам решает, что делать дальше (в NavHost — положить в savedStateHandle и
     * закрыть экран); заливка на сервер сюда не входит (A7, #101).
     *
     * Fix «после загрузки не сохраняется выбранный кадр»: раньше сюда передавался
     * `onError` колбэк, который экран передавал пустым (`onError = {}`) — любая ошибка
     * декода/диска проглатывалась молча. Ошибка теперь пишется в [FrameScrubberUiState.Ready.saveError]
     * самим VM ([dismissSaveError] его сбрасывает) — экран обязан её отрисовать.
     *
     * Также раньше все `_state.value = ready.copy(...)` в этой функции применялись
     * поверх [ready] — снимка состояния, захваченного ДО начала корутины. Если за время
     * декода/записи на диск (`withContext(Dispatchers.IO)`) плёнка или превью успевали
     * обновиться (см. [openWindow]/[requestPreview]), этот код тихо откатывал их — экран
     * на миг «терял» уже построенные кадры плёнки. Заменено на [MutableStateFlow.update]
     * поверх ТЕКУЩЕГО состояния.
     */
    fun onConfirm(cardId: String, slot: FrameSlot, onResult: (FrameSelectionResult) -> Unit) {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        val source = frameSource ?: return
        if (ready.saving) return
        _state.update { (it as? FrameScrubberUiState.Ready ?: return@update it).copy(saving = true, saveError = null) }
        viewModelScope.launch {
            // previewBitmap может быть приближённым кадром кэша скана (previewIsExact=false,
            // если тренер успел нажать «Выбрать кадр» до прихода точного) — в файл должен
            // уйти именно OPTION_CLOSEST, не кэш (issue #117, acceptance: полное качество).
            val bitmap = ready.previewBitmap.takeIf { ready.previewIsExact } ?: source.exactFrameAt(ready.selectedTimeMs)
            if (bitmap == null) {
                _state.update {
                    (it as? FrameScrubberUiState.Ready ?: return@update it)
                        .copy(saving = false, saveError = "Не удалось получить кадр. Попробуйте другой момент.")
                }
                return@launch
            }
            val savedPath = withContext(Dispatchers.IO) { saveJpeg(bitmap) }
            if (savedPath == null) {
                _state.update {
                    (it as? FrameScrubberUiState.Ready ?: return@update it)
                        .copy(saving = false, saveError = "Не удалось сохранить кадр на телефоне.")
                }
                return@launch
            }
            val moment = momentSeconds
            val record = videoRecord
            val selectedSeconds = if (moment != null && record != null) {
                // Обратный перевод: позиция видео → таймлайн аудио/транскрипта, тот же,
                // в котором сервер уже хранит momentBefore/AfterSeconds (S1/S4 схема).
                (ready.selectedTimeMs - (record.audioStartOffsetMs ?: 0L)) / 1_000.0
            } else {
                ready.selectedTimeMs / 1_000.0
            }
            _state.update { (it as? FrameScrubberUiState.Ready ?: return@update it).copy(saving = false) }
            onResult(FrameSelectionResult(cardId = cardId, slot = slot, jpegPath = savedPath, selectedSeconds = selectedSeconds))
        }
    }

    /** Закрыть баннер ошибки сохранения кадра (крестик на [com.nivel.trainer.ui.state.ErrorBanner]). */
    fun dismissSaveError() {
        _state.update { (it as? FrameScrubberUiState.Ready ?: return@update it).copy(saveError = null) }
    }

    /** [VideoRecord] → [FrameVideoSource] по источнику (A10, #115); `null` — данных не хватает (не должно случиться). */
    private fun VideoRecord.toFrameVideoSource(): FrameVideoSource? = when (source) {
        VideoSource.RECORDED -> FrameVideoSource.LocalPath(videoPath)
        VideoSource.IMPORTED -> videoUri?.let { FrameVideoSource.ContentUri(Uri.parse(it)) }
    }

    /** «Видео на месте» — для RECORDED это `File.exists()`, для IMPORTED — реально открывается `content://`. */
    private fun isAvailable(source: FrameVideoSource): Boolean = when (source) {
        is FrameVideoSource.LocalPath -> File(source.path).exists()
        is FrameVideoSource.ContentUri -> runCatching {
            context.contentResolver.openFileDescriptor(source.uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun saveJpeg(bitmap: Bitmap): String? = runCatching {
        val dir = File(context.cacheDir, "frames").apply { mkdirs() }
        val longSide = maxOf(bitmap.width, bitmap.height)
        val scale = if (longSide > MAX_JPEG_LONG_SIDE_PX) MAX_JPEG_LONG_SIDE_PX.toDouble() / longSide else 1.0
        val scaled = if (scale < 1.0) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else {
            bitmap
        }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        file.absolutePath
    }.getOrNull()

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        previewJob?.cancel()
        frameSource?.release()
        frameSource = null
    }

    private companion object {
        // Ширина кадров кэша скана (#117) — компромисс качество/память: JPEG ~470px в
        // памяти держит окно из ~100 кадров единицами мегабайт (см. FrameSource docblock),
        // достаточно чётко для превью во время активного перетаскивания.
        const val SCAN_WIDTH_PX = 480

        // Плёнка миниатюр рисуется на 64dp (ThumbnailWidth в Screen) — держать raw Bitmap'ы
        // на весь срок жизни окна в SCAN_WIDTH_PX было бы 2.25x лишней памяти без всякой
        // выгоды в качестве картинки такого размера.
        const val FILMSTRIP_WIDTH_PX = 320

        const val MAX_JPEG_LONG_SIDE_PX = 1280
        const val JPEG_QUALITY = 85
    }
}
