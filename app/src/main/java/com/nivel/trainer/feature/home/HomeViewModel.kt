package com.nivel.trainer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** #72 — состояние подтверждения выхода (bottom-sheet) на домашнем экране. */
data class HomeUiState(
    val confirmLogout: Boolean = false,
    val loggingOut: Boolean = false,
)

/**
 * ViewModel домашнего экрана (B1, каркас до полноценного дашборда #76). Пока
 * отвечает только за выход (#72): подтверждение → [AuthRepository.logout] (чистит
 * bearer-токен и весь Room-кэш) → экран вызывает [onLoggedOut] и уходит на login.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun openLogoutConfirm() = _uiState.update { it.copy(confirmLogout = true) }

    fun dismissLogoutConfirm() {
        if (_uiState.value.loggingOut) return // не закрываем во время выхода
        _uiState.update { it.copy(confirmLogout = false) }
    }

    /** Подтверждено в bottom-sheet — чистит сессию и кэш, затем вызывает [onDone]. */
    fun confirmLogout(onDone: () -> Unit) {
        if (_uiState.value.loggingOut) return
        _uiState.update { it.copy(loggingOut = true) }
        viewModelScope.launch {
            authRepository.logout()
            _uiState.update { HomeUiState() }
            onDone()
        }
    }
}
