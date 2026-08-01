package com.nivel.trainer.service.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nivel.trainer.feature.frames.FrameSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Планировщик заливки кадра (A7, #101) — единая точка постановки
 * [FrameUploadWorker] в очередь. Вызывается сразу после того, как тренер
 * подтвердил выбор кадра на скрабере (A6, #100 отдаёт [com.nivel.trainer.feature.frames.FrameSelectionResult];
 * фактический вызов — #102, экран слотов карточки).
 *
 * Очередь на пару карточка+слот уникальна (`frame-upload-<cardId>-<slot>`) с
 * политикой [ExistingWorkPolicy.REPLACE] — **не** [ExistingWorkPolicy.KEEP], как у
 * аудио: если тренер перевыбрал кадр до завершения прежней заливки, актуален
 * последний выбор, а не гонка «кто первый доехал». Прежняя работа снимается,
 * дубля в bucket не остаётся (заливается только новый storagePath).
 */
@Singleton
class FrameUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Поставить заливку кадра в очередь (или заменить незавершённую заливку того же слота). */
    fun enqueue(cardId: String, slot: FrameSlot, filePath: String) {
        val request = OneTimeWorkRequestBuilder<FrameUploadWorker>()
            .setInputData(
                workDataOf(
                    FrameUploadWorker.KEY_CARD_ID to cardId,
                    FrameUploadWorker.KEY_SLOT to slot.routeValue,
                    FrameUploadWorker.KEY_FILE_PATH to filePath,
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
            uniqueName(cardId, slot),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val TAG = "frame-upload"
        private const val BACKOFF_SECONDS = 30L
        fun uniqueName(cardId: String, slot: FrameSlot) = "frame-upload-$cardId-${slot.routeValue}"
    }
}
