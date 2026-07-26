package com.nivel.trainer.data.repository

import com.nivel.trainer.BuildConfig
import com.nivel.trainer.data.local.SessionDao
import com.nivel.trainer.data.local.StudentDao
import com.nivel.trainer.data.local.StudentEntity
import com.nivel.trainer.data.remote.AddMasterPlanItemRequest
import com.nivel.trainer.data.remote.AddMasterPlanSectionRequest
import com.nivel.trainer.data.remote.CreateGoalRequest
import com.nivel.trainer.data.remote.CreateSessionForStudentRequest
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.remote.StudentDetailResponse
import com.nivel.trainer.data.remote.UpdateStudentProfileRequest
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.data.toEntity
import com.nivel.trainer.data.toStudentSession
import com.nivel.trainer.domain.InviteStatus
import com.nivel.trainer.domain.Problem
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
 * Источник правды — сервер. `detail`, `master-plan` и статус приглашения грузятся
 * параллельно; план и приглашение — best-effort (их сбой/отсутствие не роняют профиль).
 *
 * G3/#73: офлайн-кэш по образцу [DefaultSessionDetailRepository] — успех сети кэширует
 * базовую инфо (`students`, тот же кэш что и список) и сессии (`sessions`, тот же кэш что
 * и карточка тренировки); сбой сети падает на кэш с [StudentProfile.isStale]=true. Цели,
 * мастер-план и статус приглашения не кэшируются (best-effort, как аудио-статус в
 * [SessionOverview] — офлайн они просто недоступны, профиль всё равно показывается).
 *
 * E2 (#25): здесь же справочник проблем и создание цели ученику.
 */
interface StudentProfileRepository {
    suspend fun getProfile(studentId: String): Result<StudentProfile>

    /** Правка профиля ученика (имя/аватар). Пустые значения → null. */
    suspend fun updateProfile(studentId: String, fullName: String?, avatarUrl: String?): Result<Unit>

    /** Перевыпустить приглашение; возвращает новую claim-ссылку для шаринга. */
    suspend fun regenerateInvite(studentId: String): Result<String>

    /** Отозвать приглашение (старая ссылка перестаёт работать). */
    suspend fun revokeInvite(studentId: String): Result<Unit>

    suspend fun createSession(
        studentId: String,
        goalId: String,
        scheduledAt: String?,
        completedAt: String?,
        trainerNotes: String?,
        status: String?,
    ): Result<String>

    /** Справочник проблем для пикера при создании цели (E2). */
    suspend fun getProblems(): Result<List<Problem>>

    /**
     * Создать цель ученику (E2): проблема из справочника и/или свободный текст.
     * Хотя бы одно из значений должно быть задано (гейтит UI, как в вебе).
     */
    suspend fun createGoal(
        studentId: String,
        problemId: Int?,
        customProblem: String?,
    ): Result<Unit>

    // --- E5 (#28): редактирование мастер-плана ---

    /** Создать пустой мастер-план ученику. */
    suspend fun createMasterPlan(studentId: String): Result<Unit>

    /** Добавить секцию в план (category ∈ strength/technique/tactics/custom). */
    suspend fun addMasterPlanSection(
        studentId: String,
        planId: String,
        title: String,
        category: String,
        sortOrder: Int,
    ): Result<Unit>

    /** Удалить секцию (с пунктами каскадом). */
    suspend fun deleteMasterPlanSection(studentId: String, sectionId: String): Result<Unit>

    /** Добавить пункт в секцию. */
    suspend fun addMasterPlanItem(
        studentId: String,
        sectionId: String,
        title: String,
        description: String?,
        imageUrl: String?,
        sortOrder: Int,
    ): Result<Unit>

    /** Удалить пункт мастер-плана. */
    suspend fun deleteMasterPlanItem(studentId: String, itemId: String): Result<Unit>
}

@Singleton
class DefaultStudentProfileRepository @Inject constructor(
    private val api: NivelApi,
    private val studentDao: StudentDao,
    private val sessionDao: SessionDao,
) : StudentProfileRepository {

    override suspend fun getProfile(studentId: String): Result<StudentProfile> {
        val networkResult = runCatching { fetchFromNetwork(studentId) }
        if (networkResult.isSuccess) {
            val profile = networkResult.getOrThrow()
            runCatching { cacheProfile(studentId, profile.detail) }
            return Result.success(profile.domain)
        }

        val cached = runCatching { loadFromCache(studentId) }.getOrNull()
        if (cached != null) {
            return Result.success(cached.copy(isStale = true))
        }

        return Result.failure(
            networkResult.exceptionOrNull()
                ?: RuntimeException("Нет данных — проверьте соединение"),
        )
    }

    // --- Приватные хелперы (#73: офлайн-кэш) ---

    /** Сеть + сырой detail-DTO (для кэша), чтобы не запрашивать его дважды. */
    private class NetworkProfile(val domain: StudentProfile, val detail: StudentDetailResponse)

    private suspend fun fetchFromNetwork(studentId: String): NetworkProfile = coroutineScope {
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
        NetworkProfile(detail.toDomain(planDeferred.await(), inviteDeferred.await()), detail)
    }

    /**
     * Кэширует базовую инфо ученика (та же таблица `students`, что и список — счётчики
     * `activeGoals`/`totalSessions`/`createdAt` в detail-ответе отсутствуют, сохраняем
     * то, что уже было в кэше) и его сессии (та же таблица `sessions`, что и карточка
     * тренировки).
     */
    private suspend fun cacheProfile(studentId: String, detail: StudentDetailResponse) {
        val existing = studentDao.getById(studentId)
        studentDao.upsertAll(
            listOf(
                StudentEntity(
                    id = studentId,
                    fullName = detail.fullName,
                    email = detail.email,
                    avatarUrl = detail.avatarUrl,
                    activeGoals = existing?.activeGoals ?: 0,
                    totalSessions = existing?.totalSessions ?: detail.sessions.size,
                    createdAt = existing?.createdAt,
                ),
            ),
        )
        sessionDao.replaceForStudent(studentId, detail.sessions.map { it.toEntity(studentId) })
    }

    /** Офлайн-профиль: цели/мастер-план/приглашение недоступны (best-effort). */
    private suspend fun loadFromCache(studentId: String): StudentProfile? {
        val entity = studentDao.getById(studentId) ?: return null
        return StudentProfile(
            id = entity.id,
            fullName = entity.fullName,
            email = entity.email,
            avatarUrl = entity.avatarUrl,
            goals = emptyList(),
            sessions = sessionDao.getByStudent(studentId).map { it.toStudentSession() },
            masterPlan = null,
            invite = null,
        )
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

    override suspend fun createSession(
        studentId: String,
        goalId: String,
        scheduledAt: String?,
        completedAt: String?,
        trainerNotes: String?,
        status: String?,
    ): Result<String> = runCatching {
        api.createSessionForStudent(
            CreateSessionForStudentRequest(
                studentId = studentId,
                goalId = goalId,
                scheduledAt = scheduledAt,
                completedAt = completedAt,
                trainerNotes = trainerNotes?.takeIf { it.isNotBlank() },
                status = status,
            )
        ).sessionId
    }

    override suspend fun getProblems(): Result<List<Problem>> = runCatching {
        api.getReference().problems.map { it.toDomain() }
    }

    override suspend fun createGoal(
        studentId: String,
        problemId: Int?,
        customProblem: String?,
    ): Result<Unit> = runCatching {
        api.createStudentGoal(
            studentId = studentId,
            body = CreateGoalRequest(
                problemId = problemId,
                customProblem = customProblem?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        Unit
    }

    override suspend fun createMasterPlan(studentId: String): Result<Unit> = runCatching {
        api.createMasterPlan(studentId)
        Unit
    }

    override suspend fun addMasterPlanSection(
        studentId: String,
        planId: String,
        title: String,
        category: String,
        sortOrder: Int,
    ): Result<Unit> = runCatching {
        api.addMasterPlanSection(
            studentId = studentId,
            body = AddMasterPlanSectionRequest(
                planId = planId,
                title = title.trim(),
                category = category,
                sortOrder = sortOrder,
            ),
        )
        Unit
    }

    override suspend fun deleteMasterPlanSection(studentId: String, sectionId: String): Result<Unit> =
        runCatching {
            api.deleteMasterPlanSection(studentId, sectionId)
            Unit
        }

    override suspend fun addMasterPlanItem(
        studentId: String,
        sectionId: String,
        title: String,
        description: String?,
        imageUrl: String?,
        sortOrder: Int,
    ): Result<Unit> = runCatching {
        api.addMasterPlanItem(
            studentId = studentId,
            sectionId = sectionId,
            body = AddMasterPlanItemRequest(
                title = title.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                imageUrl = imageUrl?.trim()?.takeIf { it.isNotEmpty() },
                sortOrder = sortOrder,
            ),
        )
        Unit
    }

    override suspend fun deleteMasterPlanItem(studentId: String, itemId: String): Result<Unit> =
        runCatching {
            api.deleteMasterPlanItem(studentId, itemId)
            Unit
        }
}
