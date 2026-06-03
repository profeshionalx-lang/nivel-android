package com.nivel.trainer.data

import com.nivel.trainer.data.local.InsightCardEntity
import com.nivel.trainer.data.local.SessionEntity
import com.nivel.trainer.data.local.StudentEntity
import com.nivel.trainer.data.remote.InsightCardDto
import com.nivel.trainer.data.remote.SessionDto
import com.nivel.trainer.data.remote.StudentDto
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.Student
import com.nivel.trainer.domain.TrainingSession

/**
 * Мапперы DTO → Entity (запись в кэш) и Entity → domain (выдача в UI).
 * Direction всегда один: сервер → Room → UI. Обратно (UI → сервер) не маппим —
 * запись идёт отдельными write-вызовами, не через кэш.
 */

private const val TAGS_SEPARATOR = "" // unit separator — не встречается в тексте тегов

// --- Students ---

fun StudentDto.toEntity() = StudentEntity(
    id = id,
    fullName = fullName,
    email = email,
    avatarUrl = avatarUrl,
    createdAt = createdAt,
)

fun StudentEntity.toDomain() = Student(
    id = id,
    fullName = fullName,
    email = email,
    avatarUrl = avatarUrl,
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

fun InsightCardDto.toEntity() = InsightCardEntity(
    id = id,
    sessionId = sessionId,
    studentId = studentId,
    trainerId = trainerId,
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
