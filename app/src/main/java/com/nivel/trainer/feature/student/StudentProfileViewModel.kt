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
import javax.inject.Inject

/**
 * UI-состояние экрана профиля ученика (B5).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 */
data class StudentProfileUiState(
    val loading: Boolean = true,
    val profile: StudentProfile? = null,
    val error: String? = null,
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

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
}
