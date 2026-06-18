package com.nivel.trainer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.LibraryRepository
import com.nivel.trainer.domain.Library
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-состояние экрана «Library» (E6, #29). Точечный экран чтения без кэша:
 * стандартные загрузка / ошибка / контент (пустые секции внутри контента —
 * как в вебе, со своими подписями).
 */
data class LibraryUiState(
    val loading: Boolean = true,
    val library: Library? = null,
    val error: Throwable? = null,
)

/**
 * ViewModel экрана справочника. Паттерн как у остальных read-экранов: Hilt-инъекция
 * репозитория, единый [StateFlow], загрузка через корутину. Источник правды — сервер
 * (`GET /api/v1/reference`).
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.getLibrary()
                .onSuccess { lib -> _uiState.update { it.copy(loading = false, library = lib, error = null) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e) } }
        }
    }
}
