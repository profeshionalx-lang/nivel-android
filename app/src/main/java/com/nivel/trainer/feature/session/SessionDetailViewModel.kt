package com.nivel.trainer.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.CardActionResult
import com.nivel.trainer.data.repository.InsightsRepository
import com.nivel.trainer.data.repository.InsightsResult
import com.nivel.trainer.data.repository.SessionDetailRepository
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.SessionOverview
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

/** Допустимые темы/стороны карточки — один-в-один с вебом (`EditAiCardModal`). */
val CARD_TAGS = listOf("техника", "тактика", "физика", "менталка")
val CARD_SIDES = listOf("защита", "атака")

/**
 * Состояние bottom-sheet правки карточки (D3, порт `EditAiCardModal`). `Closed` —
 * скрыт; `Open` — открыт с редактируемыми полями, индикатором отправки и ошибкой.
 * `cardId` нужен для PATCH; `quote` показываем read-only (как в вебе).
 */
sealed interface EditSheetState {
    data object Closed : EditSheetState
    data class Open(
        val cardId: String,
        val title: String,
        val body: String,
        val tag: String,
        val side: String,
        val quote: String?,
        val submitting: Boolean = false,
        val error: String? = null,
    ) : EditSheetState {
        /** Валидность как в вебе: title 1..80, body 1..400, tag/side из наборов. */
        val isValid: Boolean
            get() = title.trim().length in 1..80 &&
                body.trim().length in 1..400 &&
                tag in CARD_TAGS &&
                side in CARD_SIDES
    }
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
    /** D3 — шит правки карточки. */
    val editSheet: EditSheetState = EditSheetState.Closed,
    /** D3 — ошибка действия approve/reject (баннер; сбрасывается тапом/следующим действием). */
    val cardActionError: String? = null,
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
        refresh()
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

    // --- D3: ревью draft-карточек (одобрить / отклонить / редактировать) ---

    /**
     * Одобрить карточку. UI оптимистично убирает её из локальной очереди; здесь
     * шлём действие на сервер и перечитываем карточки. При ошибке показываем
     * баннер и тоже перечитываем — сервер вернёт правду (карточка снова в drafts).
     */
    fun approveCard(cardId: String) = runCardAction { insightsRepository.approveCard(cardId) }

    /** Отклонить карточку (см. [approveCard]). */
    fun rejectCard(cardId: String) = runCardAction { insightsRepository.rejectCard(cardId) }

    private fun runCardAction(action: suspend () -> CardActionResult) {
        _uiState.update { it.copy(cardActionError = null) }
        viewModelScope.launch {
            when (val result = action()) {
                is CardActionResult.Success -> refresh()
                is CardActionResult.Failure -> {
                    _uiState.update { it.copy(cardActionError = result.message) }
                    refresh()
                }
            }
        }
    }

    fun dismissCardActionError() {
        _uiState.update { it.copy(cardActionError = null) }
    }

    /** Открыть шит правки: начальные значения как в вебе (`EditAiCardModal`). */
    fun openEditSheet(card: InsightCard) {
        val title = card.title?.takeIf { it.isNotBlank() } ?: card.frontText.orEmpty()
        val body = card.body?.takeIf { it.isNotBlank() } ?: card.contextText.orEmpty()
        val tag = card.tags.getOrNull(0)?.takeIf { it in CARD_TAGS } ?: CARD_TAGS.first()
        val side = card.tags.getOrNull(1)?.takeIf { it in CARD_SIDES } ?: "атака"
        _uiState.update {
            it.copy(
                cardActionError = null,
                editSheet = EditSheetState.Open(
                    cardId = card.id,
                    title = title,
                    body = body,
                    tag = tag,
                    side = side,
                    quote = card.quote?.takeIf { q -> q.isNotBlank() },
                ),
            )
        }
    }

    fun closeEditSheet() {
        val sheet = _uiState.value.editSheet
        if (sheet is EditSheetState.Open && sheet.submitting) return // не закрываем во время отправки
        _uiState.update { it.copy(editSheet = EditSheetState.Closed) }
    }

    fun onEditTitleChange(value: String) = updateEditSheet { it.copy(title = value, error = null) }
    fun onEditBodyChange(value: String) = updateEditSheet { it.copy(body = value, error = null) }
    fun onEditTagChange(value: String) = updateEditSheet { it.copy(tag = value, error = null) }
    fun onEditSideChange(value: String) = updateEditSheet { it.copy(side = value, error = null) }

    private fun updateEditSheet(transform: (EditSheetState.Open) -> EditSheetState.Open) {
        _uiState.update { state ->
            val sheet = state.editSheet
            if (sheet !is EditSheetState.Open) state else state.copy(editSheet = transform(sheet))
        }
    }

    /** Сохранить правку. При успехе закрываем шит и перечитываем карточки. */
    fun submitEdit() {
        val sheet = _uiState.value.editSheet as? EditSheetState.Open ?: return
        if (sheet.submitting || !sheet.isValid) return

        _uiState.update { it.copy(editSheet = sheet.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val result = insightsRepository.updateCard(
                cardId = sheet.cardId,
                title = sheet.title.trim(),
                body = sheet.body.trim(),
                tag = sheet.tag,
                side = sheet.side,
            )
            when (result) {
                is CardActionResult.Success -> {
                    _uiState.update { it.copy(editSheet = EditSheetState.Closed) }
                    refresh()
                }
                is CardActionResult.Failure -> _uiState.update { state ->
                    val open = state.editSheet as? EditSheetState.Open ?: return@update state
                    state.copy(editSheet = open.copy(submitting = false, error = result.message))
                }
            }
        }
    }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
}
