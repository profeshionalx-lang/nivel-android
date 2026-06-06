package com.nivel.trainer.feature.auth

import android.net.Uri
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

    /** Вход Гречки открыт в Chrome Custom Tabs; ждём возврат по deep link. */
    GRECHKA_BROWSER,
}

data class AuthUiState(
    val step: AuthStep = AuthStep.OPTIONS,
    val loading: Boolean = false,
    val error: String? = null,
    /** OAuth-state (CSRF [~claim]) для текущей сессии входа Гречки. */
    val grechkaState: String = "",
    /**
     * Одноразовый запрос на открытие Custom Tabs: непустой state, который экран
     * должен «потребить» (открыть браузер и вызвать [onGrechkaTabLaunched]).
     */
    val launchGrechkaTab: String? = null,
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

    /**
     * Пользователь нажал «Войти через Гречку» — генерим state и просим экран
     * открыть Chrome Custom Tabs (см. [launchGrechkaAuth]).
     */
    fun startGrechkaLogin() {
        val csrf = randomCsrf()
        // state едет в auth-nivel.html и возвращается в deep link; claim — внутри state.
        val state = claimToken?.let { "$csrf~$it" } ?: csrf
        _uiState.update {
            it.copy(
                step = AuthStep.GRECHKA_BROWSER,
                grechkaState = state,
                launchGrechkaTab = state,
                error = null,
            )
        }
    }

    /** Экран открыл Custom Tabs — гасим одноразовый запрос, чтобы не открыть дважды. */
    fun onGrechkaTabLaunched() {
        _uiState.update { it.copy(launchGrechkaTab = null) }
    }

    /** Не удалось открыть браузер для входа Гречки. */
    fun onGrechkaTabFailed() {
        _uiState.update {
            it.copy(
                step = AuthStep.OPTIONS,
                launchGrechkaTab = null,
                error = "Не удалось открыть браузер для входа.",
            )
        }
    }

    /** Возврат к кнопкам (back / отмена входа через браузер). */
    fun cancelGrechkaLogin() {
        _uiState.update { it.copy(step = AuthStep.OPTIONS, launchGrechkaTab = null) }
    }

    /**
     * Обработка deep link `nivel://auth/callback?token=...&state=...` — возврата
     * из Custom Tabs. Сверяем CSRF-state и меняем Firebase ID token на bearer.
     */
    fun handleAuthCallback(uri: Uri) {
        val token = uri.getQueryParameter("token")
        val returnedState = uri.getQueryParameter("state")

        // Гречка возвращает state как `csrf` или `csrf~claim`; сверяем csrf-часть.
        val returnedCsrf = returnedState?.substringBefore("~")
        val expectedCsrf = _uiState.value.grechkaState.substringBefore("~")

        when {
            expectedCsrf.isBlank() ->
                // Нет ожидаемого state — мы не инициировали этот вход. Игнорируем
                // (защита от подсунутого извне deep link).
                _uiState.update { it.copy(step = AuthStep.OPTIONS) }
            token.isNullOrBlank() ->
                _uiState.update {
                    it.copy(step = AuthStep.OPTIONS, error = "Не удалось получить токен от Гречки.")
                }
            returnedCsrf != expectedCsrf ->
                _uiState.update {
                    it.copy(step = AuthStep.OPTIONS, error = "Сессия истекла, попробуйте ещё раз.")
                }
            else -> onFirebaseIdToken(token)
        }
    }

    /** Перехватили Firebase ID token из deep link Гречки — меняем на bearer. */
    fun onFirebaseIdToken(idToken: String) {
        exchange(idToken)
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
