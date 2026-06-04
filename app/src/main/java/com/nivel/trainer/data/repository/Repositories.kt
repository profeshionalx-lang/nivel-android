package com.nivel.trainer.data.repository

import com.nivel.trainer.data.local.InsightCardDao
import com.nivel.trainer.data.local.SessionDao
import com.nivel.trainer.data.local.StudentDao
import com.nivel.trainer.data.remote.CreateStudentRequest
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.data.toEntity
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.ShadowStudent
import com.nivel.trainer.domain.Student
import com.nivel.trainer.domain.TrainingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Репозитории — единый вход для UI, склейка remote (NivelApi) + local (Room-кэш).
 *
 * Контракт слоя (паттерн single-source-of-truth для кэша чтения):
 *  - наблюдение (`observe*`) всегда читает из Room и отдаёт Flow → UI видит кэш мгновенно
 *    и переживает рестарт/оффлайн;
 *  - `refresh*` тянет с сервера и атомарно заменяет кэш; источник правды — сервер;
 *  - `refresh*` возвращает Result, чтобы UI мог показать ошибку, не теряя кэш (при сбое
 *    сети Flow продолжает отдавать последний снимок).
 *
 * UI ходит ТОЛЬКО через репозитории, не зная про Retrofit/Room.
 */

interface StudentRepository {
    /** Поток учеников из кэша (источник правды UI). */
    fun observeStudents(): Flow<List<Student>>

    /** Обновить кэш с сервера. При ошибке кэш сохраняется. */
    suspend fun refreshStudents(): Result<Unit>

    /**
     * Создать теневого ученика и получить claim-ссылку приглашения (B4).
     * После успеха кэш списка обновляется, чтобы новый ученик появился сразу.
     */
    suspend fun createShadowStudent(fullName: String): Result<ShadowStudent>
}

interface SessionRepository {
    fun observeSessions(studentId: String): Flow<List<TrainingSession>>
    suspend fun refreshSessions(studentId: String): Result<Unit>
}

interface InsightCardRepository {
    fun observeCards(sessionId: String): Flow<List<InsightCard>>
    suspend fun refreshCards(sessionId: String): Result<Unit>
}

@Singleton
class DefaultStudentRepository @Inject constructor(
    private val api: NivelApi,
    private val dao: StudentDao,
) : StudentRepository {

    override fun observeStudents(): Flow<List<Student>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun refreshStudents(): Result<Unit> = runCatching {
        // Контракт A3 — обёртка `{ students: [...] }`.
        val remote = api.getStudents().students
        dao.replaceAll(remote.map { it.toEntity() })
    }

    override suspend fun createShadowStudent(fullName: String): Result<ShadowStudent> = runCatching {
        val response = api.createStudent(CreateStudentRequest(fullName = fullName.trim()))
        // Подтянуть свежий список, чтобы новый ученик появился в кэше/UI сразу.
        // Сбой refresh не должен отменять успешное создание — claim-ссылка уже выдана.
        runCatching {
            dao.replaceAll(api.getStudents().students.map { it.toEntity() })
        }
        response.toDomain()
    }
}

@Singleton
class DefaultSessionRepository @Inject constructor(
    private val api: NivelApi,
    private val dao: SessionDao,
) : SessionRepository {

    override fun observeSessions(studentId: String): Flow<List<TrainingSession>> =
        dao.observeByStudent(studentId).map { list -> list.map { it.toDomain() } }

    override suspend fun refreshSessions(studentId: String): Result<Unit> = runCatching {
        val remote = api.getStudentSessions(studentId)
        dao.replaceForStudent(studentId, remote.map { it.toEntity(studentId) })
    }
}

@Singleton
class DefaultInsightCardRepository @Inject constructor(
    private val api: NivelApi,
    private val dao: InsightCardDao,
) : InsightCardRepository {

    override fun observeCards(sessionId: String): Flow<List<InsightCard>> =
        dao.observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun refreshCards(sessionId: String): Result<Unit> = runCatching {
        // Эндпоинт `…/insight-cards` отдаёт обёртку `{ cards }`; sessionId — из пути.
        val remote = api.getSessionCards(sessionId).cards
        dao.replaceForSession(sessionId, remote.map { it.toEntity(sessionId) })
    }
}
