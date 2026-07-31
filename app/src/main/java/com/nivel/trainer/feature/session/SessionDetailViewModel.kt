package com.nivel.trainer.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.CardActionResult
import com.nivel.trainer.data.repository.InsightsRepository
import com.nivel.trainer.data.repository.InsightsResult
import com.nivel.trainer.data.repository.SessionCollectionsRepository
import com.nivel.trainer.data.repository.SessionDetailRepository
import com.nivel.trainer.domain.CardCollection
import com.nivel.trainer.domain.CollectionCardPreview
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.SessionOverview
import com.nivel.trainer.service.upload.AudioUploadScheduler
import com.nivel.trainer.service.upload.UploadStage
import com.nivel.trainer.service.upload.UploadStatusObserver
import com.nivel.trainer.ui.state.isNetworkError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
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
 * Лимиты полей правки карточки — как `maxLength` на `<input>`/`<textarea>` в вебе
 * (`EditAiCardModal`) и серверная валидация (`validateAiInsightCardPatch`). Ввод
 * жёстко обрезаем на этой длине же в [SessionDetailViewModel.onEditTitleChange]/
 * [SessionDetailViewModel.onEditBodyChange] — не только блокируем «Сохранить».
 */
const val CARD_TITLE_MAX_LENGTH = 80
const val CARD_BODY_MAX_LENGTH = 400

/**
 * Состояние bottom-sheet правки карточки (A2, #96, порт `EditAiCardModal`). `Closed` —
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
            get() = title.trim().length in 1..CARD_TITLE_MAX_LENGTH &&
                body.trim().length in 1..CARD_BODY_MAX_LENGTH &&
                tag in CARD_TAGS &&
                side in CARD_SIDES
    }
}

/**
 * Состояние шита «Добавить из библиотеки» (#78) — применение коллекции карточек
 * к текущей сессии. `Closed` → список коллекций → превью карточек выбранной →
 * (успех) закрывается и триггерит [SessionDetailViewModel.refresh].
 */
sealed interface LibrarySheetState {
    data object Closed : LibrarySheetState

    data class ListCollections(
        val collections: List<CardCollection> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    ) : LibrarySheetState

    data class PreviewCollection(
        val collection: CardCollection,
        val cards: List<CollectionCardPreview> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val applying: Boolean = false,
        val applyError: String? = null,
        val applied: Boolean = false,
    ) : LibrarySheetState
}

/**
 * UI-состояние экрана карточки тренировки (B6) + создание инсайтов (D2).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 */
