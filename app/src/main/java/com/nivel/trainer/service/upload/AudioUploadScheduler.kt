package com.nivel.trainer.service.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Планировщик заливки записи (C3, #12) — единая точка постановки [AudioUploadWorker]
 * в очередь. Вызывается при завершении записи (хэндофф из [com.nivel.trainer.service.RecordingController]);
 * экран записи C2 может вызвать его же.
 *
 * Очередь на сессию уникальна (`audio-upload-<sessionId>`) с политикой [ExistingWorkPolicy.KEEP]:
 * повторный вызов для той же сессии не плодит дубль-заливок. Требуется сеть;
 * при сбое — экспоненциальный backoff (ретраи внутри воркера, см. там).
 */
@Singleton
class AudioUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun enqueue(sessionId: String, filePath: String) {
        val request = OneTimeWorkRequestBuilder<AudioUploadWorker>()
            .setInputData(
                workDataOf(
                    AudioUploadWorker.KEY_SESSION_ID to sessionId,
                    AudioUploadWorker.KEY_FILE_PATH to filePath,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(sessionId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val TAG = "audio-upload"
        private const val BACKOFF_SECONDS = 30L
        private fun uniqueName(sessionId: String) = "audio-upload-$sessionId"
    }
}
