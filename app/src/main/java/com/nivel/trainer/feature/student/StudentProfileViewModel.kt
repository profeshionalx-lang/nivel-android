package com.nivel.trainer.feature.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.StudentProfileRepository
import com.nivel.trainer.domain.StudentProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class CreateSessionModalState(
    val show: Boolean = false,
    val goalId: String = "",
    val goalTitle: String = "",
    val dateInput: String = "",
    val completed: Boolean = false,
    val trainerNotes: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
)

/**
 * UI-состояние экрана профиля ученика (B5).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 */
data class StudentProfileUiState(
    val loading: Boolean = true,
    val profile: StudentProfile? = null,
    val error: String? = null,
    val modal: CreateSessionModalState = CreateSessionModalState(),
)

/**
 * ViewModel экрана профиля ученика (B5). Паттерн как у [com.nivel.trainer.feature.home.StudentsViewModel]:
 * Hilt-инъекция репозитория, единый [StateFlow], загрузка через корутину.
 *
 * Источник правды — сервер ([StudentProfileRepository.getProfile]); экран точечный,
 * Room-кэш для него не ведём.
 */
@HiltViewModel
class StudentProfileViewModel @Inject constructor(
    private val repository: StudentProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentProfileUiState())
    val uiState: StateFlow<StudentProfileUiState> = _uiState.asStateFlow()

    private var studentId: String? = null

    /**
     * Вызывается экраном с id из навигации. Идемпотентна: для уже принятого id
     * повторные вызовы (recompose, возврат на экран) не перезапускают загрузку —
     * иначе при пересоздании composable во время первого запроса полетел бы
     * дубль. Повтор после ошибки — через [refresh] (кнопка «Повторить»).
     */
    fun load(studentId: String) {
        if (this.studentId == studentId) return
        this.studentId = studentId
        refresh()
    }

    /** Тянет профиль с сервера; ошибку показываем с возможностью повтора. */
    fun refresh() {
        val id = studentId ?: return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.getProfile(id)
                .onSuccess { profile ->
                    _uiState.update { it.copy(loading = false, profile = profile, error = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = mapError(e)) }
                }
        }
    }

    fun openModal(goalId: String, goalTitle: String) {
        _uiState.update { it.copy(modal = CreateSessionModalState(show = true, goalId = goalId, goalTitle = goalTitle)) }
    }

    fun dismissModal() {
        _uiState.update { it.copy(modal = CreateSessionModalState()) }
    }

    fun updateDateInput(v: String) {
        _uiState.update { it.copy(modal = it.modal.copy(dateInput = v, error = null)) }
    }

    fun updateCompleted(v: Boolean) {
        _uiState.update { it.copy(modal = it.modal.copy(completed = v)) }
    }

    fun updateTrainerNotes(v: String) {
        _uiState.update { it.copy(modal = it.modal.copy(trainerNotes = v)) }
    }

    fun submitCreateSession() {
        val id = studentId ?: return
        val m = _uiState.value.modal
        val isoDate = parseDate(m.dateInput)
        if (isoDate == null) {
            _uiState.update { it.copy(modal = it.modal.copy(error = "Формат даты: 2026-06-05 12:00")) }
            return
        }
        _uiState.update { it.copy(modal = it.modal.copy(submitting = true, error = null)) }
        val status = if (m.completed) "completed" else "planned"
        val completedAt = if (m.completed) isoDate else null
        viewModelScope.launch {
            repository.createSession(
                studentId = id,
                goalId = m.goalId,
                scheduledAt = isoDate,
                completedAt = completedAt,
                trainerNotes = m.trainerNotes,
                status = status,
            ).onSuccess {
                _uiState.update { it.copy(modal = CreateSessionModalState()) }
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(modal = it.modal.copy(submitting = false, error = mapError(e))) }
            }
        }
    }

    private fun parseDate(input: String): String? = runCatching {
        val ldt = LocalDateTime.parse(input.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        ldt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }.getOrNull()

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
}
