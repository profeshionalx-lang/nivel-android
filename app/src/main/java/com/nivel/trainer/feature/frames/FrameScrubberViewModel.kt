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
        val buildingFilmstrip: Boolean = true,
        val buildProgress: Pair<Int, Int>? = null,
        val previewBitmap: Bitmap? = null,
        val previewLoading: Boolean = false,
        val expanded: Boolean = false,
        val saving: Boolean = false,
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
 * Память/декодеры: [job] (плёнка) живёт в [viewModelScope] — [onCleared] отменяет его
 * структурной конкурентностью, `finally` в [FrameSource] отпускает `MediaMetadataRetriever`
 * при отмене так же, как и при обычном завершении. Уход с экрана посреди построения
 * плёнки не оставляет «горячих» декодеров (acceptance issue #100).
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
    private var filmstripJob: Job? = null
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
        filmstripJob?.cancel()
        _state.value = FrameScrubberUiState.Ready(
            windowStartMs = window.first,
            windowEndMs = window.last,
            selectedTimeMs = initialSelection,
            buildingFilmstrip = true,
            expanded = expanded,
        )
        requestPreview(initialSelection)
        val source = frameSource ?: return
        filmstripJob = viewModelScope.launch {
            val built = mutableListOf<FilmstripFrame>()
            source.buildFilmstrip(
                startMs = window.first,
                endMs = window.last,
                stepMs = FrameWindow.THUMBNAIL_STEP_MS,
                thumbnailWidthPx = THUMBNAIL_WIDTH_PX,
            ) { doneCount, total ->
                _state.update { current ->
                    if (current !is FrameScrubberUiState.Ready) return@update current
                    current.copy(buildProgress = doneCount to total)
                }
            }.let { built.addAll(it) }
            _state.update { current ->
                if (current !is FrameScrubberUiState.Ready) return@update current
                current.copy(filmstrip = built, buildingFilmstrip = false, buildProgress = null)
            }
        }
    }

    /** Тап по миниатюре плёнки — сразу точный тайминг этого кадра (issue: должен совпасть с превью). */
    fun onThumbnailSelected(timeMs: Long) = selectTime(timeMs)

    /** Слайдер точной подстройки — тот же путь выбора, просто источник времени другой. */
    fun onSliderChanged(timeMs: Long) = selectTime(timeMs)

    private fun selectTime(timeMs: Long) {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        val clamped = timeMs.coerceIn(ready.windowStartMs, ready.windowEndMs)
        _state.value = ready.copy(selectedTimeMs = clamped)
        requestPreview(clamped)
    }

    private fun requestPreview(timeMs: Long) {
        val source = frameSource ?: return
        previewJob?.cancel()
        _state.update { current -> if (current is FrameScrubberUiState.Ready) current.copy(previewLoading = true) else current }
        previewJob = viewModelScope.launch {
            val bitmap = source.exactFrameAt(timeMs)
            _state.update { current ->
                if (current !is FrameScrubberUiState.Ready || current.selectedTimeMs != timeMs) return@update current
                current.copy(previewBitmap = bitmap, previewLoading = false)
            }
        }
    }

    /**
     * «Выбрать кадр» — декодирует точный кадр на `selectedTimeMs` (тот же `OPTION_CLOSEST`,
     * что и превью — см. [FrameSource.exactFrameAt]), даунскейлит до 1280px по длинной
     * стороне и сохраняет JPEG q=85 в `cacheDir/frames/`. Результат отдаётся [onResult] —
     * экран сам решает, что делать дальше (в NavHost — положить в savedStateHandle и
     * закрыть экран); заливка на сервер сюда не входит (A7, #101).
     */
    fun onConfirm(cardId: String, slot: FrameSlot, onResult: (FrameSelectionResult) -> Unit, onError: (String) -> Unit) {
        val ready = _state.value as? FrameScrubberUiState.Ready ?: return
        val source = frameSource ?: return
        if (ready.saving) return
        _state.value = ready.copy(saving = true)
        viewModelScope.launch {
            val bitmap = ready.previewBitmap ?: source.exactFrameAt(ready.selectedTimeMs)
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
        filmstripJob?.cancel()
        previewJob?.cancel()
        frameSource?.release()
        frameSource = null
    }

    private companion object {
        const val THUMBNAIL_WIDTH_PX = 320
        const val MAX_JPEG_LONG_SIDE_PX = 1280
        const val JPEG_QUALITY = 85
    }
}
