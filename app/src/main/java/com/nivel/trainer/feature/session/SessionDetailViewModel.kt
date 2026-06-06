package com.nivel.trainer.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.InsightsRepository
import com.nivel.trainer.data.repository.InsightsResult
import com.nivel.trainer.data.repository.SessionDetailRepository
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.SessionOverview
import com.nivel.trainer.service.upload.AudioUploadScheduler
import com.nivel.trainer.service.upload.UploadStage
import com.nivel.trainer.service.upload.UploadStatusObserver
import com.nivel.trainer.ui.state.isNetworkError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние шита «Вставить инсайты» (D2). `Closed` — шит скрыт; `Open` — открыт
 * с текстом / индикатором отправки / ошибкой парсинга.
 */
sealed interface PasteSheetState {
    data object Closed : PasteSheetState
    data class Open(
        val markdown: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
    ) : PasteSheetState
}

/**
 * UI-состояние экрана карточки тренировки (B6) + создание инсайтов (D2).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 */
data class SessionDetailUiState(
    val loading: Boolean = true,
    val overview: SessionOverview? = null,
    val error: String? = null,
    /** D2 — шит ручной вставки инсайтов. */
    val pasteSheet: PasteSheetState = PasteSheetState.Closed,
    /** D2 — идёт авто-генерация (LLM инлайн), показываем спиннер-статус. */
    val generating: Boolean = false,
    /** D2 — ошибка авто-генерации (показываем с кнопкой «Повторить анализ»). */
    val generateError: String? = null,
    /**
     * C5 — стадия фоновой заливки записи (запись→заливка%). Питается из WorkManager
     * через [UploadStatusObserver]. Видима, пока на сервере ещё нет транскрипта
     * (`overview.audio == null`); дальше пайплайн ведёт статус транскрипта (B6).
     */
    val uploadStage: UploadStage = UploadStage.None,
    /** D5 (#23): идёт вызов review-complete (блокируем повторный тап). */
    val completingReview: Boolean = false,
    /** D5 (#23): ошибка завершения разбора (показываем снэкбар/текст). */
    val completeReviewError: String? = null,
    /**
     * D4 (#22): текущий локальный порядок карточек во время drag-and-drop.
     * null — показываем `overview.cards` без изменений.
     * non-null — оптимистично переупорядоченный список (пользователь тащит палец).
     */
    val reorderedCards: List<InsightCard>? = null,
)

