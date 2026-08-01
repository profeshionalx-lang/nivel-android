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
import androidx.work.workDataOf
import com.nivel.trainer.R
import com.nivel.trainer.feature.frames.FrameSlot
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Воркер фоновой заливки кадра (A7, #101): upload-url → PUT → attach через
 * [FrameUploadRepository]. Переживает закрытие приложения (WorkManager) —
 * тренер может выбрать кадр и сразу уйти с экрана, заливка дожмёт сама.
 *
 * Зеркалит [AudioUploadWorker]: foreground-уведомление (JPEG маленький, но Wi-Fi
 * может пропасть посреди PUT — не хотим, чтобы систему убила фоновая работа),
 * [UploadOutcome.Retry] → `Result.retry()` с backoff, но не бесконечно — после
 * [MAX_ATTEMPTS] сдаёмся (`failure`, кадр остаётся на диске для ручного повтора,
 * см. [FrameUploadRepository.upload] docstring).
 */
@HiltWorker
class FrameUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: FrameUploadRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cardId = inputData.getString(KEY_CARD_ID)
        val slotValue = inputData.getString(KEY_SLOT)
        val filePath = inputData.getString(KEY_FILE_PATH)
        val slot = FrameSlot.fromRouteValue(slotValue)
        if (cardId.isNullOrBlank() || slotValue.isNullOrBlank() || filePath.isNullOrBlank()) {
            return Result.failure()
        }

        // Поднимаем foreground, чтобы не упереться в лимит выполнения. Если система
        // откажет (например, нет POST_NOTIFICATIONS) — продолжаем как обычный воркер.
        runCatching { setForeground(foregroundInfo()) }
        runCatching { setProgress(progressData(0)) }

        val outcome = repository.upload(cardId, slot, filePath) { fraction ->
            runCatching { setProgressAsync(progressData((fraction * 100).toInt())) }
        }

        return when (outcome) {
            is UploadOutcome.Success -> Result.success()
            // На провале возвращаем cardId/slot/filePath в outputData, чтобы UI (#102)
            // мог предложить ручной повтор — переenqueue того же слота и файла.
            is UploadOutcome.PermanentFailure -> Result.failure(failureData(cardId, slotValue, filePath))
            is UploadOutcome.Retry ->
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure(failureData(cardId, slotValue, filePath))
                else Result.retry()
        }
    }

    private fun progressData(percent: Int) =
        workDataOf(KEY_PROGRESS to percent.coerceIn(0, 100))

    private fun failureData(cardId: String, slotValue: String, filePath: String) =
        workDataOf(KEY_CARD_ID to cardId, KEY_SLOT to slotValue, KEY_FILE_PATH to filePath)

    private fun foregroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Загрузка кадра")
            .setContentText("Идёт заливка кадра карточки…")
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
            "Заливка кадра",
            NotificationManager.IMPORTANCE_LOW, // статус-уведомление, без звука
        ).apply {
            description = "Уведомление о фоновой заливке кадра карточки"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_CARD_ID = "card_id"
        const val KEY_SLOT = "slot"
        const val KEY_FILE_PATH = "file_path"

        /** Прогресс заливки 0..100 % в [androidx.work.WorkInfo.getProgress]. */
        const val KEY_PROGRESS = "progress_percent"

        private const val MAX_ATTEMPTS = 5
        private const val CHANNEL_ID = "frame-upload"
        private const val NOTIFICATION_ID = 1003
    }
}
