package com.nivel.trainer.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.SessionDetailRepository
import com.nivel.trainer.domain.SessionOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-состояние экрана карточки тренировки (B6).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 */
data class SessionDetailUiState(
    val loading: Boolean = true,
    val overview: SessionOverview? = null,
    val error: String? = null,
)

/**
 * ViewModel экрана карточки тренировки (B6). Паттерн как у
 * [com.nivel.trainer.feature.student.StudentProfileViewModel]: Hilt-инъекция
 * репозитория, единый [StateFlow], загрузка через корутину.
 *
 * Источник правды — сервер ([SessionDetailRepository.getOverview]); экран
 * точечный, Room-кэш для него не ведём.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: SessionDetailRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    private var sessionId: String? = null

    /**
     * Вызывается экраном с id из навигации. Идемпотентна: для уже принятого id
     * повторные вызовы (recompose, возврат на экран) не перезапускают загрузку.
     * Повтор после ошибки — через [refresh] (кнопка «Повторить»).
     */
    fun load(sessionId: String) {
        if (this.sessionId == sessionId) return
        this.sessionId = sessionId
        refresh()
    }

    /** Тянет состояние сессии с сервера; ошибку показываем с возможностью повтора. */
    fun refresh() {
        val id = sessionId ?: return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.getOverview(id)
                .onSuccess { overview ->
                    _uiState.update { it.copy(loading = false, overview = overview, error = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = mapError(e)) }
                }
        }
    }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
}
