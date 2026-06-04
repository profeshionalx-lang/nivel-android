package com.nivel.trainer.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Состояние фоновой записи (C1). Единый источник правды о том, идёт ли запись,
 * куда пишется файл и сколько уже записано. UI (экран записи C2) подписывается на
 * [RecordingController.state]; сервис [RecordingService] его обновляет.
 */
sealed interface RecordingState {
    /** Записи нет. */
    data object Idle : RecordingState

    /**
     * Идёт запись, привязанная к сессии [sessionId]. Файл пишется в [outputPath].
     * [startedElapsedRealtimeMs] — `SystemClock.elapsedRealtime()` на момент старта,
     * по нему UI/уведомление считают длительность (монотонные часы, не зависят от
     * перевода системного времени).
     */
    data class Recording(
        val sessionId: String,
        val outputPath: String,
        val startedElapsedRealtimeMs: Long,
    ) : RecordingState

    /** Запись завершена: готовый файл [outputPath] длительностью [durationMs] ждёт заливки (C3). */
    data class Finished(
        val sessionId: String,
        val outputPath: String,
        val durationMs: Long,
    ) : RecordingState

    /** Ошибка записи (нет разрешения, занят микрофон, сбой кодека). */
    data class Error(
        val sessionId: String?,
        val message: String,
    ) : RecordingState
}

/**
 * Контроллер фоновой записи — фасад над [RecordingService] (C1).
 *
 * Зачем отдельный объект, а не прямые интенты из UI: держит process-wide
 * [StateFlow] состояния (переживает пересоздание Activity), и прячет детали
 * запуска foreground-сервиса. Старт/стоп идут как команды сервису; сам сервис —
 * единственный, кто владеет `MediaRecorder` и обновляет состояние здесь.
 *
 * `@Singleton`, поэтому и сервис, и любой ViewModel инжектят один и тот же
 * экземпляр и видят одно состояние.
 */
@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /**
     * Запустить запись для сессии. Поднимает foreground-сервис (тип microphone).
     * Первичная защита: без разрешения на микрофон сервис не стартуем вовсе —
     * сразу фиксируем ошибку (экран записи C2 должен запросить разрешение заранее).
     */
    fun start(sessionId: String) {
        if (!RecordingPermissions.hasMicPermission(context)) {
            _state.value = RecordingState.Error(sessionId, "Нет разрешения на запись звука")
            return
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Остановить запись и завершить сервис. Файл остаётся на диске для заливки. */
    fun stop() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    /** Сбросить состояние в [RecordingState.Idle] после того, как UI «забрал» результат/ошибку. */
    fun acknowledge() {
        val current = _state.value
        if (current is RecordingState.Finished || current is RecordingState.Error) {
            _state.value = RecordingState.Idle
        }
    }

    /** Обновление состояния — только из сервиса (он владеет записью). */
    internal fun update(state: RecordingState) {
        _state.value = state
    }
}
