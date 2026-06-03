package com.nivel.trainer.domain

/*
 * Доменный слой — модели, независимые от Android/сети/Room.
 * UI работает с этими типами, репозитории мапят в них из Room-entity.
 * Базовые сущности B3: ученики, сессии, карточки (расширяются дальше).
 */

/** Ученик тренера. */
data class Student(
    val id: String,
    val fullName: String?,
    val email: String?,
    val avatarUrl: String?,
)

/** Тренировочная сессия ученика. */
data class TrainingSession(
    val id: String,
    val studentId: String,
    val goalId: String?,
    val sessionNumber: Int,
    val trainerNotes: String?,
    val studentInsight: String?,
    val status: String,
    val trainerReviewCompleted: Boolean,
    val scheduledAt: String?,
    val completedAt: String?,
    val createdAt: String?,
)

/** Инсайт-карточка (разбор ошибки) в рамках сессии. */
data class InsightCard(
    val id: String,
    val sessionId: String,
    val studentId: String?,
    val trainerId: String?,
    val title: String?,
    val body: String?,
    val quote: String?,
    val frontText: String?,
    val contextText: String?,
    val tags: List<String>,
    val source: String?,
    val trainerStatus: String?,
    val studentDecision: String?,
    val position: Int,
    val createdAt: String?,
)
