package com.nivel.trainer.service.upload

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стадия фоновой заливки записи для экрана статусов (C5, #14). Маппит [WorkInfo]
 * уникальной работы заливки (`audio-upload-<sessionId>`) в понятное UI-состояние.
 *
 * Заливка — ПЕРВАЯ фаза конвейера, до того как на сервере появится строка
 * транскрипта. Дальше (расшифровка → анализ → карточки) состояние берётся уже из
 * `…/transcript/status` (B6/D2). Поэтому здесь нас интересует только «пока файл
 * ещё не на сервере»: в очереди / льётся (%) / упала с возможностью повтора.
 */
sealed interface UploadStage {
    /** Активной работы заливки для этой сессии нет (ещё не ставили или уже доехала). */
    data object None : UploadStage

    /** Поставлена в очередь, ждёт сеть/слот (ENQUEUED). */
    data object Queued : UploadStage

    /** Идёт заливка; [percent] — 0..100 (из [AudioUploadWorker.KEY_PROGRESS]). */
    data class Uploading(val percent: Int) : UploadStage

    /**
     * Заливка провалилась окончательно (исчерпаны ретраи / постоянный сбой).
     * [filePath] (если есть) — для ручного повтора через [AudioUploadScheduler.retry].
     */
    data class Failed(val filePath: String?) : UploadStage

    /** Заливка успешно завершена — дальше статус ведёт транскрипт (B6). */
    data object Done : UploadStage
}

/**
 * Поставщик [Flow] стадии заливки по sessionId — поверх WorkManager LiveData/Flow
 * уникальной работы. UI (C5) подписывается и рисует прогресс/ошибку.
 */
@Singleton
class UploadStatusObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun observe(sessionId: String): Flow<UploadStage> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(AudioUploadScheduler.uniqueName(sessionId))
            .map { infos -> infos.toStage() }

    private fun List<WorkInfo>.toStage(): UploadStage {
        // Уникальная работа → берём самую свежую (по списку — последняя enqueued).
        val info = lastOrNull() ?: return UploadStage.None
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UploadStage.Queued
            WorkInfo.State.RUNNING -> {
                val percent = info.progress.getInt(AudioUploadWorker.KEY_PROGRESS, 0)
                UploadStage.Uploading(percent.coerceIn(0, 100))
            }
            WorkInfo.State.FAILED ->
                UploadStage.Failed(info.outputData.getString(AudioUploadWorker.KEY_FILE_PATH))
            WorkInfo.State.SUCCEEDED -> UploadStage.Done
            WorkInfo.State.CANCELLED -> UploadStage.None
        }
    }
}
