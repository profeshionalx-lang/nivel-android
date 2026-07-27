package com.nivel.trainer.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nivel.trainer.service.upload.AudioUploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Режим записи (A3, #97), выбирается тренером **до** старта:
 * - [AUDIO] — как раньше, телефон в кармане, экран может гаснуть, пишет `MediaRecorder`.
 * - [VIDEO] — телефон на штативе, экран не гаснет, пишет CameraX `Recorder` БЕЗ звука
 *   (звук в видео-режиме параллельным `MediaRecorder` добавит A4/#98, здесь его нет).
 */
enum class RecordingMode { AUDIO, VIDEO }

/**
 * Состояние записи (C1, расширено в A3/#97 режимом). Единый источник правды о том,
 * идёт ли запись, в каком режиме, куда пишется файл и сколько уже записано. UI (экран
 * записи C2) подписывается на [RecordingController.state]; для [RecordingMode.AUDIO]
 * его обновляет сервис [RecordingService], для [RecordingMode.VIDEO] — экран записи
 * напрямую (CameraX работает через превью на экране, не через foreground-сервис).
 */
sealed interface RecordingState {
    /** Записи нет. */
    data object Idle : RecordingState

    /**
     * Идёт запись, привязанная к сессии [sessionId]. Для [RecordingMode.AUDIO] файл
     * пишется в [outputPath], для [RecordingMode.VIDEO] — в [videoPath] (второе поле
     * у соответствующего режима не заполняется). [startedElapsedRealtimeMs] —
     * `SystemClock.elapsedRealtime()` на момент старта, по нему UI/уведомление считают
     * длительность (монотонные часы, не зависят от перевода системного времени).
     */
    data class Recording(
        val sessionId: String,
        val startedElapsedRealtimeMs: Long,
        val mode: RecordingMode = RecordingMode.AUDIO,
        val outputPath: String? = null,
        val videoPath: String? = null,
    ) : RecordingState

    /**
     * Запись завершена длительностью [durationMs]. Аудио-файл [outputPath] ждёт
     * заливки (C3); видео-файл [videoPath] остаётся только локально — на сервер
     * видео не уезжает (см. эпик NIVEL#235), заливки для него нет.
     */
    data class Finished(
        val sessionId: String,
        val durationMs: Long,
        val mode: RecordingMode = RecordingMode.AUDIO,
        val outputPath: String? = null,
        val videoPath: String? = null,
    ) : RecordingState

    /** Ошибка записи (нет разрешения, занят микрофон/камера, сбой кодека, мало места). */
    data class Error(
        val sessionId: String?,
        val message: String,
        val mode: RecordingMode = RecordingMode.AUDIO,
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
    private val uploadScheduler: AudioUploadScheduler,
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /**
     * Запустить аудио-запись для сессии. Поднимает foreground-сервис (тип microphone).
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

    /**
     * Остановить аудио-запись и завершить сервис. Файл остаётся на диске для заливки.
     *
     * Команду шлём только когда идёт именно аудио-запись: тогда сервис жив в foreground
     * и `startService` гарантированно доставит интент. Иначе (записи нет, либо это
     * видео-запись — ей владеет CameraX на экране записи, не сервис) команду не шлём:
     * на Android 8+ запуск фонового сервиса из фона бросает исключение, а для видео
     * стоп идёт напрямую через рекордер экрана (см. [RecorderScreen][com.nivel.trainer.feature.recorder.RecorderScreen]).
     */
    fun stop() {
        val current = _state.value
        if (current !is RecordingState.Recording || current.mode != RecordingMode.AUDIO) return
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

    /**
     * Обновление состояния. Для [RecordingMode.AUDIO] его зовёт только сервис (он
     * владеет `MediaRecorder`); для [RecordingMode.VIDEO] — экран записи (он владеет
     * CameraX-рекордером через превью). `internal` — вызовы только внутри модуля.
     */
    internal fun update(state: RecordingState) {
        _state.value = state
        // Хэндофф запись → конвейер (C3): как только АУДИО-запись завершена, ставим
        // заливку в очередь WorkManager. Видео на сервер не уезжает (эпик NIVEL#235,
        // заливку получит только звук в A4) — для него заливку не ставим.
        if (state is RecordingState.Finished && state.mode == RecordingMode.AUDIO && state.outputPath != null) {
            uploadScheduler.enqueue(sessionId = state.sessionId, filePath = state.outputPath)
        }
    }
}
