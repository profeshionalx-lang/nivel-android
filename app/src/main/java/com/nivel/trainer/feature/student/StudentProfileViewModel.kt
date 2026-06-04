package com.nivel.trainer.feature.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.StudentProfileRepository
import com.nivel.trainer.domain.InviteStatus
import com.nivel.trainer.domain.StudentInvite
import com.nivel.trainer.domain.StudentProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Состояние инлайн-правки профиля ученика (E3): значения полей + отправка/ошибка. */
data class ProfileEditState(
    val fullName: String,
    val avatarUrl: String,
    val submitting: Boolean = false,
    val error: String? = null,
)

/**
 * UI-состояние экрана профиля ученика (B5) + управление (E3).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 */
data class StudentProfileUiState(
    val loading: Boolean = true,
    val profile: StudentProfile? = null,
    val error: String? = null,
    /** E3 — инлайн-правка профиля; null = режим просмотра. */
    val editing: ProfileEditState? = null,
    /** E3 — идёт действие с приглашением (перевыпуск/отзыв). */
    val inviteBusy: Boolean = false,
    /** E3 — ошибка действия с приглашением (показываем баннером). */
    val actionError: String? = null,
)

/**
 * ViewModel экрана профиля ученика (B5) + управление учеником (E3).
 * Hilt-инъекция репозитория, единый [StateFlow]. Источник правды — сервер;
 * после правки профиля перечитываем профиль через [refresh], а статус
 * приглашения обновляем оптимистично (GET статуса ещё не готов на бэкенде —
 * см. репозиторий).
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
     * повторные вызовы (recompose, возврат на экран) не перезапускают загрузку.
     * Повтор после ошибки — через [refresh] (кнопка «Повторить»).
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

    // --- E3: правка профиля ---

    fun startEdit() {
        val profile = _uiState.value.profile ?: return
        _uiState.update {
            it.copy(
                editing = ProfileEditState(
                    fullName = profile.fullName.orEmpty(),
                    avatarUrl = profile.avatarUrl.orEmpty(),
                ),
            )
        }
    }

    fun onEditNameChange(text: String) = updateEditing { it.copy(fullName = text, error = null) }

    fun onEditAvatarChange(text: String) = updateEditing { it.copy(avatarUrl = text, error = null) }

    fun cancelEdit() {
        val editing = _uiState.value.editing
        if (editing != null && editing.submitting) return // не закрываем во время сохранения
        _uiState.update { it.copy(editing = null) }
    }

    /** Сохраняет имя/аватар; пустые значения → null (как `|| null` в вебе). */
    fun saveEdit() {
        val id = studentId ?: return
        val editing = _uiState.value.editing ?: return
        if (editing.submitting) return

        _uiState.update { it.copy(editing = editing.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            repository.updateProfile(
                id,
                fullName = editing.fullName.trim().ifBlank { null },
                avatarUrl = editing.avatarUrl.trim().ifBlank { null },
            )
                .onSuccess {
                    _uiState.update { it.copy(editing = null) }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        val open = state.editing ?: return@update state
                        state.copy(editing = open.copy(submitting = false, error = mapError(e)))
                    }
                }
        }
    }

    private inline fun updateEditing(transform: (ProfileEditState) -> ProfileEditState) {
        _uiState.update { state ->
            val editing = state.editing ?: return@update state
            state.copy(editing = transform(editing))
        }
    }

    // --- E3: приглашение ---

    /** Перевыпускает приглашение; оптимистично выставляет статус pending с новой ссылкой. */
    fun regenerateInvite() {
        val id = studentId ?: return
        if (_uiState.value.inviteBusy) return

        _uiState.update { it.copy(inviteBusy = true, actionError = null) }
        viewModelScope.launch {
            repository.regenerateInvite(id)
                .onSuccess { claimUrl ->
                    _uiState.update { state ->
                        state.copy(
                            inviteBusy = false,
                            profile = state.profile?.copy(
                                invite = StudentInvite(InviteStatus.PENDING, claimUrl, null),
                            ),
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(inviteBusy = false, actionError = mapError(e)) } }
        }
    }

    /** Отзывает приглашение; оптимистично выставляет статус revoked. */
    fun revokeInvite() {
        val id = studentId ?: return
        if (_uiState.value.inviteBusy) return

        _uiState.update { it.copy(inviteBusy = true, actionError = null) }
        viewModelScope.launch {
            repository.revokeInvite(id)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            inviteBusy = false,
                            profile = state.profile?.copy(
                                invite = StudentInvite(InviteStatus.REVOKED, null, null),
                            ),
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(inviteBusy = false, actionError = mapError(e)) } }
        }
    }

    fun dismissActionError() = _uiState.update { it.copy(actionError = null) }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
}
