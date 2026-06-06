package com.nivel.trainer.data.repository

import com.nivel.trainer.data.local.InsightCardDao
import com.nivel.trainer.data.local.InsightCardEntity
import com.nivel.trainer.data.local.SessionDao
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.remote.ReorderCardsRequest
import com.nivel.trainer.data.remote.ReviewCompleteRequest
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.data.toEntity
import com.nivel.trainer.data.toSessionDetail
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.SessionOverview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий карточки тренировки (B6, #9) — просмотр: детали сессии + статус
 * обработки аудио + инсайт-карточки.
 *
 * G3 (#32): добавлен Room-кэш для offline-чтения.
 * Паттерн: сначала пробуем сервер (3 параллельных запроса), при успехе — кэшируем
 * детали + карточки. При сетевой ошибке — пробуем Room-кэш; если есть хоть что-то
 * — возвращаем [SessionOverview] с флагом [isStale]=true (UI покажет баннер «Оффлайн»).
 * При отсутствии кэша — пробрасываем оригинальную ошибку (экран показывает «Повторить»).
 */
interface SessionDetailRepository {
    suspend fun getOverview(sessionId: String): Result<SessionOverview>
    /** D5 (#23): зафиксировать финальное ревью тренера и уведомить ученика. */
    suspend fun completeReview(sessionId: String): Result<Unit>
    /** D4 (#22): сохранить новый порядок карточек на сервере. */
    suspend fun reorderCards(sessionId: String, orderedIds: List<String>): Result<Unit>
}

@Singleton
class DefaultSessionDetailRepository @Inject constructor(
    private val api: NivelApi,
    private val sessionDao: SessionDao,
    private val cardDao: InsightCardDao,
) : SessionDetailRepository {

    override suspend fun completeReview(sessionId: String): Result<Unit> = runCatching {
        api.completeReview(sessionId, ReviewCompleteRequest(completed = true))
        Unit
    }

    override suspend fun reorderCards(sessionId: String, orderedIds: List<String>): Result<Unit> = runCatching {
        api.reorderCards(sessionId, ReorderCardsRequest(orderedIds))
        Unit
    }

    override suspend fun getOverview(sessionId: String): Result<SessionOverview> {
        // Сначала пробуем сервер.
        val networkResult = runCatching { fetchFromNetwork(sessionId) }
        if (networkResult.isSuccess) {
            val overview = networkResult.getOrThrow()
            // Кэшируем для offline-режима.
            runCatching { cacheOverview(sessionId, overview) }
            return Result.success(overview)
        }

        // Сеть недоступна — пробуем Room-кэш.
        val cached = runCatching { loadFromCache(sessionId) }.getOrNull()
        if (cached != null) {
            return Result.success(cached.copy(isStale = true))
        }

        // Нет ни сети, ни кэша — пробрасываем сетевую ошибку.
        return Result.failure(
            networkResult.exceptionOrNull()
                ?: RuntimeException("Нет данных — проверьте соединение"),
        )
    }

    // --- Приватные хелперы ---

    private suspend fun fetchFromNetwork(sessionId: String): SessionOverview = coroutineScope {
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

    private suspend fun cacheOverview(sessionId: String, overview: SessionOverview) {
        // Пере-запрашиваем detail-DTO для маппинга в entity (уже был получен выше,
        // но overview содержит уже domain-объект; дешевле повторить чем везде таскать DTO).
        runCatching {
            val detailDto = api.getSessionDetail(sessionId)
            sessionDao.upsert(detailDto.toEntity())
        }
        // Кэшируем карточки.
        if (overview.cards.isNotEmpty()) {
            cardDao.replaceForSession(sessionId, overview.cards.map { it.toCardEntity() })
        }
    }

    private suspend fun loadFromCache(sessionId: String): SessionOverview? {
        val sessionEntity = sessionDao.getById(sessionId) ?: return null
        val cards = cardDao.getBySession(sessionId).map { it.toDomain() }
        return SessionOverview(
            detail = sessionEntity.toSessionDetail(),
            audio = null, // аудио-статус не кэшируем (быстро меняется)
            cards = cards,
        )
    }
}

/** Конвертирует domain [InsightCard] обратно в Room-entity для кэша. */
private fun InsightCard.toCardEntity() = InsightCardEntity(
    id = id,
    sessionId = sessionId,
    studentId = studentId,
    trainerId = trainerId,
    title = title,
    body = body,
    quote = quote,
    frontText = frontText,
    contextText = contextText,
    tags = tags.takeIf { it.isNotEmpty() }?.joinToString(""),
    source = source,
    trainerStatus = trainerStatus,
    studentDecision = studentDecision,
    position = position,
    createdAt = createdAt,
)
