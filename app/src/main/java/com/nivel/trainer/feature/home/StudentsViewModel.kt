package com.nivel.trainer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.StudentRepository
import com.nivel.trainer.domain.ShadowStudent
import com.nivel.trainer.domain.Student
import com.nivel.trainer.ui.state.isNetworkError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние шага создания теневого ученика в bottom-sheet.
 */
sealed interface CreateSheetState {
    /** Лист закрыт. */
    data object Closed : CreateSheetState

    /** Шаг 1 — ввод имени ученика. */
    data class Form(
        val fullName: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
    ) : CreateSheetState

    /** Шаг 2 — ученик создан, показываем claim-ссылку для шаринга/копирования. */
    data class Created(val shadow: ShadowStudent) : CreateSheetState
}

data class StudentsUiState(
    /** Список из кэша Room (мгновенно при старте, переживает оффлайн). */
    val students: List<Student> = emptyList(),
    /** Идёт первичная сетевая загрузка (показываем спиннер только при пустом кэше). */
    val refreshing: Boolean = false,
    /** Ошибка сетевого обновления (кэш при этом сохраняется). */
    val error: String? = null,
    /**
     * G3 (#32): последнее обновление с сервера упало по сети. Если при этом есть кэш —
     * показываем оффлайн-баннер поверх данных, а не полноэкранную ошибку.
     */
    val offline: Boolean = false,
    /** Состояние bottom-sheet создания ученика. */
    val createSheet: CreateSheetState = CreateSheetState.Closed,
) {
    /** Истинный empty-state: загрузка завершена, ошибки нет, список пуст. */
    val isEmpty: Boolean get() = students.isEmpty() && !refreshing && error == null

    /** G3: показываем оффлайн-баннер — сеть упала, но кэш есть. */
    val showOfflineBanner: Boolean get() = offline && students.isNotEmpty()
}

/**
 * ViewModel экрана «Ученики» (B4). Паттерн как у [com.nivel.trainer.feature.auth.AuthViewModel]:
 * Hilt-инъекция репозитория, единый [StateFlow] UI-состояния, мутации через корутины.
 *
 * Источник правды списка — Room-кэш ([StudentRepository.observeStudents]); сеть лишь
 * обновляет кэш ([StudentRepository.refreshStudents]). UI не теряет данные при оффлайне.
 */
@HiltViewModel
class StudentsViewModel @Inject constructor(
    private val repository: StudentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentsUiState())
    val uiState: StateFlow<StudentsUiState> = _uiState.asStateFlow()

    init {
        // Подписка на кэш — UI видит данные мгновенно и реагирует на обновления.
        repository.observeStudents()
            .onEach { list -> _uiState.update { it.copy(students = list) } }
            .launchIn(viewModelScope)
        refresh()
    }

    /**
     * Тянет свежий список с сервера; при сбое кэш остаётся. Сетевую ошибку (нет связи)
     * помечаем флагом [StudentsUiState.offline] — UI покажет оффлайн-баннер поверх
     * кэша вместо полноэкранной ошибки (G3, #32).
     */
    fun refresh() {
        _uiState.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.refreshStudents()
            val failure = result.exceptionOrNull()
            _uiState.update { state ->
                state.copy(
                    refreshing = false,
                    error = failure?.let(::mapError),
                    offline = isNetworkError(failure),
                )
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    // --- Создание теневого ученика (bottom-sheet) ---

    fun openCreateSheet() = _uiState.update { it.copy(createSheet = CreateSheetState.Form()) }

    fun closeCreateSheet() = _uiState.update { it.copy(createSheet = CreateSheetState.Closed) }

    fun onCreateNameChange(value: String) = _uiState.update { state ->
        val sheet = state.createSheet
        if (sheet is CreateSheetState.Form) {
            state.copy(createSheet = sheet.copy(fullName = value, error = null))
        } else state
    }

    /** Создаёт ученика; при успехе сдвигает лист на шаг с claim-ссылкой. */
    fun submitCreate() {
        val sheet = _uiState.value.createSheet
        if (sheet !is CreateSheetState.Form) return
        val name = sheet.fullName.trim()
        if (name.isBlank() || sheet.submitting) return

        _uiState.update { it.copy(createSheet = sheet.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            repository.createShadowStudent(name)
                .onSuccess { shadow ->
                    _uiState.update { it.copy(createSheet = CreateSheetState.Created(shadow)) }
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        val current = state.createSheet
                        if (current is CreateSheetState.Form) {
                            state.copy(createSheet = current.copy(submitting = false, error = mapError(e)))
                        } else state
                    }
                }
        }
    }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
}
