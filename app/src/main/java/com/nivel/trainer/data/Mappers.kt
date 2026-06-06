package com.nivel.trainer.data

import com.nivel.trainer.data.local.InsightCardEntity
import com.nivel.trainer.data.local.SessionEntity
import com.nivel.trainer.data.local.StudentEntity
import com.nivel.trainer.data.remote.GoalDto
import com.nivel.trainer.data.remote.MasterPlanDto
import com.nivel.trainer.data.remote.MasterPlanItemDto
import com.nivel.trainer.data.remote.MasterPlanSectionDto
import com.nivel.trainer.data.remote.ProblemDto
import com.nivel.trainer.data.remote.SessionDetailResponse
import com.nivel.trainer.data.remote.SessionDto
import com.nivel.trainer.data.remote.SessionInsightCardDto
import com.nivel.trainer.data.remote.SessionTranscriptStatusResponse
import com.nivel.trainer.data.remote.ShadowStudentResponse
import com.nivel.trainer.data.remote.StudentDetailResponse
import com.nivel.trainer.data.remote.StudentDto
import com.nivel.trainer.data.remote.StudentInviteResponse
import com.nivel.trainer.data.remote.StudentSessionDto
import com.nivel.trainer.data.remote.TranscriptResponse
import com.nivel.trainer.data.remote.TranscriptSegmentDto
import com.nivel.trainer.domain.Goal
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.InviteStatus
import com.nivel.trainer.domain.MasterPlan
import com.nivel.trainer.domain.MasterPlanItem
import com.nivel.trainer.domain.MasterPlanSection
import com.nivel.trainer.domain.Problem
import com.nivel.trainer.domain.SessionAudioStatus
import com.nivel.trainer.domain.SessionDetail
import com.nivel.trainer.domain.ShadowStudent
import com.nivel.trainer.domain.Student
import com.nivel.trainer.domain.StudentInvite
import com.nivel.trainer.domain.StudentProfile
import com.nivel.trainer.domain.StudentSession
import com.nivel.trainer.domain.TrainingSession
import com.nivel.trainer.domain.Transcript
import com.nivel.trainer.domain.TranscriptSegment
import com.nivel.trainer.domain.TranscriptStatus

/**
 * Мапперы DTO → Entity (запись в кэш) и Entity → domain (выдача в UI).
 * Direction всегда один: сервер → Room → UI. Обратно (UI → сервер) не маппим —
 * запись идёт отдельными write-вызовами, не через кэш.
 */

// ASCII unit separator (U+001F) — управляющий символ, не встречается в тексте тегов.
private const val TAGS_SEPARATOR = "\u001F"

// --- Students ---

fun StudentDto.toEntity() = StudentEntity(
    id = id,
    fullName = fullName,
    email = email,
    avatarUrl = avatarUrl,
    activeGoals = activeGoals,
    totalSessions = totalSessions,
    createdAt = createdAt,
)

fun StudentEntity.toDomain() = Student(
    id = id,
    fullName = fullName,
    email = email,
    avatarUrl = avatarUrl,
    activeGoals = activeGoals,
    totalSessions = totalSessions,
)

/** Ответ создания теневого ученика / invite → доменная claim-модель для шаринга. */
fun ShadowStudentResponse.toDomain() = ShadowStudent(
    studentId = studentId,
    claimUrl = claimUrl,
    claimToken = claimToken,
    expiresAt = expiresAt,
)

// --- Sessions ---

/** studentId приходит из пути запроса (DTO его не содержит). */
fun SessionDto.toEntity(studentId: String) = SessionEntity(
    id = id,
    studentId = studentId,
    goalId = goalId,
    sessionNumber = sessionNumber,
    trainerNotes = trainerNotes,
    studentInsight = studentInsight,
    status = status,
    trainerReviewCompleted = trainerReviewCompleted,
    scheduledAt = scheduledAt,
    completedAt = completedAt,
    createdAt = createdAt,
)

fun SessionEntity.toDomain() = TrainingSession(
    id = id,
    studentId = studentId,
    goalId = goalId,
    sessionNumber = sessionNumber,
    trainerNotes = trainerNotes,
    studentInsight = studentInsight,
    status = status,
    trainerReviewCompleted = trainerReviewCompleted,
    scheduledAt = scheduledAt,
    completedAt = completedAt,
    createdAt = createdAt,
)

// --- Insight cards ---
// DTO->Entity маппер карточки — это [SessionInsightCardDto.toEntity] ниже (блок B6):
// карточки приходят из `…/insight-cards` без session_id, поэтому он берётся из пути.

fun InsightCardEntity.toDomain() = InsightCard(
    id = id,
    sessionId = sessionId,
    studentId = studentId,
    trainerId = trainerId,
    title = title,
    body = body,
    quote = quote,
    frontText = frontText,
    contextText = contextText,
    tags = tags?.takeIf { it.isNotBlank() }?.split(TAGS_SEPARATOR) ?: emptyList(),
    source = source,
    trainerStatus = trainerStatus,
    studentDecision = studentDecision,
    position = position,
    createdAt = createdAt,
)

// --- B5 (#8): профиль ученика (DTO → domain напрямую, без Room) ---
// Точечный экран чтения; кэш не обязателен по acceptance, источник правды — сервер.

fun GoalDto.toDomain() = Goal(
    id = id,
    customProblem = customProblem,
    status = status,
    createdAt = createdAt,
)

/** E2 (#25): проблема справочника → доменная модель для пикера при создании цели. */
fun ProblemDto.toDomain() = Problem(
    id = id,
    categoryId = categoryId,
    name = name,
)

fun StudentSessionDto.toDomain() = StudentSession(
    id = id,
    goalId = goalId,
    sessionNumber = sessionNumber,
    status = status,
    scheduledAt = scheduledAt,
    completedAt = completedAt,
    createdAt = createdAt,
)

