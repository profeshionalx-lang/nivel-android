package com.nivel.trainer.service.upload

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.feature.frames.FrameSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Стоп-условие для удаления локального видео тренировки (A9, #103, п.3 issue):
 * если у карточек сессии есть кадры «до»/«после», которые ещё заливаются на сервер
 * или упали с ошибкой, видео удалять нельзя — тренер потеряет исходник и не сможет
 * их перевыбрать.
 *
 * Опрашивает WorkManager по каждой карточке из [InsightCard] и обоим слотам
 * (before/after) — уникальная работа `frame-upload-<cardId>-<slot>` (A7, #101,
 * [FrameUploadScheduler.uniqueName]). `ENQUEUED`/`BLOCKED`/`RUNNING` — кадр летит;
 * `FAILED` — ретраи исчерпаны, но файл ещё на диске и ждёт ручного повтора
 * (см. [FrameUploadRepository.upload]) — тоже считаем «есть незалитые кадры».
 * `SUCCEEDED`/`CANCELLED`/нет работы для пары карточка+слот — не блокируют удаление.
 */
@Singleton
class PendingFrameUploadsChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** true — среди [cards] есть кадр в процессе заливки или со сбоем; удалять видео нельзя. */
    suspend fun hasPendingUploads(cards: List<InsightCard>): Boolean {
        val workManager = WorkManager.getInstance(context)
        for (card in cards) {
            for (slot in FrameSlot.entries) {
                val infos = workManager
                    .getWorkInfosForUniqueWorkFlow(FrameUploadScheduler.uniqueName(card.id, slot))
                    .first()
                val state = infos.lastOrNull()?.state ?: continue
                if (state == WorkInfo.State.ENQUEUED ||
                    state == WorkInfo.State.BLOCKED ||
                    state == WorkInfo.State.RUNNING ||
                    state == WorkInfo.State.FAILED
                ) {
                    return true
                }
            }
        }
        return false
    }
}
