package com.nivel.trainer.data.repository

import com.nivel.trainer.data.local.StudentDao
import com.nivel.trainer.data.remote.CreateStudentRequest
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.data.toEntity
import com.nivel.trainer.domain.ShadowStudent
import com.nivel.trainer.domain.Student
import com.nivel.trainer.domain.TrainerOverview
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

/**
 * Репозиторий домашнего экрана тренера (A6, #76): `GET /api/v1/trainer/overview`.
 * Точечное чтение без Room-кэша — как [StudentProfileRepository]/[TranscriptRepository]:
 * агрегат дешёвый и быстро устаревает (тренер возвращается сюда после каждого разбора),
 * тянуть его заново дешевле, чем поддерживать кэш в актуальном состоянии.
 */
interface TrainerOverviewRepository {
    suspend fun getOverview(): Result<TrainerOverview>
}

@Singleton
class DefaultTrainerOverviewRepository @Inject constructor(
    private val api: NivelApi,
) : TrainerOverviewRepository {

    override suspend fun getOverview(): Result<TrainerOverview> = runCatching {
        api.getTrainerOverview().toDomain()
    }
}
