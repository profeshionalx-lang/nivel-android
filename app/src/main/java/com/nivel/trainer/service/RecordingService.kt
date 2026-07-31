package com.nivel.trainer.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nivel.trainer.MainActivity
import com.nivel.trainer.R
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * Foreground-сервис фоновой записи тренировки (C1; аудио-сайдкар видео-режима — A4/#98).
 *
 * Тип `microphone` + видимое уведомление со встроенным таймером и кнопкой «Стоп».
 * Запись идёт в `MediaRecorder` (AAC в контейнере MPEG-4 → `.m4a` — формат уже
 * принимается конвейером транскрипции) и не прерывается при локе экрана/сворачивании,
 * потому что сервис в foreground и держит микрофон.
 *
 * Один и тот же сервис обслуживает два режима ([RecordingMode], `EXTRA_MODE`):
 * - [RecordingMode.AUDIO] (по умолчанию, без `EXTRA_MODE`) — как раньше, поведение
 *   не изменилось ни на байт: результат идёт напрямую в [RecordingController.update].
 * - [RecordingMode.VIDEO] — параллельный сайдкар звука (вариант B): та же запись в
 *   `MediaRecorder`, но результат репортится через [RecordingController.onAudioSidecarStarted]/
 *   [RecordingController.onAudioSidecarFinished], чтобы не затирать видео-часть
 *   состояния, которой владеет экран записи (CameraX).
 *
 * Состоянием владеет [RecordingController] (process-wide). Сервис — единственный,
 * кто запускает/останавливает `MediaRecorder` и сообщает результат в контроллер.
 * Команды приходят интентами ([ACTION_START]/[ACTION_STOP]) от контроллера.
 *
 * Устойчивость к обрывам/докачке — задача C4; здесь надёжная запись и файл на диске.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var controller: RecordingController

    private var recorder: MediaRecorder? = null
    private var sessionId: String? = null
    private var outputFile: File? = null

    // Момент входа в startRecording() — используется ТОЛЬКО для приблизительного
    // "when" уведомления (setWhen в buildNotification): notification обязана подняться
    // (promoteToForeground) ДО того, как mr вообще существует, так что точного момента
    // старта записи для неё ещё нет. Не использовать для расчёта durationMs/оффсета —
    // для этого есть [recordingStartedElapsedRealtimeMs] ниже.
    private var startedElapsedRealtimeMs: Long = 0L

    // Момент, когда MediaRecorder РЕАЛЬНО начал писать (сразу после успешного mr.start(),
    // а не когда сервис только принял intent) — от него считаем durationMs (stopRecording)
    // и его же репортим в RecordingController (Recording.startedElapsedRealtimeMs для
    // AUDIO / onAudioSidecarStarted для сайдкара VIDEO-режима, A4/#98). Это важно для
    // audioStartOffsetMs: между входом в startRecording() и реальным стартом записи
    // проходит promoteToForeground() (создание канала, уведомление) + mr.prepare()
    // (инициализация кодека) — десятки-сотни мс, которые иначе тихо съедались бы из
    // измеряемого дрейфа звук/видео (см. Acceptance issue #98 — обязательное измерение
    // дрейфа на реальном устройстве).
    private var recordingStartedElapsedRealtimeMs: Long = 0L
    private var mode: RecordingMode = RecordingMode.AUDIO

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requestedMode = intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
                    ?: RecordingMode.AUDIO
                startRecording(intent.getStringExtra(EXTRA_SESSION_ID), requestedMode)
            }
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        // Не перезапускаем запись автоматически после убийства процесса: возобновление
        // длинной записи без сохранённого состояния — отдельная задача (C4).
        return START_NOT_STICKY
    }

    private fun startRecording(sessionId: String?, mode: RecordingMode) {
        // Повторный старт во время активной записи игнорируем.
        if (recorder != null) return
        this.mode = mode

        startedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        // Сервис запущен через startForegroundService → ОБЯЗАНЫ позвать startForeground в
        // течение ~5с, иначе система роняет процесс. Поднимаем foreground всегда; тип
        // microphone — только при наличии разрешения (без него FGS-microphone на 14+
        // бросает SecurityException, поэтому деградируем до типа «none»).
        // Тип сервиса всегда `microphone` независимо от режима — камерой в видео-режиме
        // владеет CameraX на самом экране записи (foreground Activity), не этот сервис,
        // поэтому FOREGROUND_SERVICE_CAMERA здесь не нужен (см. AndroidManifest.xml).
        if (!promoteToForeground(useMicrophoneType = hasMic)) {
            reportError(sessionId, mode, "Не удалось запустить сервис записи")
            stopSelf()
            return
        }

        if (sessionId.isNullOrBlank()) {
            reportError(null, mode, "Не выбрана тренировка для записи")
            finishForeground()
            return
        }
        if (!hasMic) {
            reportError(sessionId, mode, "Нет разрешения на запись звука")
            finishForeground()
            return
        }

        val file = File(File(filesDir, RECORDINGS_DIR).apply { mkdirs() }, fileName(sessionId))
        val mr = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setOutputFile(file.absolutePath)
        }

        try {
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            runCatching { mr.release() }
            file.delete()
            reportError(sessionId, mode, e.message ?: "Не удалось начать запись")
            finishForeground()
            return
        }
        // Отсюда MediaRecorder реально пишет — это и есть настоящий момент старта.
        recordingStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()

        recorder = mr
        this.sessionId = sessionId
        outputFile = file
        when (mode) {
            RecordingMode.AUDIO -> controller.update(
                RecordingState.Recording(
                    sessionId = sessionId,
                    outputPath = file.absolutePath,
                    startedElapsedRealtimeMs = recordingStartedElapsedRealtimeMs,
                ),
            )
            RecordingMode.VIDEO -> controller.onAudioSidecarStarted(
                sessionId = sessionId,
                audioPath = file.absolutePath,
                startedElapsedRealtimeMs = recordingStartedElapsedRealtimeMs,
            )
        }
    }

    private fun stopRecording() {
        val mr = recorder
        val file = outputFile
        val id = sessionId
        val recordingMode = mode
        if (mr == null || file == null || id == null) {
            // Нечего останавливать (или уже остановлено) — просто гасим сервис.
            finishForeground()
            return
        }

        val durationMs = SystemClock.elapsedRealtime() - recordingStartedElapsedRealtimeMs
        // TODO(C4): для очень длинных записей финализация moov-атома в stop() может
        // занять заметное время — вынести stop()/release() в фоновый поток, чтобы не
        // блокировать main и не рисковать ANR. Для C1 (первый срез) — синхронно.
        val stoppedOk = runCatching {
            mr.stop()
        }.isSuccess
        runCatching { mr.release() }
        recorder = null

        if (stoppedOk && file.exists() && file.length() > 0) {
            when (recordingMode) {
                RecordingMode.AUDIO -> controller.update(
                    RecordingState.Finished(sessionId = id, outputPath = file.absolutePath, durationMs = durationMs),
                )
                RecordingMode.VIDEO -> controller.onAudioSidecarFinished(
                    sessionId = id,
                    audioPath = file.absolutePath,
                    durationMs = durationMs,
                )
            }
        } else {
            // `stop()` бросает, если запись слишком короткая (нет кадров) — файл невалиден.
            file.delete()
            reportError(id, recordingMode, "Запись слишком короткая или повреждена")
        }

        sessionId = null
        outputFile = null
        finishForeground()
    }

    /** Репорт ошибки в контроллер с учётом режима — AUDIO идёт напрямую в [RecordingController.update]. */
    private fun reportError(sessionId: String?, mode: RecordingMode, message: String) {
        when (mode) {
            RecordingMode.AUDIO -> controller.update(RecordingState.Error(sessionId, message))
            RecordingMode.VIDEO -> controller.onAudioSidecarError(sessionId, message)
        }
    }

    override fun onDestroy() {
        // Подстраховка: если сервис убивают на ходу — закрываем рекордер, не теряя ресурс.
        recorder?.let { mr ->
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        recorder = null
        super.onDestroy()
    }

    // --- Foreground notification ---

    /**
     * Поднять сервис в foreground с уведомлением записи. [useMicrophoneType] —
     * объявить тип `microphone` (только при наличии разрешения; иначе тип «none»,
     * чтобы не словить SecurityException на Android 14+). Возвращает false, если
     * система отказала в запуске foreground.
     */
    private fun promoteToForeground(useMicrophoneType: Boolean): Boolean {
        ensureChannel()
        val type = if (useMicrophoneType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }.isSuccess
    }

    /** Снять foreground (убрать уведомление) и остановить сервис. */
    private fun finishForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Идёт запись тренировки")
        .setContentText("Нажмите, чтобы открыть Nivel")
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // Встроенный хронометр сам считает время записи — без поминутных обновлений.
        .setUsesChronometer(true)
        .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedElapsedRealtimeMs))
        .setContentIntent(openAppIntent())
        .addAction(0, "Стоп", stopActionIntent())
        .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, immutableFlags())
    }

    private fun stopActionIntent(): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(this, 1, intent, immutableFlags())
    }

    private fun immutableFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись тренировки",
            NotificationManager.IMPORTANCE_LOW, // без звука/вибрации — это статус-уведомление
        ).apply {
            description = "Уведомление о фоновой записи тренировки"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun fileName(sessionId: String): String {
        // Безопасное имя файла: id сессии может содержать дефисы (UUID) — это ок для файловой системы.
        val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "session-$safeId-${System.currentTimeMillis()}.m4a"
    }

    companion object {
        const val ACTION_START = "com.nivel.trainer.recording.START"
        const val ACTION_STOP = "com.nivel.trainer.recording.STOP"
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** Имя [RecordingMode] (A4, #98) — отсутствует ⇒ [RecordingMode.AUDIO] (поведение не менялось). */
        const val EXTRA_MODE = "extra_mode"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val RECORDINGS_DIR = "recordings"
    }
}
