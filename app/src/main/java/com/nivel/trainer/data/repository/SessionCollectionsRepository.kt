package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.ApplyCollectionRequest
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.data.toPreviewDomain
import com.nivel.trainer.domain.CardCollection
import com.nivel.trainer.domain.CollectionCardPreview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий применения коллекций карточек к сессии (#78). Точечное чтение
 * без Room-кэша (как [TrainerOverviewRepository]) — коллекции листаются один раз
 * при открытии bottom-sheet, кэшировать нечего.
 *
 * Управление коллекциями (создание, добавление/удаление карточек) — вне объёма,
 * тренер ведёт их в вебе; здесь только чтение списка + превью + применение.
 */
interface SessionCollectionsRepository {
    suspend fun getCollections(): Result<List<CardCollection>>
    suspend fun getCollectionCards(collectionId: String): Result<List<CollectionCardPreview>>

    /** Применяет коллекцию к сессии; возвращает число добавленных карточек. */
    suspend fun applyCollection(collectionId: String, sessionId: String): Result<Int>
}

@Singleton
class DefaultSessionCollectionsRepository @Inject constructor(
    private val api: NivelApi,
) : SessionCollectionsRepository {

    override suspend fun getCollections(): Result<List<CardCollection>> = runCatching {
        api.getCollections().collections.map { it.toDomain() }
    }

    override suspend fun getCollectionCards(collectionId: String): Result<List<CollectionCardPreview>> = runCatching {
        api.getCollectionCards(collectionId).cards.map { it.toPreviewDomain() }
    }

    override suspend fun applyCollection(collectionId: String, sessionId: String): Result<Int> = runCatching {
        api.applyCollection(collectionId, ApplyCollectionRequest(sessionId)).applied
    }
}
