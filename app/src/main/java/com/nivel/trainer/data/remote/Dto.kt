package com.nivel.trainer.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO ответов REST API бэкенда (эндпоинты `/api/v1/…`, репо profeshionalx-lang/NIVEL).
 * Сериализация — kotlinx.serialization. Поля выровнены по доменной модели веба
 * (`src/lib/types/index.ts`: Profile, Session, InsightCard).
 *
 * Read-endpoints учеников/сессий/карточек определяются в задаче A3 («Фундамент»).
 * Пока контракта в коде нет — DTO написаны по дизайн-доку; реальная интеграция
 * выверяется при появлении A3 (см. TODO в NivelApi).
 */

@Serializable
data class HealthResponse(
    val status: String,
)

/** Универсальный ответ-подтверждение write-эндпоинтов (`{ ok }`), напр. revoke invite. */
@Serializable
data class OkResponse(
    val ok: Boolean = true,
)

/**
 * Ответ `GET /api/v1/students` — обёртка `{ students: [...] }` (контракт A3,
 * `src/app/api/v1/students/route.ts` в репо NIVEL). DTO выровнен по реальному
 * хендлеру, а не по дизайн-доку.
 */
@Serializable
data class StudentsResponse(
    val students: List<StudentDto> = emptyList(),
)

/**
 * Ученик тренера. Источник — `profiles` (role=student); счётчики `active_goals`
 * / `total_sessions` считаются на сервере (`listStudentsCore`) и показываются в
 * карточке списка один-в-один с вебом (`trainer/students/page.tsx`).
 */
