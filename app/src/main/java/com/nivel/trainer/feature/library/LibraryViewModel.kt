package com.nivel.trainer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.LibraryRepository
import com.nivel.trainer.domain.LibraryItem
import com.nivel.trainer.ui.state.isNetworkError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { SKILLS, EXERCISES }

/** Состояние bottom-sheet создания навыка/упражнения. */
sealed interface CreateLibraryItemState {
    data object Closed : CreateLibraryItemState

    data class Form(
        val nameRu: String = "",
        val nameEn: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
    ) : CreateLibraryItemState
}

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.SKILLS,
    val query: String = "",
    val skills: List<LibraryItem> = emptyList(),
    val exercises: List<LibraryItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val createSheet: CreateLibraryItemState = CreateLibraryItemState.Closed,
) {
    /** Список активной вкладки — списки не шарятся между «Навыки»/«Упражнения». */
    val items: List<LibraryItem> get() = if (tab == LibraryTab.SKILLS) skills else exercises

    /** Пусто именно из-за поиска (не «начните вводить» — сервер отдаёт первые 50 и без q). */
    val isEmpty: Boolean get() = items.isEmpty() && !loading && error == null
}

/**
 * ViewModel экрана «Библиотека» (E6, #77): поиск + создание навыков/упражнений
 * (`GET/POST /api/v1/skills`/`/exercises`, NIVEL#225). Дебаунс поиска 300мс —
 * job-based (как `loadJob` в [com.nivel.trainer.feature.transcript.TranscriptViewModel]),
 * а не `Flow.debounce`, чтобы не тащить в модуль лишнюю зависимость на пустом месте.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /** Первая загрузка вкладки — сразу, без дебаунса (пустой запрос = первые 50 по алфавиту). */
    fun load() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(_uiState.value.query) }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            search(value)
        }
    }

    fun selectTab(tab: LibraryTab) {
        if (_uiState.value.tab == tab) return
        _uiState.update { it.copy(tab = tab) }
        load()
    }

    private suspend fun search(query: String) {
        val tab = _uiState.value.tab
        _uiState.update { it.copy(loading = true, error = null) }
        val result = when (tab) {
            LibraryTab.SKILLS -> repository.searchSkills(query)
            LibraryTab.EXERCISES -> repository.searchExercises(query)
        }
        result
            .onSuccess { items ->
                _uiState.update { state ->
                    when (tab) {
                        LibraryTab.SKILLS -> state.copy(skills = items, loading = false)
                        LibraryTab.EXERCISES -> state.copy(exercises = items, loading = false)
                    }
                }
            }
            .onFailure { e -> _uiState.update { it.copy(loading = false, error = mapError(e)) } }
    }

    // --- Создание (bottom-sheet) ---

    fun openCreateSheet() = _uiState.update { it.copy(createSheet = CreateLibraryItemState.Form()) }

    fun closeCreateSheet() = _uiState.update { it.copy(createSheet = CreateLibraryItemState.Closed) }

    fun onCreateNameRuChange(value: String) = updateForm { it.copy(nameRu = value, error = null) }

    fun onCreateNameEnChange(value: String) = updateForm { it.copy(nameEn = value, error = null) }

    /** Создаёт элемент активной вкладки; при успехе добавляет его в начало списка и закрывает лист. */
    fun submitCreate() {
        val sheet = _uiState.value.createSheet
        if (sheet !is CreateLibraryItemState.Form) return
        val nameRu = sheet.nameRu.trim()
        if (nameRu.isBlank() || sheet.submitting) return

        _uiState.update { it.copy(createSheet = sheet.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val tab = _uiState.value.tab
            val nameEn = sheet.nameEn.trim().ifBlank { null }
            val result = when (tab) {
                LibraryTab.SKILLS -> repository.createSkill(nameRu, nameEn)
                LibraryTab.EXERCISES -> repository.createExercise(nameRu, nameEn)
            }
            result
                .onSuccess { item ->
                    _uiState.update { state ->
                        val updated = when (tab) {
                            LibraryTab.SKILLS -> state.copy(skills = prepend(item, state.skills))
                            LibraryTab.EXERCISES -> state.copy(exercises = prepend(item, state.exercises))
                        }
                        updated.copy(createSheet = CreateLibraryItemState.Closed)
                    }
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        val current = state.createSheet
                        if (current is CreateLibraryItemState.Form) {
                            state.copy(createSheet = current.copy(submitting = false, error = mapError(e)))
                        } else state
                    }
                }
        }
    }

    private fun updateForm(transform: (CreateLibraryItemState.Form) -> CreateLibraryItemState.Form) {
        _uiState.update { state ->
            val sheet = state.createSheet
            if (sheet is CreateLibraryItemState.Form) state.copy(createSheet = transform(sheet)) else state
        }
    }

    /**
     * Кладёт созданный элемент в начало списка. Сервер на дубликат имени отдаёт id
     * УЖЕ существующей записи (не ошибку) — если она уже видна в текущей выдаче,
     * простой `listOf(item) + list` дал бы два элемента с одинаковым `id` и уронил
     * `LazyColumn` (`key = { it.id }` требует уникальности). Дубликат просто
     * поднимаем наверх вместо повторной вставки.
     */
    private fun prepend(item: LibraryItem, list: List<LibraryItem>): List<LibraryItem> =
        listOf(item) + list.filterNot { it.id == item.id }

    private fun mapError(e: Throwable): String = when {
        isNetworkError(e) -> "Нет подключения к интернету. Проверьте сеть и повторите."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