data class SessionDetailUiState(
    val loading: Boolean = true,
    val overview: SessionOverview? = null,
    val error: String? = null,
    /** #71: рефреш поверх уже загруженного обзора (pull-to-refresh/возврат на экран). */
    val refreshing: Boolean = false,
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
    /**
     * G3 (#32): true = данные из Room-кэша (сеть была недоступна).
     * UI показывает оффлайн-баннер «Показаны данные из кэша».
     */
    val isOffline: Boolean = false,
    /** #78 — шит «Добавить из библиотеки» (применение коллекции карточек). */
    val librarySheet: LibrarySheetState = LibrarySheetState.Closed,
    /** A2 (#96) — шит правки draft-карточки (approve/reject/edit ревью). */
    val editSheet: EditSheetState = EditSheetState.Closed,
    /**
     * A2 (#96) — ошибка последнего действия approve/reject (баннер). Сбрасывается
     * тапом или следующим действием ревью; после ошибки экран перечитывает карточки
     * с сервера — карточка возвращается в исходный статус, если действие не дошло.
     */
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
    private val uploadStatusObserver: UploadStatusObserver,
    private val uploadScheduler: AudioUploadScheduler,
    private val collectionsRepository: SessionCollectionsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    private var sessionId: String? = null

    /**
     * Вызывается экраном с id из навигации. #71: больше не идемпотентна — вызов для уже
     * принятого id тоже перезапрашивает данные (возврат на экран, pull-to-refresh), только
     * без полноэкранного спиннера поверх уже показанного обзора (см. [refresh]).
     */
    fun load(sessionId: String) {
        val isNewId = this.sessionId != sessionId
        this.sessionId = sessionId
        if (isNewId) observeUpload(sessionId)
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

    /** Текущий явный (пользовательский) запрос обзора — см. [refresh]. */
    private var refreshJob: Job? = null

    /**
     * Тянет состояние сессии с сервера. Если обзор уже на экране — рефреш идёт незаметно
     * ([SessionDetailUiState.refreshing]); иначе — полноэкранный спиннер ([loading]).
     * Ошибку первичной загрузки показываем с «Повторить»; ошибку фонового рефреша — молча.
     * Источник вызова — пользователь (pull-to-refresh, возврат на экран, кнопка «Повторить»).
     *
     * Отменяет предыдущий незавершённый [refresh] — иначе более старый ответ, доехавший позже
     * нового, мог бы погасить индикатор раньше времени или затереть свежие данные устаревшими.
     */
    fun refresh() {
        val id = sessionId ?: return
        refreshJob?.cancel()
        val hasData = _uiState.value.overview != null
        _uiState.update { it.copy(loading = !hasData, refreshing = hasData, error = null) }
        refreshJob = viewModelScope.launch { fetchOverview(id, updateSpinners = true) }
    }

    /**
     * #71: тихий автополлинг (заливка/анализ, см. [SessionDetailScreen]) — обновляет данные
     * без [SessionDetailUiState.loading]/[refreshing]. Не трогает индикаторы даже по завершении —
     * иначе поллинг мог бы погасить спиннер параллельно идущего пользовательского [refresh],
     * а pull-to-refresh индикатор мигал бы каждые несколько секунд, пока идёт заливка или анализ.
     */
    fun pollRefresh() {
        val id = sessionId ?: return
        viewModelScope.launch { fetchOverview(id, updateSpinners = false) }
    }

    private suspend fun fetchOverview(id: String, updateSpinners: Boolean) {
        repository.getOverview(id)
            .onSuccess { overview ->
                // Если сервер уже довёл анализ до ready — снимаем прежнюю ошибку генерации.
                val clearGenError = overview.audio?.analysisStatus == "ready"
                _uiState.update { state ->
                    state.copy(
                        loading = if (updateSpinners) false else state.loading,
                        refreshing = if (updateSpinners) false else state.refreshing,
                        overview = overview,
                        error = if (updateSpinners) null else state.error,
                        generateError = if (clearGenError) null else state.generateError,
                        isOffline = overview.isStale, // G3: флаг кэша
                    )
                }
            }
            .onFailure { e ->
                if (!updateSpinners) return@onFailure // тихий автополлинг: не трогаем видимое состояние
                _uiState.update { state ->
                    state.copy(
                        loading = false,
                        refreshing = false,
                        error = if (state.overview == null) mapError(e) else state.error,
                    )
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
     *
     * A2 (#96): черновики теперь ревьюятся отдельно (см. [DraftReviewSection] на
     * экране) и не участвуют в drag-and-drop списке — как и на вебе, где reorder
     * доступен только approved-карточкам (`ApprovedCardsReorderable`). Индексы
     * [fromIndex]/[toIndex] приходят от UI относительно approved-подсписка; здесь
     * переставляем именно его, оставляя черновики на их исходных абсолютных позициях.
     */
    fun moveCard(fromIndex: Int, toIndex: Int) {
        val currentFull = _uiState.value.reorderedCards
            ?: _uiState.value.overview?.cards
            ?: return
        if (fromIndex == toIndex) return
        val approvedAbsoluteIndices = currentFull.indices.filter { currentFull[it].trainerStatus == "approved" }
        if (fromIndex !in approvedAbsoluteIndices.indices || toIndex !in approvedAbsoluteIndices.indices) return

        val approved = approvedAbsoluteIndices.map { currentFull[it] }.toMutableList()
        val card = approved.removeAt(fromIndex)
        approved.add(toIndex, card)

        val mutableFull = currentFull.toMutableList()
        approvedAbsoluteIndices.forEachIndexed { i, absoluteIndex -> mutableFull[absoluteIndex] = approved[i] }
        _uiState.update { it.copy(reorderedCards = mutableFull) }
    }

    /**
     * Фиксирует новый порядок на сервере после завершения drag-жеста.
     * Оптимистичный список уже применён — в случае ошибки сеть перечитаем через refresh().
     * A2: на сервер уходят id только approved-карточек — черновики не переупорядочиваются.
     */
    fun commitCardReorder() {
        val id = sessionId ?: return
        val cards = _uiState.value.reorderedCards ?: return
        val orderedIds = cards.filter { it.trainerStatus == "approved" }.map { it.id }
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

    // --- A2 (#96): ревью draft-карточек (одобрить / отклонить / редактировать) ---

    /** Id карточек с уже отправленным approve/reject — гард от дубликатов на быстрый двойной тап/свайп. */
    private val pendingCardActions = mutableSetOf<String>()

    /**
     * Одобрить карточку. UI (свайп-стек) оптимистично убирает её из локальной
     * очереди; здесь шлём действие на сервер и перечитываем карточки. При ошибке
     * показываем баннер и тоже перечитываем — сервер вернёт правду (карточка
     * останется/вернётся в drafts, свайп-стек пересинкается по актуальному списку).
     */
    fun approveCard(cardId: String) = runCardAction(cardId) { insightsRepository.approveCard(cardId) }

    /** Отклонить карточку (см. [approveCard]). */
    fun rejectCard(cardId: String) = runCardAction(cardId) { insightsRepository.rejectCard(cardId) }

    /** cardId != null: гардим от повторного тапа/свайпа по той же карточке, пока первый запрос летит. */
    private fun runCardAction(cardId: String? = null, action: suspend () -> CardActionResult) {
        if (cardId != null && !pendingCardActions.add(cardId)) return
        _uiState.update { it.copy(cardActionError = null) }
        viewModelScope.launch {
            try {
                when (val result = action()) {
                    is CardActionResult.Success -> refresh()
                    is CardActionResult.Failure -> {
                        _uiState.update { it.copy(cardActionError = result.message) }
                        refresh()
                    }
                }
            } finally {
                if (cardId != null) pendingCardActions.remove(cardId)
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

    /** Не закрываем во время отправки — иначе теряется введённый текст правки. */
    fun closeEditSheet() {
        val sheet = _uiState.value.editSheet
        if (sheet is EditSheetState.Open && sheet.submitting) return
        _uiState.update { it.copy(editSheet = EditSheetState.Closed) }
    }

    /** Обрезаем ввод по лимиту сразу — как `maxLength` на вебе, не только блокировка «Сохранить». */
    fun onEditTitleChange(value: String) =
        updateEditSheet { it.copy(title = value.take(CARD_TITLE_MAX_LENGTH), error = null) }

    fun onEditBodyChange(value: String) =
        updateEditSheet { it.copy(body = value.take(CARD_BODY_MAX_LENGTH), error = null) }
    fun onEditTagChange(value: String) = updateEditSheet { it.copy(tag = value, error = null) }
    fun onEditSideChange(value: String) = updateEditSheet { it.copy(side = value, error = null) }

    private fun updateEditSheet(transform: (EditSheetState.Open) -> EditSheetState.Open) {
        _uiState.update { state ->
            val sheet = state.editSheet
            if (sheet !is EditSheetState.Open) state else state.copy(editSheet = transform(sheet))
        }
    }

    /**
     * Сохранить правку. При успехе закрываем шит и перечитываем карточки — статус и
     * контент приезжают с сервера, а не остаются только в локальном стейте (acceptance).
     * При обрыве сети шит остаётся открытым с введённым текстом и текстом ошибки.
     */
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

    // --- #78: применение коллекции карточек к сессии ---

    /** Открывает шит и грузит список коллекций тренера. */
    fun openLibrarySheet() {
        _uiState.update { it.copy(librarySheet = LibrarySheetState.ListCollections()) }
        viewModelScope.launch {
            collectionsRepository.getCollections()
                .onSuccess { collections ->
                    _uiState.update { state ->
                        val sheet = state.librarySheet as? LibrarySheetState.ListCollections ?: return@update state
                        state.copy(librarySheet = sheet.copy(collections = collections, loading = false, error = null))
                    }
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        val sheet = state.librarySheet as? LibrarySheetState.ListCollections ?: return@update state
                        state.copy(librarySheet = sheet.copy(loading = false, error = mapError(e)))
                    }
                }
        }
    }

    fun dismissLibrarySheet() {
        val sheet = _uiState.value.librarySheet
        if (sheet is LibrarySheetState.PreviewCollection && sheet.applying) return // не закрываем во время применения
        _uiState.update { it.copy(librarySheet = LibrarySheetState.Closed) }
    }

    /** Тап по коллекции в списке — грузит превью её карточек перед применением. */
    fun selectCollection(collection: CardCollection) {
        _uiState.update { it.copy(librarySheet = LibrarySheetState.PreviewCollection(collection = collection)) }
        viewModelScope.launch {
            collectionsRepository.getCollectionCards(collection.id)
                .onSuccess { cards ->
                    _uiState.update { state ->
                        val sheet = state.librarySheet as? LibrarySheetState.PreviewCollection ?: return@update state
                        state.copy(librarySheet = sheet.copy(cards = cards, loading = false, error = null))
                    }
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        val sheet = state.librarySheet as? LibrarySheetState.PreviewCollection ?: return@update state
                        state.copy(librarySheet = sheet.copy(loading = false, error = mapError(e)))
                    }
                }
        }
    }

    /**
     * «Другая коллекция» — назад к списку. Список короткоживущий и дешёвый — проще
     * перезапросить заново, чем таскать его отдельно в состоянии превью ради одного
     * возврата назад; [openLibrarySheet] сама сбрасывает состояние на `ListCollections`.
     */
    fun backToCollectionsList() = openLibrarySheet()

    /** Применяет выбранную коллекцию к текущей сессии; при успехе перечитывает карточки. */
    fun applySelectedCollection() {
        val id = sessionId ?: return
        val sheet = _uiState.value.librarySheet as? LibrarySheetState.PreviewCollection ?: return
        if (sheet.applying) return

        _uiState.update { it.copy(librarySheet = sheet.copy(applying = true, applyError = null)) }
        viewModelScope.launch {
            collectionsRepository.applyCollection(sheet.collection.id, id)
                .onSuccess {
                    _uiState.update { state ->
                        val current = state.librarySheet as? LibrarySheetState.PreviewCollection ?: return@update state
                        state.copy(librarySheet = current.copy(applying = false, applied = true))
                    }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        val current = state.librarySheet as? LibrarySheetState.PreviewCollection ?: return@update state
                        state.copy(librarySheet = current.copy(applying = false, applyError = mapError(e)))
                    }
                }
        }
    }

    private fun mapError(e: Throwable): String = when {
        // G3 (#32): сетевую ошибку показываем понятной «нет связи» формулировкой.
        isNetworkError(e) -> "Нет подключения к интернету. Проверьте сеть и повторите."
        // #78: чужая коллекция → 403 — понятный текст вместо «HTTP 403 Forbidden».
        e is HttpException && e.code() == 403 -> "Нет доступа к этой коллекции."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
    }
}
