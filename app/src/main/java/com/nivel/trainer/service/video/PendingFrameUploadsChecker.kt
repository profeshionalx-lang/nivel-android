package com.nivel.trainer.service.video

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стоп-условие удаления видео (A9, #103): если в сессии есть кадры в статусе
 * «заливается»/«ошибка», видео удалять нельзя — тренер потеряет возможность
 * перевыбрать их с исходника. Заливка кадров — отдельная задача (#101), в кодовой
 * базе её ещё нет, поэтому здесь только контракт проверки.
 *
 * [NoOpPendingFrameUploadsChecker] — временная реализация «кадров нет», пока #101
 * не завела реальное хранилище статусов заливки (по образцу
 * [com.nivel.trainer.service.upload.UploadStatusObserver] для аудио). Когда оно
 * появится, нужно добавить реализацию поверх него и перепривязать биндинг в DI —
 * сигнатуру интерфейса менять не придётся, вызывающий код (удаление видео) тоже
 * трогать не нужно.
 */
interface PendingFrameUploadsChecker {
    /** true — в сессии есть незалитые/сбойные кадры, видео удалять нельзя. */
    suspend fun hasPendingFrames(sessionId: String): Boolean
}

@Singleton
class NoOpPendingFrameUploadsChecker @Inject constructor() : PendingFrameUploadsChecker {
    override suspend fun hasPendingFrames(sessionId: String): Boolean = false
}
