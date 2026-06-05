package com.nivel.trainer.service.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.nivel.trainer.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Воркер фоновой заливки записи в конвейер (C3, #12): upload-url → PUT → transcribe
 * через [AudioUploadRepository]. Переживает закрытие приложения (WorkManager).
 *
 * Long-running foreground: длинная запись (до 90 мин ⇒ десятки МБ) + блокирующий
 * `…/transcribe` могут превысить 10-минутный лимит обычного воркера, поэтому
 * показываем foreground-уведомление и работаем как сервис типа `dataSync`.
 *
 * Реакция на исход: [UploadOutcome.Retry] → `Result.retry()` (backoff), но не
 * бесконечно — после [MAX_ATTEMPTS] попыток сдаёмся (`failure`). Постоянный сбой —
 * сразу `failure`.
 */
@HiltWorker
class AudioUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AudioUploadRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
        val filePath = inputData.getString(KEY_FILE_PATH)
        if (sessionId.isNullOrBlank() || filePath.isNullOrBlank()) {
            return Result.failure()
        }

        // Поднимаем foreground, чтобы не упереться в лимит выполнения. Если система
        // откажет (например, нет POST_NOTIFICATIONS) — продолжаем как обычный воркер.
        runCatching { setForeground(foregroundInfo()) }

        return when (val outcome = repository.upload(sessionId, filePath)) {
            is UploadOutcome.Success -> Result.success()
            is UploadOutcome.PermanentFailure -> Result.failure()
            is UploadOutcome.Retry ->
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Загрузка записи тренировки")
            .setContentText("Идёт заливка и расшифровка…")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Заливка записи",
            NotificationManager.IMPORTANCE_LOW, // статус-уведомление, без звука
        ).apply {
            description = "Уведомление о фоновой заливке записи тренировки"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_FILE_PATH = "file_path"

        private const val MAX_ATTEMPTS = 5
        private const val CHANNEL_ID = "upload"
        private const val NOTIFICATION_ID = 1002
    }
}
