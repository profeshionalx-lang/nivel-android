package com.nivel.trainer.feature.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.TranscriptRepository
import com.nivel.trainer.domain.Transcript
import com.nivel.trainer.domain.TranscriptStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-состояние экрана транскрипта (D1).
 * Первый кадр — спиннер; дальше либо транскрипт (любого статуса), либо ошибка
 * сети с «Повторить». Состояние processing/failed внутри транскрипта рисует сам
 * экран по [Transcript.status] (как табы/спиннер/ошибка в вебе TranscriptView).
 */
data class TranscriptUiState(
    val loading: Boolean = true,
    val transcript: Transcript? = null,
    val error: String? = null,
    /** G3 (#32): показан кэш из-за отсутствия сети — UI рисует оффлайн-индикатор. */
    val offline: Boolean = false,
)

/**
 * ViewModel экрана транскрипта (D1, #19). Паттерн как у
 * [com.nivel.trainer.feature.student.StudentProfileViewModel]: Hilt-инъекция
 * репозитория, единый [StateFlow], загрузка через корутину, без Room-кэша.
 *
 * Полностью повторяет поведение веб-`TranscriptView`: пока статус `processing` —
 * автоматически перезапрашивает транскрипт каждые [POLL_INTERVAL_MS] мс, пока он
 * не станет `ready`/`failed`. Корутина опроса живёт в [viewModelScope] и сама
 * умирает при очистке VM.
 */
@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val repository: TranscriptRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranscriptUiState())
    val uiState: StateFlow<TranscriptUiState> = _uiState.asStateFlow()

    private var sessionId: String? = null

    /** Текущая корутина загрузки/опроса. Перед новым [refresh] отменяем старую,
     * чтобы не плодить параллельные циклы опроса (дубль запросов каждые 3с). */
    private var loadJob: Job? = null

    /**
     * Вызывается экраном с id из навигации. Идемпотентна: повторные вызовы для
     * того же id (recompose, возврат на экран) не перезапускают загрузку.
     */
    fun load(sessionId: String) {
        if (this.sessionId == sessionId) return
        this.sessionId = sessionId
        refresh()
    }

    /**
     * Тянет транскрипт с сервера. На сетевую ошибку показываем экран ошибки с
     * «Повторить». Если статус транскрипта — `processing`, запускаем автополлинг.
     */
    fun refresh() {
        val id = sessionId ?: return
        // Отменяем предыдущую загрузку/опрос — иначе повторный refresh («Повторить»
        // или возврат на экран) запустил бы второй цикл опроса параллельно первому.
        loadJob?.cancel()
        _uiState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            loadOnce(id)
            pollWhileProcessing(id)
        }
    }

    /**
     * Один запрос. Возвращает `true`, если ответ свежий (сеть), и `false`, если
     * отдан кэш (оффлайн) или запрос упал без кэша — это сигнал поллингу остановиться.
     * Репозиторий при сетевом сбое отдаёт последний снимок (`stale=true`); без
     * кэша — `Result.failure`, тогда показываем ошибку (если данных ещё не было).
     */
    private suspend fun loadOnce(id: String): Boolean {
        var fresh = false
        repository.getTranscript(id)
            .onSuccess { cached ->
                fresh = !cached.stale
                _uiState.update {
                    it.copy(loading = false, transcript = cached.value, error = null, offline = cached.stale)
                }
            }
            .onFailure { e ->
                _uiState.update {
                    // Кэша нет: если данных ещё не было — показываем ошибку,
                    // иначе оставляем последний снимок (сетевой сбой при опросе).
                    if (it.transcript == null) it.copy(loading = false, error = mapError(e))
                    else it.copy(loading = false)
                }
            }
        return fresh
    }

    /**
     * Пока статус транскрипта `processing` — перезапрашиваем каждые 3с (как
     * `setInterval` в вебе). Останавливаемся на `ready`/`failed` или когда сеть
     * недоступна (отдан кэш/ошибка) — чтобы не крутить вхолостую.
     */
    private suspend fun pollWhileProcessing(id: String) {
        while (_uiState.value.transcript?.status == TranscriptStatus.PROCESSING) {
            delay(POLL_INTERVAL_MS)
            val fresh = loadOnce(id)
            if (!fresh) break
        }
    }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."

    private companion object {
        const val POLL_INTERVAL_MS = 3000L
    }
}
