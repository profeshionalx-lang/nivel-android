package com.nivel.trainer.data.repository

import com.nivel.trainer.BuildConfig
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.remote.UpdateStudentProfileRequest
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.InviteStatus
import com.nivel.trainer.domain.StudentInvite
import com.nivel.trainer.domain.StudentProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий профиля ученика (B5, #8) + управление учеником (E3, #26).
 * Просмотр (B5): базовая инфо + цели + сессии + мастер-план + статус приглашения.
 * Управление (E3): правка профиля, перевыпуск/отзыв приглашения.
 *
 * Точечный экран чтения, без Room-кэша: источник правды — сервер. `detail`,
 * `master-plan` и статус приглашения грузятся параллельно; план и приглашение —
 * best-effort (их сбой/отсутствие не роняют профиль).
 */
interface StudentProfileRepository {
    suspend fun getProfile(studentId: String): Result<StudentProfile>

    /** Правка профиля ученика (имя/аватар). Пустые значения → null. */
    suspend fun updateProfile(studentId: String, fullName: String?, avatarUrl: String?): Result<Unit>

    /** Перевыпустить приглашение; возвращает новую claim-ссылку для шаринга. */
    suspend fun regenerateInvite(studentId: String): Result<String>

    /** Отозвать приглашение (старая ссылка перестаёт работать). */
    suspend fun revokeInvite(studentId: String): Result<Unit>
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
            // Статус приглашения — best-effort: GET-эндпоинт ещё не готов (см. NivelApi
            // TODO), 404/сбой → UNKNOWN (а не null), чтобы секция приглашения всё равно
            // показывалась и тренер мог перевыпустить ссылку до появления реального GET.
            val inviteDeferred = async {
                runCatching { api.getStudentInvite(studentId).toDomain(BuildConfig.API_BASE_URL) }
                    .getOrDefault(StudentInvite(InviteStatus.UNKNOWN, null, null))
            }
            val detail = detailDeferred.await()
            detail.toDomain(planDeferred.await(), inviteDeferred.await())
        }
    }

    override suspend fun updateProfile(
        studentId: String,
        fullName: String?,
        avatarUrl: String?,
    ): Result<Unit> = runCatching {
        api.updateStudentProfile(
            studentId,
            UpdateStudentProfileRequest(fullName = fullName, avatarUrl = avatarUrl),
        )
        Unit
    }

    override suspend fun regenerateInvite(studentId: String): Result<String> = runCatching {
        api.regenerateInvite(studentId).claimUrl
    }

    override suspend fun revokeInvite(studentId: String): Result<Unit> = runCatching {
        api.revokeInvite(studentId)
        Unit
    }
}
