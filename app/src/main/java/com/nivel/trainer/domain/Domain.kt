package com.nivel.trainer.domain

import kotlinx.serialization.Serializable

/*
 * Доменный слой — модели, независимые от Android/сети/Room.
 * UI работает с этими типами, репозитории мапят в них из Room-entity.
 * Базовые сущности B3: ученики, сессии, карточки (расширяются дальше).
 *
 * `@Serializable` (G3, #32): модели экранов чтения сериализуются в JSON для
 * Room read-cache (оффлайн-чтение). Сериализация — деталь кэша, не контракт API;
 * источник правды остаётся сервер.
 */

/** Ученик тренера. */
@Serializable
data class Student(
    val id: String,
    val fullName: String?,
    val email: String?,
    val avatarUrl: String?,
    /** Кол-во активных целей (счётчик с сервера, показывается в карточке списка). */
    val activeGoals: Int = 0,
    /** Кол-во сессий ученика (счётчик с сервера). */
    val totalSessions: Int = 0,
)

/**
 * Результат создания теневого ученика / перевыпуска приглашения: claim-ссылка,
 * которой тренер делится с учеником (share-intent / копирование).
 */
data class ShadowStudent(
    val studentId: String,
    val claimUrl: String,
    val claimToken: String,
    val expiresAt: String?,
)

/** Тренировочная сессия ученика. */
@Serializable
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
@Serializable
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

// --- B5 (#8): профиль ученика (просмотр) ---

/** Цель ученика в профиле. Заголовок = [customProblem] (как в вебе). */
@Serializable
data class Goal(
    val id: String,
    val customProblem: String?,
    val status: String,
    val createdAt: String?,
)

/**
 * Проблема из справочника (E2, #25). Используется при создании цели: тренер
 * может привязать цель к проблеме из списка вместо/вместе со свободным текстом.
 * Локализованное имя приходит с сервера (`?lang`). [categoryId] — для возможной
 * группировки; веб показывает плоский список, отсортированный по `sort_order`.
 */
data class Problem(
    val id: Int,
    val categoryId: Int,
    val name: String,
)

/**
 * Сессия в карточке профиля ученика. Легче [TrainingSession]: контракт
 * `/students/{id}` отдаёт только id/номер/статус/даты, `sessionNumber` nullable.
 */
@Serializable
data class StudentSession(
    val id: String,
    val goalId: String?,
    val sessionNumber: Int?,
    val status: String,
    val scheduledAt: String?,
    val completedAt: String?,
    val createdAt: String?,
)

/** Профиль ученика: базовая инфо + его цели и сессии. */
@Serializable
data class StudentProfile(
    val id: String,
    val fullName: String?,
    val email: String?,
    val avatarUrl: String?,
    val goals: List<Goal>,
    val sessions: List<StudentSession>,
    /** Мастер-план ученика; null — плана ещё нет. */
    val masterPlan: MasterPlan?,
    /** Приглашение ученика (E3); null — статус ещё не загружен. */
    val invite: StudentInvite? = null,
)

/** Мастер-план ученика: упорядоченные секции с пунктами. */
@Serializable
data class MasterPlan(
    val id: String,
    val sections: List<MasterPlanSection>,
)

@Serializable
data class MasterPlanSection(
    val id: String,
    val title: String,
    val category: String?,
    val items: List<MasterPlanItem>,
)

@Serializable
data class MasterPlanItem(
    val id: String,
    val title: String,
    val description: String?,
    val imageUrl: String?,
)

// --- E3 (#26): управление учеником — приглашение ---

/** Статус приглашения ученика (как в вебе). `UNKNOWN` — статус ещё не загружен. */
@Serializable
enum class InviteStatus { NONE, PENDING, CLAIMED, REVOKED, UNKNOWN }

/**
 * Состояние приглашения ученика: статус, claim-ссылка (для pending) и время
 * принятия (для claimed). Тренер делится ссылкой, перевыпускает или отзывает её.
 */
@Serializable
data class StudentInvite(
    val status: InviteStatus,
    val claimUrl: String?,
    val claimedAt: String?,
)

// --- B6 (#9): карточка тренировки (просмотр) ---

/**
 * Детали сессии для экрана карточки тренировки. Упражнения в модель не тянем —
 * на экране сессии они не показываются (решение по #9, как веб-страница сессии).
 * `sessionNumber` nullable (контракт `getSessionDetailCore`).
 */
@Serializable
data class SessionDetail(
    val id: String,
    val goalId: String?,
    val sessionNumber: Int?,
    val status: String,
    val trainerNotes: String?,
    val scheduledAt: String?,
    val completedAt: String?,
    /** D5 (#23): тренер уже нажал «Завершить разбор» — кнопка показывается как inactive. */
    val trainerReviewCompleted: Boolean = false,
)

/**
 * Статус обработки аудио сессии: транскрипция + анализ карточек.
 * На экране `null` означает «записи ещё нет» (эндпоинт статуса отдал 404).
 */
@Serializable
data class SessionAudioStatus(
    /** processing | ready | failed (статус транскрипции). */
    val transcriptStatus: String,
    val transcriptError: String?,
    /** idle | processing | ready | failed (статус AI-анализа). */
    val analysisStatus: String,
    val analysisError: String?,
)

/**
 * Полное состояние экрана карточки тренировки (B6): детали + статус аудио +
 * инсайт-карточки сессии. Карточки — переиспользуем доменный [InsightCard].
 */
@Serializable
data class SessionOverview(
    val detail: SessionDetail,
    val audio: SessionAudioStatus?,
    val cards: List<InsightCard>,
)

// --- D1 (#19): транскрипт тренировки (просмотр, выгрузка) ---

/** Статус обработки транскрипта (значения сервера из transcribeSessionCore). */
@Serializable
enum class TranscriptStatus {
    PROCESSING,
    READY,
    FAILED;

    companion object {
        /** Парсит строку статуса с сервера; неизвестное трактуем как FAILED. */
        fun from(raw: String?): TranscriptStatus = when (raw?.lowercase()) {
            "processing" -> PROCESSING
            "ready" -> READY
            else -> FAILED
        }
    }
}

/**
 * Транскрипт тренировочной сессии: статус + сырой текст + сегменты с таймкодами.
 * Источник правды — сервер; экран точечный, без Room-кэша (как профиль ученика).
 */
@Serializable
data class Transcript(
    val status: TranscriptStatus,
    val errorMessage: String?,
    val rawText: String?,
    val segments: List<TranscriptSegment>,
    val durationSeconds: Int?,
)

/** Сегмент транскрипта: таймкоды (сек), текст и оценка уверенности avg_logprob. */
@Serializable
data class TranscriptSegment(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val avgLogprob: Double?,
)

// --- G3 (#32): оффлайн-чтение ---

/**
 * Результат чтения с пометкой «данные из кэша» (G3, #32). [stale] = true, когда
 * сеть недоступна и репозиторий отдал последний сохранённый снимок из Room —
 * UI показывает индикатор оффлайна. [stale] = false — свежий ответ с сервера.
 */
data class Cached<T>(
    val value: T,
    val stale: Boolean,
)
