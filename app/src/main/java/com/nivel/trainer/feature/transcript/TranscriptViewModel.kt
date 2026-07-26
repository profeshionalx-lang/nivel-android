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
    /**
     * A9 (#79): статус анализа отдельно от статуса транскрипции — свои текст и
     * ошибка, не смешиваются с [transcript]/[error]. `null`, пока не загружен или
     * транскрипта ещё нет (для 404 запрашивать нечего).
     */
    val analysisStatus: String? = null,
    val analysisError: String? = null,
    /** Меню действий (bottom-sheet): сброс/удаление/повторный анализ. */
    val actionsSheetOpen: Boolean = false,
    /** Подтверждение удаления (отдельный bottom-sheet — деструктивное действие). */
    val confirmDelete: Boolean = false,
    val actionInProgress: Boolean = false,
    val actionError: String? = null,
    /** «Анализ поставлен в очередь» — снимается пользователем в UI. */
    val queuedMessage: String? = null,
) {
    /** «Проанализировать заново» доступно только когда расшифровка готова (сервер иначе вернёт 400). */
    val canRequeueAnalysis: Boolean get() = transcript?.status == TranscriptStatus.READY
}

/**
 * ViewModel экрана транскрипта (D1, #19; управление — A9, #79). Паттерн как у
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
                loadStatus(id)
            }
            .onFailure { e ->
                _uiState.update { state ->
                    when {
                        // #75: 404 — строки транскрипта больше нет (не создавалась либо
                        // пропала между опросами, например запись удалили). Проверяем
                        // ПЕРЕД "есть данные" — иначе poll на processing-экране после
                        // удаления записи навсегда завис бы на старом снимке.
                        e is HttpException && e.code() == 404 ->
                            state.copy(
                                loading = false, refreshing = false, transcript = null, error = null,
                                notFound = true, analysisStatus = null, analysisError = null,
                            )
                        // Данные есть, фоновый сбой (сеть/сервер) при опросе/рефреше — оставляем последний снимок.
                        state.transcript != null -> state.copy(loading = false, refreshing = false)
                        else ->
                            state.copy(loading = false, refreshing = false, error = mapError(e), notFound = false)
                    }
                }
            }
    }

    /**
     * Статус анализа (A9, #79) — отдельным запросом, best-effort: сбой не должен
     * ронять уже показанный транскрипт, поэтому ошибка молча игнорируется (при
     * следующем refresh/poll подтянется).
     */
    private suspend fun loadStatus(id: String) {
        repository.getStatus(id)
            .onSuccess { status ->
                _uiState.update { it.copy(analysisStatus = status.analysisStatus, analysisError = status.analysisError) }
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

    // --- A9 (#79): меню действий — сброс, удаление, повторный анализ ---

    fun openActionsSheet() = _uiState.update { it.copy(actionsSheetOpen = true, actionError = null) }

    fun closeActionsSheet() = _uiState.update { it.copy(actionsSheetOpen = false) }

    fun requestDelete() = _uiState.update { it.copy(actionsSheetOpen = false, confirmDelete = true) }

    fun cancelDelete() {
        if (_uiState.value.actionInProgress) return
        _uiState.update { it.copy(confirmDelete = false) }
    }

    fun dismissQueuedMessage() = _uiState.update { it.copy(queuedMessage = null) }

    /**
     * «Расшифровать заново»: сбрасывает упавшую/зависшую расшифровку (удаляет строку
     * и файл в Storage — тот же `deleteTranscriptCore`, что и удаление). Реального
     * автозапуска новой расшифровки нет — тренеру нужно заново загрузить аудио на
     * экране сессии; здесь экран просто возвращается в пустое состояние «запись ещё
     * не расшифрована», готовое принять новую попытку.
     */
    fun resetTranscript() {
        val id = sessionId ?: return
        if (_uiState.value.actionInProgress) return
        loadJob?.cancel()
        _uiState.update { it.copy(actionsSheetOpen = false, actionInProgress = true, actionError = null) }
        viewModelScope.launch {
            repository.resetTranscript(id)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            actionInProgress = false, transcript = null, notFound = true,
                            analysisStatus = null, analysisError = null,
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(actionInProgress = false, actionError = mapError(e)) } }
        }
    }

    /** «Удалить запись», подтверждено в bottom-sheet: удаляет строку + аудио-файл. */
    fun confirmDelete() {
        val id = sessionId ?: return
        if (_uiState.value.actionInProgress) return
        loadJob?.cancel()
        _uiState.update { it.copy(actionInProgress = true, actionError = null) }
        viewModelScope.launch {
            repository.deleteTranscript(id)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            actionInProgress = false, confirmDelete = false, transcript = null, notFound = true,
                            analysisStatus = null, analysisError = null,
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(actionInProgress = false, actionError = mapError(e)) } }
        }
    }

    /**
     * «Проанализировать заново» — только когда транскрипт `ready` ([TranscriptUiState.canRequeueAnalysis]).
     * Не мгновенно: демон подхватывает запись сам, поэтому UI лишь подтверждает
     * постановку в очередь, статус обновится по `RefreshOnResume`/pull-to-refresh.
     */
    fun requeueAnalysis() {
        val id = sessionId ?: return
        if (_uiState.value.actionInProgress || !_uiState.value.canRequeueAnalysis) return
        _uiState.update { it.copy(actionsSheetOpen = false, actionInProgress = true, actionError = null) }
        viewModelScope.launch {
            repository.requeueAnalysis(id)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            actionInProgress = false,
                            queuedMessage = "Анализ поставлен в очередь",
                            analysisStatus = "idle",
                            analysisError = null,
                        )
                    }
                }
                .onFailure { e ->
                    val message = if (e is HttpException && e.code() == 400) {
                        "Анализ уже в очереди или транскрипт не готов."
                    } else {
                        mapError(e)
                    }
                    _uiState.update { it.copy(actionInProgress = false, actionError = message) }
                }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3000L
    }
}
