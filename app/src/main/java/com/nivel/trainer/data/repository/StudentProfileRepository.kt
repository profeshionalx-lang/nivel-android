package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.StudentProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий профиля ученика (B5, #8) — просмотр: базовая инфо + цели + сессии
 * + мастер-план. Точечный экран чтения, поэтому без Room-кэша: тянет данные с
 * сервера и отдаёт доменную модель. Источник правды — сервер (см. AGENTS.md).
 *
 * `detail` и `master-plan` грузятся параллельно. Мастер-план не критичен для
 * экрана: при сбое именно его запроса профиль всё равно показывается без плана.
 */
interface StudentProfileRepository {
    suspend fun getProfile(studentId: String): Result<StudentProfile>
}

@Singleton
class DefaultStudentProfileRepository @Inject constructor(
    private val api: NivelApi,
) : StudentProfileRepository {

    override suspend fun getProfile(studentId: String): Result<StudentProfile> = runCatching {
        coroutineScope {
            val detailDeferred = async { api.getStudentDetail(studentId) }
            // Мастер-план — best-effort: его отсутствие/сбой не должны ронять профиль.
            val planDeferred = async {
                runCatching { api.getStudentMasterPlan(studentId).plan }.getOrNull()
            }
            val detail = detailDeferred.await()
            detail.toDomain(planDeferred.await())
        }
    }
}
