package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.AddCardToCollectionRequest
import com.nivel.trainer.data.remote.ApplyCollectionRequest
import com.nivel.trainer.data.remote.ApplyTemplateRequest
import com.nivel.trainer.data.remote.CreateCollectionRequest
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.CardLibrary
import com.nivel.trainer.domain.StudentSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий библиотеки карточек-шаблонов и коллекций (E4, #27). Порт веб-страницы
 * `trainer/cards` (`CardsLibrary`): чтение шаблонов/учеников/коллекций, ведение
 * коллекций (создать, добавить/убрать карточку) и применение шаблона/коллекции к
 * сессии ученика.
 *
 * Точечный экран чтения, без Room-кэша: источник правды — сервер (как профиль
 * ученика). Карточки и коллекции грузятся параллельно одним [getLibrary].
 *
 * Сессии ученика для шита применения берём из уже существующего
 * `GET /api/v1/students/{id}` (он отдаёт `sessions`) — без отдельного эндпоинта.
 */
interface CardLibraryRepository {
    /** Загрузить библиотеку: шаблоны + ученики + коллекции (параллельно). */
    suspend fun getLibrary(): Result<CardLibrary>

    /** Создать коллекцию; возвращает её id. */
    suspend fun createCollection(name: String): Result<String>

    /** Добавить шаблон (по ключу template_id/id) в коллекцию. */
    suspend fun addCardToCollection(collectionId: String, templateId: String): Result<Unit>

    /** Убрать шаблон из коллекции. */
    suspend fun removeCardFromCollection(collectionId: String, templateId: String): Result<Unit>

    /** Применить шаблон к сессии ученика (создаёт карточку у ученика). */
    suspend fun applyTemplate(sessionId: String, templateId: String): Result<Unit>

    /** Применить коллекцию к сессии ученика; возвращает сколько карточек добавлено. */
    suspend fun applyCollection(collectionId: String, sessionId: String): Result<Int>

    /** Сессии ученика для выбора цели применения (из профиля ученика). */
    suspend fun getStudentSessions(studentId: String): Result<List<StudentSession>>
}

@Singleton
class DefaultCardLibraryRepository @Inject constructor(
    private val api: NivelApi,
) : CardLibraryRepository {

    override suspend fun getLibrary(): Result<CardLibrary> = runCatching {
        coroutineScope {
            val cardsDeferred = async { api.getCardLibrary() }
            val collectionsDeferred = async { api.getCollections() }
            val cards = cardsDeferred.await()
            val collections = collectionsDeferred.await()
            CardLibrary(
                templates = cards.templates.map { it.toDomain() },
                students = cards.students.map { it.toDomain() },
                collections = collections.collections.map { it.toDomain() },
            )
        }
    }

    override suspend fun createCollection(name: String): Result<String> = runCatching {
        api.createCollection(CreateCollectionRequest(name = name.trim())).id
            ?: error("Сервер не вернул id коллекции")
    }

    override suspend fun addCardToCollection(collectionId: String, templateId: String): Result<Unit> =
        runCatching {
            api.addCardToCollection(collectionId, AddCardToCollectionRequest(templateId = templateId))
            Unit
        }

    override suspend fun removeCardFromCollection(collectionId: String, templateId: String): Result<Unit> =
        runCatching {
            api.removeCardFromCollection(collectionId, templateId)
            Unit
        }

    override suspend fun applyTemplate(sessionId: String, templateId: String): Result<Unit> =
        runCatching {
            api.applyTemplateToSession(sessionId, ApplyTemplateRequest(templateId = templateId))
            Unit
        }

    override suspend fun applyCollection(collectionId: String, sessionId: String): Result<Int> =
        runCatching {
            api.applyCollectionToSession(collectionId, ApplyCollectionRequest(sessionId = sessionId)).applied
        }

    override suspend fun getStudentSessions(studentId: String): Result<List<StudentSession>> =
        runCatching {
            api.getStudentDetail(studentId).sessions.map { it.toDomain() }
        }
}
