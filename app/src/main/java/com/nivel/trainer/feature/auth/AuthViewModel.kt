package com.nivel.trainer.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

/** Какой шаг входа показываем. */
enum class AuthStep {
    /** Кнопки «Войти через Гречку» / «Войти через Google». */
    OPTIONS,

    /** Открыт WebView с флоу Гречки. */
    GRECHKA_WEBVIEW,
}

data class AuthUiState(
    val step: AuthStep = AuthStep.OPTIONS,
    val loading: Boolean = false,
    val error: String? = null,
    /** OAuth-state (CSRF [~claim]) для текущей сессии WebView Гречки. */
    val grechkaState: String = "",
    /** Успешный вход — экран навигирует на home. */
    val loggedIn: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** Токен приглашения (claim) — прокидывается из deep link при наличии. */
    private var claimToken: String? = null

    fun setClaimToken(token: String?) {
        claimToken = token?.takeIf { it.isNotBlank() }
    }

    /** Пользователь нажал «Войти через Гречку» — открываем WebView с новым state. */
    fun startGrechkaLogin() {
        val csrf = randomCsrf()
        // state едет в auth-nivel.html и возвращается в редиректе; claim — внутри state.
        val state = claimToken?.let { "$csrf~$it" } ?: csrf
        _uiState.update {
            it.copy(step = AuthStep.GRECHKA_WEBVIEW, grechkaState = state, error = null)
        }
    }

    /** Возврат из WebView к кнопкам (back / отмена). */
    fun cancelGrechkaLogin() {
        _uiState.update { it.copy(step = AuthStep.OPTIONS) }
    }

    /** Перехватили Firebase ID token из редиректа Гречки — меняем на bearer. */
    fun onFirebaseIdToken(idToken: String) {
        exchange(idToken)
    }

    /** Ошибка внутри WebView Гречки. */
    fun onGrechkaError(message: String) {
        _uiState.update { it.copy(step = AuthStep.OPTIONS, loading = false, error = message) }
    }

    /**
     * Общий путь обмена Firebase ID token → bearer (и для Гречки, и для Google).
     */
    private fun exchange(idToken: String) {
        _uiState.update { it.copy(loading = true, error = null, step = AuthStep.OPTIONS) }
        viewModelScope.launch {
            runCatching { authRepository.loginWithFirebaseIdToken(idToken, claimToken) }
                .onSuccess {
                    _uiState.update { it.copy(loading = false, loggedIn = true) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(loading = false, error = mapError(e))
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Не удалось войти. Попробуйте снова."

    private fun randomCsrf(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
