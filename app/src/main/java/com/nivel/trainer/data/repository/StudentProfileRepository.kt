package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.AddMasterPlanItemRequest
import com.nivel.trainer.data.remote.AddMasterPlanSectionRequest
import com.nivel.trainer.data.remote.CreateGoalRequest
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.Problem
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
 *
 * E2 (#25): здесь же справочник проблем и создание цели ученику.
 */
interface StudentProfileRepository {
    suspend fun getProfile(studentId: String): Result<StudentProfile>

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
