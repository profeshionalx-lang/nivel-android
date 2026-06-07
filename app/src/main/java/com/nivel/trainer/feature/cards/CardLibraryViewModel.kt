package com.nivel.trainer.feature.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.CardLibraryRepository
import com.nivel.trainer.domain.CardCollection
import com.nivel.trainer.domain.CardLibraryStudent
import com.nivel.trainer.domain.CardTemplate
import com.nivel.trainer.domain.StudentSession
import com.nivel.trainer.ui.state.isNetworkError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Вкладки экрана библиотеки (как сегмент-табы Cards/Collections в вебе). */
enum class CardTab { CARDS, COLLECTIONS }

/** Что применяем в шите применения: одиночный шаблон или целую коллекцию. */
enum class ApplyMode { TEMPLATE, COLLECTION }

/**
 * Состояние шита применения карточки/коллекции к ученику (порт web `ApplyCardSheet`
 * и блока «Apply collection»): шаг выбора ученика → выбор сессии → применить.
 */
data class ApplySheetState(
    val visible: Boolean = false,
    val mode: ApplyMode = ApplyMode.TEMPLATE,
    /** templateKey (для шаблона) или collectionId (для коллекции). */
    val targetId: String = "",
    val title: String? = null,
    val selectedStudent: CardLibraryStudent? = null,
    val sessions: List<StudentSession> = emptyList(),
    val loadingSessions: Boolean = false,
    val submitting: Boolean = false,
    val successMessage: String? = null,
    val error: String? = null,
)

/**
 * Состояние шита выбора коллекций для карточки (порт bookmark-поповера web
 * `LibraryCardItem`): добавить/убрать шаблон в коллекциях галочками. Mobile-first —
 * вместо поповера нижний шит.
 */
data class CollectionPickerState(
    val visible: Boolean = false,
    val templateKey: String = "",
    val templateTitle: String? = null,
)

/**
 * UI-состояние экрана библиотеки карточек (E4). Источник правды — сервер; точечный
 * экран, без Room-кэша. Фильтры/поиск/коллекции — порт локального состояния веб-
 * `CardsLibrary` (мобильная раскладка).
 */
data class CardLibraryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val templates: List<CardTemplate> = emptyList(),
    val students: List<CardLibraryStudent> = emptyList(),
    val collections: List<CardCollection> = emptyList(),
    val tab: CardTab = CardTab.CARDS,
    val search: String = "",
    /** Активный фильтр темы (тег); null — все темы. */
    val tagFilter: String? = null,
    /** Активный фильтр статуса: "all" | approved | draft | rejected. */
    val statusFilter: String = "all",
    /** Имя новой коллекции (поле создания на вкладке Collections). */
    val newCollectionName: String = "",
    /** Ошибка фонового действия (apply/коллекции) — показываем баннером. */
    val actionError: String? = null,
    val applySheet: ApplySheetState = ApplySheetState(),
    val collectionPicker: CollectionPickerState = CollectionPickerState(),
) {
    /** Отфильтрованные шаблоны (поиск по title/body + тег + статус), как в вебе. */
    val filteredTemplates: List<CardTemplate>
        get() {
            val q = search.trim().lowercase()
            return templates.filter { t ->
                if (q.isNotEmpty() &&
                    t.title?.lowercase()?.contains(q) != true &&
                    t.body?.lowercase()?.contains(q) != true
                ) return@filter false
                if (tagFilter != null && !t.tags.contains(tagFilter)) return@filter false
                if (statusFilter != "all" && t.trainerStatus != statusFilter) return@filter false
                true
            }
        }

    val hasFilters: Boolean
        get() = search.isNotBlank() || tagFilter != null || statusFilter != "all"
}

/**
 * ViewModel экрана библиотеки карточек (E4, #27). Паттерн как у
 * [com.nivel.trainer.feature.student.StudentProfileViewModel]: Hilt-инъекция
 * репозитория, единый [StateFlow], загрузка через корутину.
 *
 * Ведение коллекций (добавить/убрать карточку, создать) обновляется оптимистично
 * (как `startTransition` + локальный setState в вебе); при сбое — откат + баннер.
 */
