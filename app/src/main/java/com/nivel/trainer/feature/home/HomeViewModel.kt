package com.nivel.trainer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.AuthRepository
import com.nivel.trainer.data.repository.TrainerOverviewRepository
import com.nivel.trainer.domain.TrainerOverview
import com.nivel.trainer.ui.state.isNetworkError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние домашнего экрана тренера (A6, #76) + выход из аккаунта (#72).
 *
 * Как в [com.nivel.trainer.feature.home.StudentsUiState]: [overview] — последний
 * успешно загруженный снимок, [refreshing]/[error]/[offline]/[refreshFailed] описывают
 * состояние текущего запроса поверх него. Данные не кэшируются в Room (см. репозиторий) —
 * поэтому "кэш" здесь — просто последнее значение в состоянии, переживает только пока
 * ViewModel жив.
 */
data class HomeUiState(
    val overview: TrainerOverview? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
    val refreshFailed: Boolean = false,
    val confirmLogout: Boolean = false,
    val loggingOut: Boolean = false,
) {
    /** Истинный empty-state: загрузка завершена, ошибки нет, данных никогда не было. */
    val isEmpty: Boolean get() = overview == null && !refreshing && error == null

    /** Баннер поверх последнего снимка при любой неудаче рефреша — не только сетевой. */
    val showOfflineBanner: Boolean get() = refreshFailed && overview != null
}

/**
 * ViewModel домашнего экрана (B1 → A6/#76): дашборд тренера ([TrainerOverviewRepository])
 * + выход из аккаунта (#72, [AuthRepository.logout]).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val overviewRepository: TrainerOverviewRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Тянет свежий агрегат с сервера. При сбое, если снимок уже был, оставляем его на
     * экране и показываем баннер (см. [HomeUiState.showOfflineBanner]) — как список
     * учеников (#71). Пустой снимок при сбое — полноэкранная ошибка с «Повторить».
     */
    fun refresh() {
        _uiState.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            overviewRepository.getOverview()
                .onSuccess { overview ->
                    _uiState.update {
                        it.copy(
                            overview = overview,
                            refreshing = false,
                            error = null,
                            offline = false,
                            refreshFailed = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        state.copy(
                            refreshing = false,
                            error = if (state.overview == null) mapError(e) else null,
                            offline = isNetworkError(e),
                            refreshFailed = true,
                        )
                    }
                }
        }
    }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."

    // --- Выход из аккаунта (#72) ---

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
