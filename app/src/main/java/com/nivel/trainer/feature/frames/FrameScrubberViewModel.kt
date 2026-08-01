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
import kotlin.math.abs
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

    /**
     * Слайдер и плёнка — один и тот же список [frames] (#117, финальная версия): владелец
     * решил, что точность до кадра не нужна («в рамках полсекунды нормально»), поэтому
     * позиции слайдера дискретны — [selectedIndex] просто индексирует уже готовые кадры,
     * ничего не декодируя на движение (ни спиннера, ни отложенных задач).
     */
    data class Ready(
        val windowStartMs: Long,
        val windowEndMs: Long,
        val selectedIndex: Int = 0,
        val frames: List<FilmstripFrame> = emptyList(),
        val buildingFilmstrip: Boolean = true,
        val buildProgress: Pair<Int, Int>? = null,
        val expanded: Boolean = false,
        val saving: Boolean = false,
    ) : FrameScrubberUiState {
        val selectedTimeMs: Long get() = frames.getOrNull(selectedIndex)?.videoTimeMs ?: windowStartMs
        val previewBitmap: Bitmap? get() = frames.getOrNull(selectedIndex)?.bitmap
    }
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
 *  - [FrameSourceFactory] — декод кадров, за интерфейсом.
 *
 * Память/декодеры: [scanJob] (линейный разбор окна, #117) живёт в [viewModelScope] —
 * [onCleared] отменяет его структурной конкурентностью, `finally` в [FrameSource] отпускает
 * нативные ресурсы при отмене так же, как и при обычном завершении. Уход с экрана посреди
 * разбора не оставляет «горячих» декодеров (acceptance issue #100/#117).
 *
 * Скраб слайдера (#117, финальная версия) — весь набор кадров окна разбирается ОДИН раз
 * при открытии/расширении окна ([openWindow]) и держится в памяти целиком
 * ([FrameScrubberUiState.Ready.frames], ~[FrameWindow.TARGET_FRAME_COUNT] штук). Слайдер и
 * тап по миниатюре только листают этот список ([onSliderIndexChanged]/[onThumbnailSelected]) —
 * никакого декодирования на движение пальца. Честный `getFrameAtTime(OPTION_CLOSEST)` остаётся
 * только в [onConfirm] — там, где нужна точность и полное разрешение, а не скорость.
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

    // Слайдер и плёнка интерактивны уже во время скана (кадры приходят прогрессивно) — если
    // тренер успел ткнуть/подвигать ползунок до конца разбора, следующий пришедший кадр не
    // должен откатывать выбор обратно к initialSelection у него из-под пальца. true — как
    // только было хоть одно ручное взаимодействие в рамках текущего openWindow.
    private var userPickedSelection = false

    private fun openWindow(window: LongRange, expanded: Boolean, initialSelection: Long) {
        scanJob?.cancel()
        userPickedSelection = false
        _state.value = FrameScrubberUiState.Ready(
            windowStartMs = window.first,
            windowEndMs = window.last,
            buildingFilmstrip = true,
            expanded = expanded,
        )
        val source = frameSource ?: return
        // Шаг подбирается под длину окна так, чтобы кадров всегда было ~TARGET_FRAME_COUNT —
        // без этого «Расширить окно» (60с) на той же плотности раздуло бы память в разы.
        val stepMs = FrameWindow.scanStepMs(window.first, window.last)
        val totalTicks = ((window.last - window.first) / stepMs + 1).toInt().coerceAtLeast(1)
        scanJob = viewModelScope.launch {
            val frames = mutableListOf<FilmstripFrame>()
            source.scanWindow(
                startMs = window.first,
                endMs = window.last,
                stepMs = stepMs,
                targetWidthPx = FRAME_WIDTH_PX,
            ) { frame ->
                frames += frame
                _state.update { current ->
                    if (current !is FrameScrubberUiState.Ready) return@update current
                    var next = current.copy(frames = frames.toList(), buildProgress = frames.size to totalTicks)
                    // Индекс, ближайший к моменту карточки, пересчитываем на каждый пришедший
                    // кадр — как только скан пройдёт рядом с ним, слайдер сразу окажется в
                    // нужном месте, не дожидаясь конца окна. НО только пока тренер сам не
                    // тронул слайдер/миниатюру — иначе его выбор откатывало бы следующим кадром.
                    if (!userPickedSelection) next = next.copy(selectedIndex = nearestIndex(frames, initialSelection))
                    next
                }
            }
            _state.update { current ->
                if (current !is FrameScrubberUiState.Ready) return@update current
                var next = current.copy(buildingFilmstrip = false, buildProgress = null)
                if (!userPickedSelection) next = next.copy(selectedIndex = nearestIndex(current.frames, initialSelection))
                next
            }
        }
    }

    private fun nearestIndex(frames: List<FilmstripFrame>, timeMs: Long): Int =
        frames.indices.minByOrNull { abs(frames[it].videoTimeMs - timeMs) } ?: 0

    /** Тап по миниатюре плёнки — то же самое, что и позиция слайдера (#117: один и тот же список). */
    fun onThumbnailSelected(index: Int) = selectIndex(index)

    /** Движение слайдера (#117) — просто индексирует уже готовые кадры, без декодирования. */
    fun onSliderIndexChanged(index: Int) = selectIndex(index)

    private fun selectIndex(index: Int) {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        if (ready.frames.isEmpty()) return
        userPickedSelection = true // с этого момента прогрессивный скан больше не трогает selectedIndex
        _state.value = ready.copy(selectedIndex = index.coerceIn(0, ready.frames.lastIndex))
    }

    /**
     * «Выбрать кадр» — единственное место, где кадр декодируется честно: `OPTION_CLOSEST` на
     * `selectedTimeMs` (не приближённый кадр из [FrameScrubberUiState.Ready.frames]),
     * даунскейлится до 1280px по длинной стороне и сохраняется JPEG q=85 в `cacheDir/frames/`.
     * Результат отдаётся [onResult] — экран сам решает, что делать дальше (в NavHost —
     * положить в savedStateHandle и закрыть экран); заливка на сервер сюда не входит (A7, #101).
     */
    fun onConfirm(cardId: String, slot: FrameSlot, onResult: (FrameSelectionResult) -> Unit, onError: (String) -> Unit) {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        val source = frameSource ?: return
        if (ready.saving) return
        _state.value = ready.copy(saving = true)
        viewModelScope.launch {
            // Кадр в frames — приближённый (шаг FrameWindow.scanStepMs), в файл должен уйти
            // честный OPTION_CLOSEST; приближённый — только фолбэк, если точный декод не удался.
            val bitmap = source.exactFrameAt(ready.selectedTimeMs) ?: ready.previewBitmap
            if (bitmap == null) {
                _state.value = ready.copy(saving = false)
                onError("Не удалось получить кадр. Попробуйте другой момент.")
                return@launch
            }
            val savedPath = withContext(Dispatchers.IO) { saveJpeg(bitmap) }
            if (savedPath == null) {
                _state.value = ready.copy(saving = false)
                onError("Не удалось сохранить кадр на телефоне.")
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
            _state.value = ready.copy(saving = false)
            onResult(FrameSelectionResult(cardId = cardId, slot = slot, jpegPath = savedPath, selectedSeconds = selectedSeconds))
        }
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
        frameSource?.release()
        frameSource = null
    }

    private companion object {
        // Ширина кадров окна (#117, финальная версия) — крупнее старой ширины миниатюр
        // (320px), т.к. этот же список теперь рисует и большое превью, не только ленту.
        // ~TARGET_FRAME_COUNT кадров на этой ширине в RGB_565 — единицы мегабайт (см.
        // FrameWindow.scanStepMs и FrameSource docblock), не десятки.
        const val FRAME_WIDTH_PX = 540

        const val MAX_JPEG_LONG_SIDE_PX = 1280
        const val JPEG_QUALITY = 85
    }
}
