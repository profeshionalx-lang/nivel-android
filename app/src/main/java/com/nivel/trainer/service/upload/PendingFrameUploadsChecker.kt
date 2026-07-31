package com.nivel.trainer.service.upload

import com.nivel.trainer.domain.InsightCard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стоп-условие для удаления локального видео тренировки (A9, #103, п.3 issue):
 * если у карточек сессии есть кадры «до»/«после», которые ещё заливаются на сервер
 * или упали с ошибкой, видео удалять нельзя — тренер потеряет исходник и не сможет
 * их перевыбрать.
 *
 * TODO: A7 (#101) — сейчас проверять реально нечего. Пер-слотовый upload-статус кадра
 * (queued/uploading/failed) появится только вместе с `FrameUploadWorker`/
 * `FrameUploadScheduler` (A7, #101), которые зависят от A6, которая зависит от A5
 * (#99, `momentBefore/AfterSeconds` + `frameBefore/AfterUrl` на [InsightCard]) — на
 * момент написания A5 ещё не смержена в main, а A6/A7 не начаты. До тех пор в
 * домене/Room нет ни одного поля, по которому можно было бы определить «кадр летит»/
 * «кадр упал» — безопасный дефолт: считать, что незалитых кадров нет (не блокировать
 * удаление видео).
 *
 * Когда A7 сольётся — заменить тело [hasPendingUploads] на реальный опрос
 * `WorkManager.getWorkInfosForUniqueWork(FrameUploadScheduler.uniqueName(cardId, slot))`
 * по каждой карточке из [InsightCard] и обоим слотам (before/after), по образцу
 * [UploadStatusObserver] для аудио — `ENQUEUED`/`RUNNING`/неудачный `FAILED` после
 * исчерпанных ретраев считать «есть незалитые кадры».
 */
@Singleton
class PendingFrameUploadsChecker @Inject constructor() {

    /** true — среди [cards] есть кадр в процессе заливки или со сбоем; удалять видео нельзя. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun hasPendingUploads(cards: List<InsightCard>): Boolean {
        // TODO: A7 (#101) — реальная проверка через WorkManager, см. класс-докстринг.
        return false
    }
}