@HiltViewModel
class CardLibraryViewModel @Inject constructor(
    private val repository: CardLibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CardLibraryUiState())
    val uiState: StateFlow<CardLibraryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.getLibrary()
                .onSuccess { lib ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            templates = lib.templates,
                            students = lib.students,
                            collections = lib.collections,
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = mapError(e)) } }
        }
    }

    // --- Табы / фильтры ---

    fun selectTab(tab: CardTab) = _uiState.update { it.copy(tab = tab) }

    fun onSearchChange(text: String) = _uiState.update { it.copy(search = text) }

    fun onTagFilter(tag: String?) = _uiState.update { it.copy(tagFilter = tag) }

    fun onStatusFilter(status: String) = _uiState.update { it.copy(statusFilter = status) }

    fun resetFilters() = _uiState.update {
        it.copy(search = "", tagFilter = null, statusFilter = "all")
    }

    fun dismissActionError() = _uiState.update { it.copy(actionError = null) }

    // --- Создание коллекции ---

    fun onNewCollectionNameChange(text: String) = _uiState.update { it.copy(newCollectionName = text) }

    fun createCollection() {
        val name = _uiState.value.newCollectionName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createCollection(name)
                .onSuccess { id ->
                    _uiState.update { state ->
                        state.copy(
                            newCollectionName = "",
                            collections = listOf(
                                CardCollection(
                                    id = id,
                                    name = name,
                                    createdAt = null,
                                    cardCount = 0,
                                    templateIds = emptyList(),
                                ),
                            ) + state.collections,
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(actionError = mapError(e)) } }
        }
    }

    // --- Ведение коллекций (bookmark-шит карточки) ---

    fun openCollectionPicker(template: CardTemplate) = _uiState.update {
        it.copy(
            collectionPicker = CollectionPickerState(
                visible = true,
                templateKey = template.key,
                templateTitle = template.title,
            ),
        )
    }

    fun dismissCollectionPicker() = _uiState.update {
        it.copy(collectionPicker = CollectionPickerState())
    }

    /** Переключает принадлежность шаблона коллекции (оптимистично, с откатом при сбое). */
    fun toggleCardInCollection(collectionId: String, templateKey: String) {
        val collection = _uiState.value.collections.firstOrNull { it.id == collectionId } ?: return
        val inCollection = collection.templateIds.contains(templateKey)
        // Оптимистичное обновление.
        _uiState.update { state -> state.copy(collections = state.collections.map { c -> applyToggle(c, collectionId, templateKey, !inCollection) }) }
        viewModelScope.launch {
            val result = if (inCollection) {
                repository.removeCardFromCollection(collectionId, templateKey)
            } else {
                repository.addCardToCollection(collectionId, templateKey)
            }
            result.onFailure { e ->
                // Откат изменения + баннер.
                _uiState.update { state ->
                    state.copy(
                        collections = state.collections.map { c -> applyToggle(c, collectionId, templateKey, inCollection) },
                        actionError = mapError(e),
                    )
                }
            }
        }
    }

    private fun applyToggle(
        c: CardCollection,
        collectionId: String,
        templateKey: String,
        shouldContain: Boolean,
    ): CardCollection {
        if (c.id != collectionId) return c
        val has = c.templateIds.contains(templateKey)
        return when {
            shouldContain && !has -> c.copy(
                templateIds = c.templateIds + templateKey,
                cardCount = c.cardCount + 1,
            )
            !shouldContain && has -> c.copy(
                templateIds = c.templateIds.filterNot { it == templateKey },
                cardCount = (c.cardCount - 1).coerceAtLeast(0),
            )
            else -> c
        }
    }

    /** Убрать карточку из коллекции на вкладке Collections (как крестик в вебе). */
    fun removeCardFromCollection(collectionId: String, templateKey: String) =
        toggleCardInCollection(collectionId, templateKey)

    // --- Применение шаблона / коллекции к ученику ---

    fun startApplyTemplate(template: CardTemplate) = _uiState.update {
        it.copy(
            applySheet = ApplySheetState(
                visible = true,
                mode = ApplyMode.TEMPLATE,
                targetId = template.key,
                title = template.title,
            ),
        )
    }

    fun startApplyCollection(collection: CardCollection) = _uiState.update {
        it.copy(
            applySheet = ApplySheetState(
                visible = true,
                mode = ApplyMode.COLLECTION,
                targetId = collection.id,
                title = collection.name,
            ),
        )
    }

    fun dismissApplySheet() = _uiState.update { it.copy(applySheet = ApplySheetState()) }

    /** Назад к выбору ученика (как «Другой ученик» в вебе). */
    fun resetApplyStudent() = _uiState.update {
        it.copy(applySheet = it.applySheet.copy(selectedStudent = null, sessions = emptyList(), error = null))
    }

    /** Выбрать ученика → подгрузить его сессии. */
    fun selectStudentForApply(student: CardLibraryStudent) {
        _uiState.update {
            it.copy(applySheet = it.applySheet.copy(selectedStudent = student, loadingSessions = true, error = null))
        }
        viewModelScope.launch {
            repository.getStudentSessions(student.id)
                .onSuccess { sessions ->
                    _uiState.update {
                        it.copy(applySheet = it.applySheet.copy(sessions = sessions, loadingSessions = false))
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(applySheet = it.applySheet.copy(loadingSessions = false, error = mapError(e)))
                    }
                }
        }
    }

    /** Применить выбранный шаблон/коллекцию к выбранной сессии. */
    fun applyToSession(sessionId: String) {
        val sheet = _uiState.value.applySheet
        if (sheet.submitting) return
        _uiState.update { it.copy(applySheet = it.applySheet.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            when (sheet.mode) {
                ApplyMode.TEMPLATE -> repository.applyTemplate(sessionId, sheet.targetId)
                    .onSuccess { onApplySuccess("Карточка добавлена в сессию") }
                    .onFailure { e -> onApplyFailure(e) }

                ApplyMode.COLLECTION -> repository.applyCollection(sheet.targetId, sessionId)
                    .onSuccess { applied -> onApplySuccess("Добавлено $applied карточек") }
                    .onFailure { e -> onApplyFailure(e) }
            }
        }
    }

    private fun onApplySuccess(message: String) = _uiState.update {
        it.copy(applySheet = it.applySheet.copy(submitting = false, successMessage = message, error = null))
    }

    private fun onApplyFailure(e: Throwable) = _uiState.update {
        it.copy(applySheet = it.applySheet.copy(submitting = false, error = mapError(e)))
    }

    private fun mapError(e: Throwable): String = when {
        isNetworkError(e) -> "Нет подключения к интернету. Проверьте сеть и повторите."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
    }
}
