package com.nivel.trainer.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.nivel.trainer.data.local.LocalVideoStore
import com.nivel.trainer.data.local.VideoRecord
import com.nivel.trainer.service.upload.AudioUploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Режим записи (A3, #97), выбирается тренером **до** старта:
 * - [AUDIO] — как раньше, телефон в кармане, экран может гаснуть, пишет `MediaRecorder`.
 * - [VIDEO] — телефон на штативе, экран не гаснет, пишет CameraX `Recorder` БЕЗ звука;
 *   звук пишет параллельный `MediaRecorder` (вариант B, A4/#98) — тот же
 *   [RecordingService], но как «сайдкар»: файл уезжает на сервер, видео остаётся
 *   только на телефоне (эпик NIVEL#235).
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
    /**
     * [audioStartOffsetMs] — только для [RecordingMode.VIDEO] (A4, #98): разница
     * `audioStartedElapsedRealtimeMs - startedElapsedRealtimeMs` (видео стартует первым
     * и владеет [startedElapsedRealtimeMs]), заполняется как только аудио-сайдкар
     * реально начал писать — до этого момента `null`.
     */
    data class Recording(
        val sessionId: String,
        val startedElapsedRealtimeMs: Long,
        val mode: RecordingMode = RecordingMode.AUDIO,
        val outputPath: String? = null,
        val videoPath: String? = null,
        val audioStartOffsetMs: Long? = null,
    ) : RecordingState

    /**
     * Запись завершена длительностью [durationMs]. Аудио-файл [outputPath] ждёт
     * заливки (C3); видео-файл [videoPath] остаётся только локально — на сервер
     * видео не уезжает (см. эпик NIVEL#235), заливки для него нет. [audioStartOffsetMs] —
     * см. докблок [Recording], нужен для скрабера (A6), чтобы поправить таймкод момента
     * на рассинхрон стартов двух рекордеров. [interrupted] (#106, только для
     * [RecordingMode.VIDEO]) — true, если CameraX финализировал запись с ошибкой
     * (обрыв не по команде тренера), но файл всё равно непустой и играбельный —
     * экран должен показать это, а не рисовать обычный «успех».
     */
    data class Finished(
        val sessionId: String,
        val durationMs: Long,
        val mode: RecordingMode = RecordingMode.AUDIO,
        val outputPath: String? = null,
        val videoPath: String? = null,
        val audioStartOffsetMs: Long? = null,
        val interrupted: Boolean = false,
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
    private val localVideoStore: LocalVideoStore,
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    // Скоуп для suspend-записи в LocalVideoStore (DataStore) — сама точка входа
    // (onAudioSidecarFinished/videoFinished) синхронная, IO — на фоне. Как и в
    // TokenStore, скоуп синглтона живёт весь процесс, отдельно не отменяем.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Половинки видео-сессии (A4, #98): CameraX и аудио-сайдкар финализируются
    // независимо и в произвольном порядке — копим то, что пришло первым, и не
    // публикуем итоговый Finished, пока не придут ОБЕ части.
    private data class PendingVideo(
        val sessionId: String,
        val videoPath: String,
        val durationMs: Long,
        val interrupted: Boolean = false,
    )
    private data class PendingAudio(val sessionId: String, val audioPath: String, val durationMs: Long)
    private data class ActiveVideoStart(val startedAtEpochMs: Long, val startedElapsedRealtimeMs: Long)

    @Volatile private var pendingVideo: PendingVideo? = null
    @Volatile private var pendingAudio: PendingAudio? = null
    @Volatile private var activeVideoStart: ActiveVideoStart? = null

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
     * Команду шлём только когда идёт именно аудио-запись сервисом в режиме
     * [RecordingMode.AUDIO]: тогда сервис жив в foreground и `startService`
     * гарантированно доставит интент. Для видео-режима стоп CameraX идёт напрямую
     * через рекордер экрана (см. [RecorderScreen][com.nivel.trainer.feature.recorder.RecorderScreen]),
     * а аудио-сайдкар (A4, #98) останавливается отдельно через [stopAudioSidecar].
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

    // --- Видео-режим: CameraX (A3, #97) + аудио-сайдкар (A4, #98) ---

    /**
     * Экран записи (через [RecorderViewModel]) сообщает: CameraX начал писать
     * [videoPath] в момент [startedElapsedRealtimeMs]. Именно этот момент становится
     * `startedElapsedRealtimeMs` всей видео-сессии — аудио-сайдкар стартует чуть позже
     * (см. [onAudioSidecarStarted]), разница и есть [RecordingState.Recording.audioStartOffsetMs].
     */
    fun videoStarted(sessionId: String, videoPath: String, startedElapsedRealtimeMs: Long) {
        pendingVideo = null
        pendingAudio = null
        activeVideoStart = ActiveVideoStart(System.currentTimeMillis(), startedElapsedRealtimeMs)
        _state.value = RecordingState.Recording(
            sessionId = sessionId,
            startedElapsedRealtimeMs = startedElapsedRealtimeMs,
            mode = RecordingMode.VIDEO,
            videoPath = videoPath,
        )
    }

    /**
     * Попросить [RecordingService] начать параллельный `MediaRecorder` для звука
     * (вариант B, A4/#98) — тот же сервис, что и в аудио-режиме, но с
     * `EXTRA_MODE=VIDEO`: пишет в такой же `.m4a`, но репортит через
     * [onAudioSidecarStarted]/[onAudioSidecarFinished] вместо прямого [update],
     * чтобы не затирать видео-часть состояния. Зовётся сразу после [videoStarted] —
     * максимально близко по времени к старту CameraX (см. класс-докблок [RecordingMode]).
     */
    fun startAudioSidecar(sessionId: String) {
        if (!RecordingPermissions.hasMicPermission(context)) {
            onAudioSidecarError(sessionId, "Нет разрешения на запись звука")
            return
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
            putExtra(RecordingService.EXTRA_MODE, RecordingMode.VIDEO.name)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Остановить аудио-сайдкар видео-режима (кнопка «Стоп» видео-flow). */
    fun stopAudioSidecar() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    /** [RecordingService] сообщает: аудио-сайдкар реально начал писать. */
    internal fun onAudioSidecarStarted(sessionId: String, audioPath: String, startedElapsedRealtimeMs: Long) {
        val current = _state.value
        if (current is RecordingState.Recording && current.mode == RecordingMode.VIDEO && current.sessionId == sessionId) {
            val offsetMs = startedElapsedRealtimeMs - current.startedElapsedRealtimeMs
            _state.value = current.copy(outputPath = audioPath, audioStartOffsetMs = offsetMs)
        }
    }

    /**
     * [RecordingService] сообщает: аудио-сайдкар не смог начать/сохранить запись.
     * Без звука транскрипции не будет — весь видео-разбор смысла не имеет (кадры без
     * звука нечем привязать к моментам), поэтому это ошибка всей сессии, а не только
     * аудио-части. Видеофайл при этом может остаться на диске — CameraX его не трогает.
     */
    internal fun onAudioSidecarError(sessionId: String?, message: String) {
        pendingVideo = null
        pendingAudio = null
        activeVideoStart = null
        _state.value = RecordingState.Error(sessionId, "Не удалось записать звук: $message", RecordingMode.VIDEO)
    }

    /** [RecordingService] сообщает: аудио-сайдкар успешно дописан и закрыт. */
    internal fun onAudioSidecarFinished(sessionId: String, audioPath: String, durationMs: Long) {
        pendingAudio = PendingAudio(sessionId, audioPath, durationMs)
        tryFinalizeVideoSession(sessionId)
    }

    /**
     * Экран записи сообщает: CameraX финализировал видеофайл. [interrupted] (#106) —
     * см. докблок [RecordingState.Finished].
     */
    fun videoFinished(sessionId: String, videoPath: String, durationMs: Long, interrupted: Boolean = false) {
        pendingVideo = PendingVideo(sessionId, videoPath, durationMs, interrupted)
        tryFinalizeVideoSession(sessionId)
    }

    /**
     * Экран записи сообщает: видеозапись не удалась (сбой камеры, обрыв без данных).
     * Аудио-сайдкар останавливаем вместе с ней — без кадров карточки «до/после» не
     * собрать, оставлять запись звука дальше идти незачем (полноценная деградация до
     * аудио-режима на сбое камеры — вне acceptance этой задачи, см. PR).
     */
    fun videoError(sessionId: String?, message: String) {
        stopAudioSidecar()
        pendingVideo = null
        pendingAudio = null
        activeVideoStart = null
        _state.value = RecordingState.Error(sessionId, message, RecordingMode.VIDEO)
    }

    /**
     * Публикует [RecordingState.Finished] и запускает хэндофф, только когда обе
     * половинки видео-сессии (видео от CameraX + аудио от сервиса) уже собраны —
     * они финализируются независимо и в произвольном порядке.
     */
    private fun tryFinalizeVideoSession(sessionId: String) {
        val video = pendingVideo?.takeIf { it.sessionId == sessionId } ?: return
        val audio = pendingAudio?.takeIf { it.sessionId == sessionId } ?: return
        val offsetMs = (_state.value as? RecordingState.Recording)?.audioStartOffsetMs
        val videoStart = activeVideoStart
        pendingVideo = null
        pendingAudio = null
        activeVideoStart = null

        _state.value = RecordingState.Finished(
            sessionId = sessionId,
            durationMs = video.durationMs,
            mode = RecordingMode.VIDEO,
            outputPath = audio.audioPath,
            videoPath = video.videoPath,
            audioStartOffsetMs = offsetMs,
            interrupted = video.interrupted,
        )
        // Хэндофф: в очередь заливки уходит ТОЛЬКО аудио (существующий шедулер, ничего
        // в нём не меняем) — видео на сервер не уезжает (эпик NIVEL#235).
        uploadScheduler.enqueue(sessionId = sessionId, filePath = audio.audioPath)

        // LocalVideoStore (A4, #98): регистрируем видео, чтобы экран сессии (A8) мог
        // предложить «Выбрать кадр». Пишем в фоне — DataStore suspend, а этот метод
        // синхронный (зовётся из RecordingService/RecorderViewModel).
        val videoFile = File(video.videoPath)
        scope.launch {
            localVideoStore.put(
                VideoRecord(
                    sessionId = sessionId,
                    videoPath = video.videoPath,
                    startedAtEpochMs = videoStart?.startedAtEpochMs ?: System.currentTimeMillis(),
                    startedAtElapsedRealtimeMs = videoStart?.startedElapsedRealtimeMs
                        ?: SystemClock.elapsedRealtime(),
                    durationMs = video.durationMs,
                    audioStartOffsetMs = offsetMs,
                    sizeBytes = videoFile.length(),
                ),
            )
        }
    }
}
