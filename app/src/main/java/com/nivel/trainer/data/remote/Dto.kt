package com.nivel.trainer.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO ответов REST API бэкенда (`/api/v1/*`, репо profeshionalx-lang/NIVEL).
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

/** Ученик тренера. Источник — `profiles` (role=student), отдаётся read-endpoint'ом A3. */
@Serializable
data class StudentDto(
    val id: String,
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
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
