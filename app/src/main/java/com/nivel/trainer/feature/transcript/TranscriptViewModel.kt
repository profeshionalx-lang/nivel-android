package com.nivel.trainer.feature.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.TranscriptRepository
import com.nivel.trainer.domain.Transcript
import com.nivel.trainer.domain.TranscriptStatus
import com.nivel.trainer.ui.state.isNetworkError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
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
    /** #71: рефреш поверх уже загруженного транскрипта (pull-to-refresh/возврат на экран). */
    val refreshing: Boolean = false,
    /**
     * #75: сервер отдал 404 — запись ещё не расшифрована (аудио не загружено или
     * очередь STT ещё не подхватила). Это не ошибка — пустое состояние, отдельное
     * от [error] (сетевой/серверный сбой).
     */
    val notFound: Boolean = false,
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
     * Вызывается экраном с id из навигации. #71: больше не идемпотентна — вызов для уже
     * принятого id тоже перезапрашивает данные (возврат на экран, pull-to-refresh), только
     * без полноэкранного спиннера поверх уже показанного транскрипта (см. [refresh]).
     */
    fun load(sessionId: String) {
        this.sessionId = sessionId
        refresh()
    }

    /**
     * Тянет транскрипт с сервера. Если транскрипт уже на экране — рефреш идёт незаметно
     * ([TranscriptUiState.refreshing]); иначе — полноэкранный спиннер ([loading]). На
     * сетевую ошибку первичной загрузки показываем экран ошибки с «Повторить». Если статус
     * транскрипта — `processing`, запускаем автополлинг.
     */
    fun refresh() {
        val id = sessionId ?: return
        // Отменяем предыдущую загрузку/опрос — иначе повторный refresh («Повторить»
        // или возврат на экран) запустил бы второй цикл опроса параллельно первому.
        loadJob?.cancel()
        val hasData = _uiState.value.transcript != null
        _uiState.update { it.copy(loading = !hasData, refreshing = hasData, error = null, notFound = false) }
        loadJob = viewModelScope.launch {
            loadOnce(id)
            pollWhileProcessing(id)
        }
    }

    /**
     * Один запрос: при успехе кладём транскрипт, при сбое — либо пустое состояние
     * (404 — записи ещё нет), либо ошибку (если нет данных).
     */
    private suspend fun loadOnce(id: String) {
        repository.getTranscript(id)
            .onSuccess { transcript ->
                _uiState.update {
                    it.copy(loading = false, refreshing = false, transcript = transcript, error = null, notFound = false)
                }
            }
            .onFailure { e ->
                _uiState.update { state ->
                    when {
                        // #75: 404 — строки транскрипта больше нет (не создавалась либо
                        // пропала между опросами, например запись удалили). Проверяем
                        // ПЕРЕД "есть данные" — иначе poll на processing-экране после
                        // удаления записи навсегда завис бы на старом снимке.
                        e is HttpException && e.code() == 404 ->
                            state.copy(loading = false, refreshing = false, transcript = null, error = null, notFound = true)
                        // Данные есть, фоновый сбой (сеть/сервер) при опросе/рефреше — оставляем последний снимок.
                        state.transcript != null -> state.copy(loading = false, refreshing = false)
                        else ->
                            state.copy(loading = false, refreshing = false, error = mapError(e), notFound = false)
                    }
                }
            }
    }

    /**
     * Пока статус транскрипта `processing` — перезапрашиваем каждые 3с (как
     * `setInterval` в вебе). Останавливаемся на `ready`/`failed` или сетевой
     * ошибке (чтобы не крутить вхолостую — пользователь повторит вручную).
     */
    private suspend fun pollWhileProcessing(id: String) {
        while (_uiState.value.transcript?.status == TranscriptStatus.PROCESSING) {
            delay(POLL_INTERVAL_MS)
            val before = _uiState.value.transcript
            loadOnce(id)
            // Сетевой сбой при опросе (статус не изменился, данные остались) — стоп.
            if (_uiState.value.transcript === before) break
        }
    }

    private fun mapError(e: Throwable): String = when {
        isNetworkError(e) -> "Нет подключения к интернету. Проверьте сеть и повторите."
        e is HttpException && e.code() == 403 -> "Нет доступа к этой сессии."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3000L
    }
}
