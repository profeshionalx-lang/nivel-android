package com.nivel.trainer.service.upload

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nivel.trainer.feature.frames.FrameSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стадия фоновой заливки кадра для UI карточки (#102). Маппит [WorkInfo]
 * уникальной работы заливки (`frame-upload-<cardId>-<slot>`) в то же
 * [UploadStage], что и [UploadStatusObserver] для аудио — семантика стадий
 * (queued/uploading %/failed/done) одинакова, различается только источник
 * уникальной работы (per card+slot, а не per session).
 *
 * `Done` здесь означает «attach подтверждён сервером» — именно в этот момент
 * UI должен перечитать карточку, чтобы получить свежий `frame_*_url` (сервер —
 * единственный источник правды, путь не хранится и не показывается на клиенте).
 */
@Singleton
class FrameUploadStatusObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun observe(cardId: String, slot: FrameSlot): Flow<UploadStage> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(FrameUploadScheduler.uniqueName(cardId, slot))
            .map { infos -> infos.toStage() }

    private fun List<WorkInfo>.toStage(): UploadStage {
        // Уникальная работа → берём самую свежую (по списку — последняя enqueued).
        val info = lastOrNull() ?: return UploadStage.None
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UploadStage.Queued
            WorkInfo.State.RUNNING -> {
                val percent = info.progress.getInt(FrameUploadWorker.KEY_PROGRESS, 0)
                UploadStage.Uploading(percent.coerceIn(0, 100))
            }
            WorkInfo.State.FAILED ->
                UploadStage.Failed(
                    filePath = info.outputData.getString(FrameUploadWorker.KEY_FILE_PATH),
                    reason = info.outputData.getString(FrameUploadWorker.KEY_ERROR),
                )
            WorkInfo.State.SUCCEEDED -> UploadStage.Done
            WorkInfo.State.CANCELLED -> UploadStage.None
        }
    }
}