fun MasterPlanItemDto.toDomain() = MasterPlanItem(
    id = id,
    title = title,
    description = description,
    imageUrl = imageUrl,
)

fun MasterPlanSectionDto.toDomain() = MasterPlanSection(
    id = id,
    title = title,
    category = category,
    // Пункты упорядочиваем по sort_order — сервер уже сортирует, но дублируем для надёжности.
    items = items.sortedBy { it.sortOrder }.map { it.toDomain() },
)

fun MasterPlanDto.toDomain() = MasterPlan(
    id = id,
    sections = sections.sortedBy { it.sortOrder }.map { it.toDomain() },
)

// --- D1 (#19): транскрипт (DTO → domain напрямую, без Room) ---

fun TranscriptSegmentDto.toDomain() = TranscriptSegment(
    id = id,
    start = start,
    end = end,
    text = text,
    avgLogprob = avgLogprob,
)

fun TranscriptResponse.toDomain() = Transcript(
    status = TranscriptStatus.from(status),
    errorMessage = errorMessage,
    rawText = rawText,
    // Сегменты упорядочены сервером по времени; сортируем по start для надёжности.
    segments = segments.sortedBy { it.start }.map { it.toDomain() },
    durationSeconds = durationSeconds,
)

/** Склейка detail + master-plan (+ приглашение, E3) в доменную модель профиля. */
fun StudentDetailResponse.toDomain(
    masterPlan: MasterPlanDto?,
    invite: StudentInvite? = null,
) = StudentProfile(
    id = id,
    fullName = fullName,
    email = email,
    avatarUrl = avatarUrl,
    goals = goals.map { it.toDomain() },
    sessions = sessions.map { it.toDomain() },
    masterPlan = masterPlan?.toDomain(),
    invite = invite,
)

// --- E3 (#26): статус приглашения ---

/**
 * Статус приглашения → доменная модель. `baseUrl` — базовый URL бэкенда (он же
 * NIVEL_URL): claim-ссылку собираем как `{base}/invite/{token}` (как в web InviteBlock).
 */
fun StudentInviteResponse.toDomain(baseUrl: String): StudentInvite {
    val mapped = when (status) {
        "pending" -> InviteStatus.PENDING
        "claimed" -> InviteStatus.CLAIMED
        "revoked" -> InviteStatus.REVOKED
        "none" -> InviteStatus.NONE
        else -> InviteStatus.UNKNOWN
    }
    val claimUrl = token?.takeIf { it.isNotBlank() }?.let { "${baseUrl.trimEnd('/')}/invite/$it" }
    return StudentInvite(status = mapped, claimUrl = claimUrl, claimedAt = claimedAt)
}

// --- B6 (#9): карточка тренировки (DTO → domain, без Room) ---

fun SessionDetailResponse.toDomain() = SessionDetail(
    id = id,
    goalId = goalId,
    sessionNumber = sessionNumber,
    status = status,
    trainerNotes = trainerNotes,
    scheduledAt = scheduledAt,
    completedAt = completedAt,
    trainerReviewCompleted = trainerReviewCompleted,
)

/**
 * G3 (#32): кэширует детали сессии из API в Room-entity.
 * studentId — пустая строка (detail-эндпоинт его не возвращает; кэш читается по session id).
 */
fun SessionDetailResponse.toEntity() = SessionEntity(
    id = id,
    studentId = "",
    goalId = goalId,
    sessionNumber = sessionNumber ?: 0,
    trainerNotes = trainerNotes,
    studentInsight = null,
    status = status,
    trainerReviewCompleted = trainerReviewCompleted,
    scheduledAt = scheduledAt,
    completedAt = completedAt,
    createdAt = null,
)

/** G3 (#32): восстанавливает [SessionDetail] из кэша (Room) для offline-показа. */
fun SessionEntity.toSessionDetail() = SessionDetail(
    id = id,
    goalId = goalId,
    sessionNumber = sessionNumber,
    status = status,
    trainerNotes = trainerNotes,
    scheduledAt = scheduledAt,
    completedAt = completedAt,
    trainerReviewCompleted = trainerReviewCompleted,
)

fun SessionTranscriptStatusResponse.toDomain() = SessionAudioStatus(
    transcriptStatus = status,
    transcriptError = errorMessage,
    analysisStatus = analysisStatus,
    analysisError = analysisError,
)

/**
 * Карточка из эндпоинта `…/insight-cards` → доменный [InsightCard].
 * `sessionId` приходит из пути запроса (DTO его не содержит); student/trainer id
 * на этом экране не нужны (trainer-only просмотр своей сессии).
 */
fun SessionInsightCardDto.toDomain(sessionId: String) = InsightCard(
    id = id,
    sessionId = sessionId,
    studentId = null,
    trainerId = null,
    title = title,
    body = body,
    quote = quote,
    frontText = frontText,
    contextText = contextText,
    tags = tags ?: emptyList(),
    source = source,
    trainerStatus = trainerStatus,
    studentDecision = studentDecision,
    position = position,
    createdAt = createdAt,
)

/** Тот же DTO → Room-entity для кэша карточек (B3). `sessionId` из пути. */
fun SessionInsightCardDto.toEntity(sessionId: String) = InsightCardEntity(
    id = id,
    sessionId = sessionId,
    studentId = null,
    trainerId = null,
    title = title,
    body = body,
    quote = quote,
    frontText = frontText,
    contextText = contextText,
    tags = tags?.takeIf { it.isNotEmpty() }?.joinToString(TAGS_SEPARATOR),
    source = source,
    trainerStatus = trainerStatus,
    studentDecision = studentDecision,
    position = position,
    createdAt = createdAt,
)