/**
 * ViewModel экрана карточки тренировки (B6) и создания инсайтов (D2).
 * Паттерн как у профиля ученика (B5): Hilt-инъекция репозиториев, единый
 * [StateFlow], загрузка/действия через корутину.
 *
 * Источник правды — сервер. Чтение — [SessionDetailRepository.getOverview];
 * запись инсайтов (paste/generate) — [InsightsRepository], после успеха
 * перечитываем карточки через [refresh].
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: SessionDetailRepository,
    private val insightsRepository: InsightsRepository,
    private val uploadStatusObserver: UploadStatusObserver,
    private val uploadScheduler: AudioUploadScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    private var sessionId: String? = null

    /**
     * Вызывается экраном с id из навигации. Идемпотентна: для уже принятого id
     * повторные вызовы (recompose, возврат на экран) не перезапускают загрузку.
     * Повтор после ошибки — через [refresh] (кнопка «Повторить»).
     */
    fun load(sessionId: String) {
        if (this.sessionId == sessionId) return
        this.sessionId = sessionId
        observeUpload(sessionId)
        refresh()
    }

    /**
     * C5 — подписка на стадию заливки записи (WorkManager). Живёт пока жив ViewModel;
     * UI показывает прогресс заливки до появления транскрипта на сервере.
     */
    private fun observeUpload(sessionId: String) {
        viewModelScope.launch {
            uploadStatusObserver.observe(sessionId).collect { stage ->
                _uiState.update { it.copy(uploadStage = stage) }
            }
        }
    }

    /** C5 — ручной повтор заливки после провала (берёт file_path из стадии Failed). */
    fun retryUpload() {
        val id = sessionId ?: return
        val stage = _uiState.value.uploadStage as? UploadStage.Failed ?: return
        val filePath = stage.filePath ?: return
        uploadScheduler.retry(id, filePath)
    }

    /** Тянет состояние сессии с сервера; ошибку показываем с возможностью повтора. */
    fun refresh() {
        val id = sessionId ?: return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.getOverview(id)
                .onSuccess { overview ->
                    // Если сервер уже довёл анализ до ready — снимаем прежнюю ошибку генерации.
                    val clearGenError = overview.audio?.analysisStatus == "ready"
                    _uiState.update {
                        it.copy(
                            loading = false,
                            overview = overview,
                            error = null,
                            generateError = if (clearGenError) null else it.generateError,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = mapError(e)) }
                }
        }
    }

    // --- D2: ручная вставка инсайтов ---

    fun openPasteSheet() {
        _uiState.update { it.copy(pasteSheet = PasteSheetState.Open()) }
    }

    fun closePasteSheet() {
        val sheet = _uiState.value.pasteSheet
        if (sheet is PasteSheetState.Open && sheet.submitting) return // не закрываем во время отправки
        _uiState.update { it.copy(pasteSheet = PasteSheetState.Closed) }
    }

    fun onPasteMarkdownChange(text: String) {
        _uiState.update { state ->
            val sheet = state.pasteSheet
            if (sheet !is PasteSheetState.Open) state
            else state.copy(pasteSheet = sheet.copy(markdown = text, error = null))
        }
    }

    /** Отправляет вставленный markdown; при успехе закрывает шит и перечитывает карточки. */
    fun submitPaste() {
        val id = sessionId ?: return
        val sheet = _uiState.value.pasteSheet as? PasteSheetState.Open ?: return
        val markdown = sheet.markdown.trim()
        if (markdown.isEmpty() || sheet.submitting) return

        _uiState.update { it.copy(pasteSheet = sheet.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            when (val result = insightsRepository.pasteInsights(id, markdown)) {
                is InsightsResult.Success -> {
                    _uiState.update { it.copy(pasteSheet = PasteSheetState.Closed) }
                    refresh()
                }
                is InsightsResult.Failure -> _uiState.update { state ->
                    val open = state.pasteSheet as? PasteSheetState.Open ?: return@update state
                    state.copy(pasteSheet = open.copy(submitting = false, error = result.message))
                }
            }
        }
    }

    // --- D5: завершить разбор ---

    /**
     * Фиксирует финальное ревью тренера (D5, #23). Атомарный guard на сервере:
     * повторный тап когда `trainerReviewCompleted=true` — безопасный no-op.
     * При успехе перечитываем обзор — кнопка перейдёт в состояние «уже завершено».
     */
    fun completeReview() {
        val id = sessionId ?: return
        if (_uiState.value.completingReview) return
        _uiState.update { it.copy(completingReview = true, completeReviewError = null) }
        viewModelScope.launch {
            repository.completeReview(id)
                .onSuccess {
                    _uiState.update { it.copy(completingReview = false) }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            completingReview = false,
                            completeReviewError = mapError(e),
                        )
                    }
                }
        }
    }

    fun dismissCompleteReviewError() {
        _uiState.update { it.copy(completeReviewError = null) }
    }

    // --- D4: drag-and-drop переупорядочивание карточек ---

    /**
     * Оптимистично меняет порядок карточек локально во время перетаскивания.
     * Вызывается из UI на каждый onDragMove с новым индексом целевого элемента.
     */
    fun moveCard(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value.reorderedCards
            ?: _uiState.value.overview?.cards
            ?: return
        if (fromIndex == toIndex) return
        val mutable = current.toMutableList()
        val card = mutable.removeAt(fromIndex)
        mutable.add(toIndex, card)
        _uiState.update { it.copy(reorderedCards = mutable) }
    }

    /**
     * Фиксирует новый порядок на сервере после завершения drag-жеста.
     * Оптимистичный список уже применён — в случае ошибки сеть перечитаем через refresh().
     */
    fun commitCardReorder() {
        val id = sessionId ?: return
        val cards = _uiState.value.reorderedCards ?: return
        val orderedIds = cards.map { it.id }
        // Применяем reorderedCards в overview сразу, чтобы UI не мигал.
        val overview = _uiState.value.overview
        if (overview != null) {
            _uiState.update { it.copy(overview = overview.copy(cards = cards), reorderedCards = null) }
        } else {
            _uiState.update { it.copy(reorderedCards = null) }
        }
        viewModelScope.launch {
            repository.reorderCards(id, orderedIds)
                .onFailure { refresh() } // при ошибке перечитываем с сервера
        }
    }

    // --- D2: авто-генерация инсайтов ---

    /** Запускает (или перезапускает) авто-анализ транскрипта; при успехе перечитывает карточки. */
    fun generateInsights() {
        val id = sessionId ?: return
        if (_uiState.value.generating) return

        _uiState.update { it.copy(generating = true, generateError = null) }
        viewModelScope.launch {
            when (val result = insightsRepository.generateInsights(id)) {
                is InsightsResult.Success -> {
                    _uiState.update { it.copy(generating = false, generateError = null) }
                    refresh()
                }
                is InsightsResult.Failure ->
                    _uiState.update { it.copy(generating = false, generateError = result.message) }
            }
        }
    }

    private fun mapError(e: Throwable): String = when {
        // G3 (#32): сетевую ошибку показываем понятной «нет связи» формулировкой.
        isNetworkError(e) -> "Нет подключения к интернету. Проверьте сеть и повторите."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
    }
}
