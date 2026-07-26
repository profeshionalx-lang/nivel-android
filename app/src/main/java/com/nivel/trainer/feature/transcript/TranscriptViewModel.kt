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

/** Аналог-по-серверу действие — сброс и удаление вызывают один и тот же `deleteTranscriptCore`. */
enum class PendingDestructiveAction { RESET, DELETE }

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
    /**
     * Подтверждение сброса/удаления — оба вызывают один и тот же деструктивный
     * `deleteTranscriptCore` на сервере (удаляет строку + аудио-файл безвозвратно),
     * поэтому оба требуют подтверждения через один и тот же bottom-sheet.
     */
    val pendingAction: PendingDestructiveAction? = null,
    val actionInProgress: Boolean = false,
    val actionError: String? = null,
    /** «Анализ поставлен в очередь» — снимается пользователем в UI. */
    val queuedMessage: String? = null,
    /**
     * Одноразовый сигнал экрану: сброс/удаление успешно завершились — уходим назад
     * на карточку сессии, как веб (`resetTranscript`/`deleteTranscript` Server Actions
     * делают `redirect(/sessions/{id})`, см. `src/lib/actions/audio.ts`). Экран этого
     * состояния «транскрипта нет» никогда не показывает — веб-страница `/transcript`
     * сама редиректит прочь, если транскрипта нет (`if (!transcript) redirect(...)`).
     * Screen обязан вызвать [onNavigatedBack] сразу после навигации, чтобы флаг не
     * сработал повторно при рекомпозиции.
     */
    val navigateBack: Boolean = false,
) {
    /**
     * «Проанализировать заново» доступно только когда расшифровка готова И анализ
     * ещё не в очереди/не идёт — иначе сервер вернёт 400, а сразу после успешного
     * requeue (когда локально уже проставлен `analysisStatus = "idle"`) кнопка
     * оставалась бы активной и повторный тап давал непонятную ошибку.
     */
    val canRequeueAnalysis: Boolean
        get() = transcript?.status == TranscriptStatus.READY &&
            analysisStatus != "idle" && analysisStatus != "processing"
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

    /**
     * И «Расшифровать заново», и «Удалить запись» безвозвратно удаляют строку и
     * аудио-файл на сервере (один и тот же `deleteTranscriptCore`) — оба ведут на
     * одно и то же подтверждение, а не выполняются по одному тапу.
     */
    fun requestAction(action: PendingDestructiveAction) =
        _uiState.update { it.copy(actionsSheetOpen = false, pendingAction = action) }

    fun cancelPendingAction() {
        if (_uiState.value.actionInProgress) return
        _uiState.update { it.copy(pendingAction = null) }
    }

    fun dismissQueuedMessage() = _uiState.update { it.copy(queuedMessage = null) }

    fun clearActionError() = _uiState.update { it.copy(actionError = null) }

    /** Screen вызывает сразу после [TranscriptUiState.navigateBack] — гасит одноразовый сигнал. */
    fun onNavigatedBack() = _uiState.update { it.copy(navigateBack = false) }

    /**
     * Подтверждено в bottom-sheet: выполняет [TranscriptUiState.pendingAction]. Оба
     * варианта на сервере — одна и та же деструктивная операция; веб после неё
     * уходит с этой страницы (`redirect(/sessions/{id})` в `src/lib/actions/audio.ts`
     * — страница транскрипта на вебе физически не рендерит «транскрипта нет», сама
     * редиректит прочь). Здесь то же самое через [TranscriptUiState.navigateBack].
     */
    fun confirmPendingAction() {
        val id = sessionId ?: return
        val action = _uiState.value.pendingAction ?: return
        if (_uiState.value.actionInProgress) return
        loadJob?.cancel()
        _uiState.update { it.copy(actionInProgress = true, actionError = null) }
        viewModelScope.launch {
            val result = when (action) {
                PendingDestructiveAction.RESET -> repository.resetTranscript(id)
                PendingDestructiveAction.DELETE -> repository.deleteTranscript(id)
            }
            result
                .onSuccess {
                    _uiState.update {
                        it.copy(actionInProgress = false, pendingAction = null, navigateBack = true)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(actionInProgress = false, actionError = mapError(e)) } }
        }
    }

    /**
     * «Проанализировать заново» — только когда транскрипт `ready` и анализ ещё не
     * в очереди/не идёт ([TranscriptUiState.canRequeueAnalysis]). Не мгновенно: демон
     * подхватывает запись сам, UI лишь подтверждает постановку в очередь, статус
     * обновится по `RefreshOnResume`/pull-to-refresh.
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
