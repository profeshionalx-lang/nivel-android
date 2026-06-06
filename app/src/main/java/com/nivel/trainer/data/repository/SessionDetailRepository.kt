package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.remote.ReorderCardsRequest
import com.nivel.trainer.data.remote.ReviewCompleteRequest
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.Cached
import com.nivel.trainer.domain.SessionOverview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий карточки тренировки (B6, #9) — просмотр: детали сессии + статус
 * обработки аудио + инсайт-карточки. Точечный экран чтения без собственных
 * Room-entity; оффлайн-чтение (G3, #32) — через generic [JsonResponseCache]:
 * при сетевом сбое отдаём последний снимок overview со `stale=true`. Источник
 * правды — сервер (см. AGENTS.md).
 *
 * Три запроса идут параллельно:
 *  - детали сессии — критичны (их сбой роняет networkCall → отдаём кэш или ошибку);
 *  - статус аудио — best-effort: 404 (записи ещё нет) и любой сбой → `null`,
 *    экран показывается без аудио-блока;
 *  - карточки — best-effort → пустой список (секция покажет «пусто»).
 */
interface SessionDetailRepository {
    suspend fun getOverview(sessionId: String): Result<Cached<SessionOverview>>
    /** D5 (#23): зафиксировать финальное ревью тренера и уведомить ученика. */
    suspend fun completeReview(sessionId: String): Result<Unit>
    /** D4 (#22): сохранить новый порядок карточек на сервере. */
    suspend fun reorderCards(sessionId: String, orderedIds: List<String>): Result<Unit>
}

@Singleton
class DefaultSessionDetailRepository @Inject constructor(
    private val api: NivelApi,
    private val cache: JsonResponseCache,
) : SessionDetailRepository {

    override suspend fun completeReview(sessionId: String): Result<Unit> = runCatching {
        api.completeReview(sessionId, ReviewCompleteRequest(completed = true))
        Unit
    }

    override suspend fun reorderCards(sessionId: String, orderedIds: List<String>): Result<Unit> = runCatching {
        api.reorderCards(sessionId, ReorderCardsRequest(orderedIds))
        Unit
    }

    override suspend fun getOverview(sessionId: String): Result<Cached<SessionOverview>> =
        cache.fetch("session_overview:$sessionId", SessionOverview.serializer()) {
            coroutineScope {
                val detailDeferred = async { api.getSessionDetail(sessionId) }
                val audioDeferred = async {
                    // 404 = записи ещё нет; прочие сбои тоже не должны ронять экран.
                    runCatching { api.getSessionTranscriptStatus(sessionId).toDomain() }.getOrNull()
                }
                val cardsDeferred = async {
                    runCatching { api.getSessionCards(sessionId).cards.map { it.toDomain(sessionId) } }
                        .getOrDefault(emptyList())
                }
                SessionOverview(
                    detail = detailDeferred.await().toDomain(),
                    audio = audioDeferred.await(),
                    cards = cardsDeferred.await(),
                )
            }
        }
}