@Serializable
data class StudentDto(
    val id: String,
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("active_goals") val activeGoals: Int = 0,
    @SerialName("total_sessions") val totalSessions: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Тело `POST /api/v1/students` — создание теневого ученика. Сервер сам выдаёт
 * claim-токен/ссылку (email пустой до клейма учеником).
 */
@Serializable
data class CreateStudentRequest(
    @SerialName("full_name") val fullName: String,
)

/**
 * Ответ создания теневого ученика и invite regenerate
 * (`POST /api/v1/students` 201 и `…/invite/regenerate`):
 * `{ ok, studentId, claimUrl, claimToken, expiresAt }` (`createShadowStudentCore`).
 * `claimUrl` = `${NEXT_PUBLIC_NIVEL_URL}/invite/{token}`, её и шарим/копируем.
 */
@Serializable
data class ShadowStudentResponse(
    val ok: Boolean = true,
    val studentId: String,
    val claimUrl: String,
    val claimToken: String,
    val expiresAt: String? = null,
)

/** Тренировочная сессия ученика. Источник — `sessions`. */
@Serializable
data class SessionDto(
    val id: String,
    @SerialName("goal_id") val goalId: String? = null,
    @SerialName("session_number") val sessionNumber: Int,
    @SerialName("trainer_notes") val trainerNotes: String? = null,
    @SerialName("student_insight") val studentInsight: String? = null,
    val status: String,
    @SerialName("trainer_review_completed") val trainerReviewCompleted: Boolean = false,
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Инсайт-карточка (разбор ошибки). Источник — `insight_cards`. */
@Serializable
data class InsightCardDto(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("trainer_id") val trainerId: String? = null,
    val title: String? = null,
    val body: String? = null,
    val quote: String? = null,
    @SerialName("front_text") val frontText: String? = null,
    @SerialName("context_text") val contextText: String? = null,
    val tags: List<String>? = null,
    val source: String? = null,
    @SerialName("trainer_status") val trainerStatus: String? = null,
    @SerialName("student_decision") val studentDecision: String? = null,
    val position: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Тело запроса обмена Firebase ID token на bearer-сессию.
 * `POST /api/v1/auth/token` (эндпоинт A2 в репо NIVEL).
 */
@Serializable
data class TokenRequest(
    val idToken: String,
    val claimToken: String? = null,
)

/**
 * Ответ `POST /api/v1/auth/token`: `{ ok, token, user, expiresIn }`.
 * `token` — HMAC-JWT (тот же, что веб-кука `__session`), кладётся в DataStore
 * и шлётся как `Authorization: Bearer`.
 */
@Serializable
data class TokenResponse(
    val ok: Boolean = true,
    val token: String,
    val user: SessionUserDto,
    val expiresIn: Long,
)

/** Профиль пользователя из сессии (SessionUser в `src/lib/auth/session.ts`). */
@Serializable
data class SessionUserDto(
    val id: String,
    val email: String,
    @SerialName("firebase_uid") val firebaseUid: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: String,
)

// -----------------------------------------------------------------------------
// B5 (#8) — профиль ученика (просмотр): цели + сессии + мастер-план.
// Контракт сверен по реальным core-функциям NIVEL
// (`src/lib/core/trainerReads.ts`: getStudentDetailCore, getMasterPlanCore).
// Добавлено в конец файла, чтобы минимизировать diff в общем файле.
// -----------------------------------------------------------------------------

/**
 * Ответ `GET /api/v1/students/{id}` — профиль ученика с целями и сессиями
 * (`StudentDetail` из `getStudentDetailCore`). Не обёрнут — объект отдаётся как есть.
 */
@Serializable
data class StudentDetailResponse(
    val id: String,
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val goals: List<GoalDto> = emptyList(),
    val sessions: List<StudentSessionDto> = emptyList(),
)

/** Цель ученика в профиле. Заголовок цели = `custom_problem` (как в вебе). */
@Serializable
data class GoalDto(
    val id: String,
    @SerialName("custom_problem") val customProblem: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Сессия в профиле ученика. Отдельный DTO от [SessionDto]: контракт
 * `getStudentDetailCore` отдаёт `session_number` как nullable и без
 * trainer_notes/insight — переиспользовать строгий [SessionDto] нельзя.
 */
@Serializable
data class StudentSessionDto(
    val id: String,
    @SerialName("goal_id") val goalId: String? = null,
    @SerialName("session_number") val sessionNumber: Int? = null,
    val status: String,
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Ответ `GET /api/v1/students/{id}/master-plan` — обёртка `{ plan }`,
 * `plan: null` когда у ученика ещё нет плана (`getMasterPlanCore`).
 */
@Serializable
data class MasterPlanResponse(
    val plan: MasterPlanDto? = null,
)

/** Мастер-план: секции по порядку, у каждой — пункты. */
@Serializable
data class MasterPlanDto(
    val id: String,
    val sections: List<MasterPlanSectionDto> = emptyList(),
)

@Serializable
data class MasterPlanSectionDto(
    val id: String,
    val title: String,
    val category: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val items: List<MasterPlanItemDto> = emptyList(),
)

@Serializable
data class MasterPlanItemDto(
    val id: String,
    val title: String,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

// -----------------------------------------------------------------------------
// E2 (#25) — создание цели для ученика (write A5) + справочник проблем (read A3).
// Добавлено в конец файла, чтобы минимизировать diff в общем DTO.
// -----------------------------------------------------------------------------

/**
 * Ответ `GET /api/v1/reference` (A3, `src/app/api/v1/reference/route.ts`).
 * Локализован по `?lang=ru|en`. Нам для целей нужны только `problems`
 * (+ категории для группировки); навыки/упражнения здесь не используем,
 * поэтому в DTO не объявляем (kotlinx игнорирует лишние поля).
 */
@Serializable
data class ReferenceResponse(
    @SerialName("problem_categories") val problemCategories: List<ProblemCategoryDto> = emptyList(),
    val problems: List<ProblemDto> = emptyList(),
)

@Serializable
data class ProblemCategoryDto(
    val id: Int,
    val name: String = "",
    @SerialName("sort_order") val sortOrder: Int? = null,
)

@Serializable
data class ProblemDto(
    val id: Int,
    @SerialName("category_id") val categoryId: Int,
    val name: String = "",
    @SerialName("sort_order") val sortOrder: Int? = null,
)

/**
 * Тело `POST /api/v1/students/{id}/goals` (A5, `createGoalForStudentCore`).
 * Хотя бы одно из полей должно быть задано: либо привязка к проблеме из
 * справочника (`problemId`), либо свободный текст (`customProblem`) — как в
 * вебе (`InlineGoalCreator`). Оба поля nullable на сервере.
 */
@Serializable
data class CreateGoalRequest(
    val problemId: Int? = null,
    val customProblem: String? = null,
)

/** Ответ создания цели — `{ ok, goalId }`, 201. */
@Serializable
data class CreateGoalResponse(
    val ok: Boolean = true,
    val goalId: String? = null,
)

// -----------------------------------------------------------------------------
// E5 (#28) — редактирование мастер-плана: создать план, секции и пункты (write A5).
// Чтение плана уже есть (MasterPlanResponse/MasterPlanDto, B5).
// -----------------------------------------------------------------------------

/** Общий ответ create-операций мастер-плана — `{ ok, id }`, 201. */
@Serializable
data class CreatedResponse(
    val ok: Boolean = true,
    val id: String? = null,
)

/**
 * Тело `POST /api/v1/students/{id}/master-plan/sections`. `category` ∈
 * {strength, technique, tactics, custom} (валидируется сервером).
 */
@Serializable
data class AddMasterPlanSectionRequest(
    val planId: String,
    val title: String,
    val category: String,
    val sortOrder: Int? = null,
)

/** Тело `POST /api/v1/students/{id}/master-plan/sections/{sectionId}/items`. */
@Serializable
data class AddMasterPlanItemRequest(
    val title: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val sortOrder: Int? = null,
)
