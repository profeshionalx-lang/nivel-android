package com.nivel.trainer.data.repository

import com.nivel.trainer.data.local.LocalVideoStore
import com.nivel.trainer.data.local.VideoRecord
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.service.upload.PendingFrameUploadsChecker
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Результат попытки удалить локальное видео тренировки (A9, #103). */
sealed interface VideoDeletionResult {
    /** Файл `.mp4` и запись в [LocalVideoStore] удалены. */
    data object Deleted : VideoDeletionResult

    /** Локального видео для сессии уже не было — нечего удалять (идемпотентно). */
    data object NothingToDelete : VideoDeletionResult

    /** Есть незалитые/сбойные кадры — видео нарочно не тронуто. [reason] — текст для UI. */
    data class Blocked(val reason: String) : VideoDeletionResult

    /** Файловая ошибка при удалении. */
    data class Failure(val message: String) : VideoDeletionResult
}

/**
 * Удаление локального видео тренировки (A9, #103) — по кнопке «Завершить разбор»
 * (после успешного ответа сервера) или вручную из индикатора занятого места на
 * экране сессии. Инкапсулирует File API + [LocalVideoStore], чтобы ViewModel их не
 * трогал напрямую, и стоп-условие «незалитые кадры» ([PendingFrameUploadsChecker]).
 */
interface VideoCleanupRepository {
    /** Живое состояние локального видео сессии — питает индикатор занятого места. */
    fun observe(sessionId: String): Flow<VideoRecord?>

    /**
     * Удаляет `.mp4` + запись в [LocalVideoStore], если для [cards] сессии нет
     * незалитых/сбойных кадров. Не бросает — файловые ошибки маппятся в
     * [VideoDeletionResult.Failure]. Вызывающая сторона отвечает за порядок:
     * дергать это ПОСЛЕ подтверждённого успеха на сервере (review-complete), не до.
     */
    suspend fun deleteVideo(sessionId: String, cards: List<InsightCard>): VideoDeletionResult
}

@Singleton
class DefaultVideoCleanupRepository @Inject constructor(
    private val localVideoStore: LocalVideoStore,
    private val pendingFrameUploadsChecker: PendingFrameUploadsChecker,
) : VideoCleanupRepository {

    override fun observe(sessionId: String): Flow<VideoRecord?> = localVideoStore.observe(sessionId)

    override suspend fun deleteVideo(sessionId: String, cards: List<InsightCard>): VideoDeletionResult {
        val record = localVideoStore.get(sessionId) ?: return VideoDeletionResult.NothingToDelete

        if (pendingFrameUploadsChecker.hasPendingUploads(cards)) {
            return VideoDeletionResult.Blocked(
                "Кадры ещё заливаются на сервер. Подожди, пока заливка закончится, и попробуй " +
                    "снова — иначе не сможешь их перевыбрать.",
            )
        }

        return runCatching {
            val file = File(record.videoPath)
            check(!file.exists() || file.delete()) { "Не удалось удалить файл видео" }
            localVideoStore.remove(sessionId)
        }.fold(
            onSuccess = { VideoDeletionResult.Deleted },
            onFailure = { e -> VideoDeletionResult.Failure(e.message ?: "Не удалось удалить видео") },
        )
    }
}
