package com.nivel.trainer.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room-entity кэша чтения. Источник правды — сервер; здесь только последний снимок
 * ответа API, чтобы показывать данные оффлайн и мгновенно при старте.
 * Поля выровнены по DTO/домену (ученики, сессии, карточки).
 */

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey val id: String,
    val fullName: String?,
    val email: String?,
    val avatarUrl: String?,
    val createdAt: String?,
)

@Entity(
    tableName = "sessions",
    indices = [Index("studentId")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    /** К какому ученику относится сессия — приходит из пути запроса (A3). */
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

@Entity(
    tableName = "insight_cards",
    indices = [Index("sessionId")],
)
data class InsightCardEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val studentId: String?,
    val trainerId: String?,
    val title: String?,
    val body: String?,
    val quote: String?,
    val frontText: String?,
    val contextText: String?,
    /** Теги храним как строку (CSV) — Room без TypeConverter; пусто = нет тегов. */
    val tags: String?,
    val source: String?,
    val trainerStatus: String?,
    val studentDecision: String?,
    val position: Int,
    val createdAt: String?,
)
