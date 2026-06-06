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

// Инсайт-карточки сессии приходят из `…/insight-cards` в обёртке `{ cards }`
// без session_id/student_id/trainer_id — их DTO определён ниже как
// [SessionInsightCardDto] (блок B6). Прежний широкий `InsightCardDto` удалён,
// т.к. реальный эндпоинт его шейп не отдаёт.

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
// E3 (#26) — управление учеником: правка профиля + статус приглашения.
// Профиль: PATCH /api/v1/students/{id} (updateStudentProfileCore). Статус
// приглашения сверен по web `getStudentInvite` (token/status/claimed_at).
// -----------------------------------------------------------------------------

/**
 * Тело `PATCH /api/v1/students/{id}` — правка профиля ученика. Оба поля
 * опциональны; пустую строку шлём как null (как `|| null` в вебе `updateStudentProfile`).
 */
@Serializable
data class UpdateStudentProfileRequest(
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * Статус приглашения ученика. Шейп по web `getStudentInvite`:
 * `{ token, status: none|pending|claimed|revoked, claimed_at }`.
 * TODO(#Фундамент): GET статуса в `/api/v1` ещё нет — контракт по web-экшену;
 * до появления эндпоинта вызов вернёт 404 и трактуется как «статус неизвестен».
 */
@Serializable
data class StudentInviteResponse(
    val token: String? = null,
    val status: String = "none",
    @SerialName("claimed_at") val claimedAt: String? = null,
)

// E1 (#24) — создание тренировки без упражнений (POST /api/v1/sessions/for-student).
@Serializable
data class CreateSessionForStudentRequest(
    @SerialName("studentId") val studentId: String,
    @SerialName("goalId") val goalId: String,
    @SerialName("scheduledAt") val scheduledAt: String? = null,
    @SerialName("completedAt") val completedAt: String? = null,
    @SerialName("trainerNotes") val trainerNotes: String? = null,
    @SerialName("status") val status: String? = null,
)

@Serializable
data class CreateSessionForStudentResponse(
    val ok: Boolean,
    @SerialName("sessionId") val sessionId: String,
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

// -----------------------------------------------------------------------------
// C3 (#12) — аудио-конвейер: signed upload URL + запуск транскрипции (A4).
// Контракт сверен по route-файлам NIVEL:
//   POST /api/v1/sessions/{id}/audio/upload-url  { ext? } -> { uploadUrl, storagePath }
//   POST /api/v1/sessions/{id}/transcribe        { storagePath } -> { ok }
// -----------------------------------------------------------------------------

/** Тело `…/audio/upload-url`. `ext` — расширение файла записи (по умолчанию m4a). */
@Serializable
data class UploadUrlRequest(
    val ext: String = "m4a",
)

/**
 * Ответ `…/audio/upload-url` (`requestAudioUploadUrlCore`): абсолютный Supabase
 * signed-URL для прямого PUT файла + путь в bucket для последующего transcribe.
 */
@Serializable
data class UploadUrlResponse(
    val uploadUrl: String,
    val storagePath: String,
)

/** Тело `…/transcribe`: путь загруженного файла, полученный из upload-url. */
@Serializable
data class TranscribeRequest(
    val storagePath: String,
)

/** Ответ `…/transcribe`: `{ ok }` при успешном запуске/завершении расшифровки. */
@Serializable
data class TranscribeResponse(
    val ok: Boolean = true,
)

// -----------------------------------------------------------------------------
// B6 (#9) — карточка тренировки (просмотр): детали + статус аудио + карточки.
// Контракт сверен по route-файлам NIVEL: sessions/[id], .../transcript/status,
// .../insight-cards (core: getSessionDetailCore, getTranscriptStatusCore,
// getSessionInsightCardsCore). Добавлено в конец файла, чтобы минимизировать diff.
// -----------------------------------------------------------------------------

/**
 * Ответ `GET /api/v1/sessions/{id}` (`getSessionDetailCore`). `session_number`
 * nullable (как в core). Поле `exercises` НЕ объявляем: на экране сессии
 * упражнения не рендерятся (решение по #9, как веб-страница сессии), а
 * `ignoreUnknownKeys=true` молча пропустит его в ответе. `created_at` эндпоинт
 * не возвращает — дату на экране берём из `completed_at`/`scheduled_at`.
 */
@Serializable
data class SessionDetailResponse(
    val id: String,
    @SerialName("goal_id") val goalId: String? = null,
    @SerialName("session_number") val sessionNumber: Int? = null,
    val status: String,
    @SerialName("trainer_notes") val trainerNotes: String? = null,
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    /** D5 (#23): флаг финального ревью тренера — кнопка «Завершить разбор». */
    @SerialName("trainer_review_completed") val trainerReviewCompleted: Boolean = false,
)

/** Тело `POST /api/v1/sessions/{id}/review-complete` (D5). Body необязателен — по умолчанию завершает. */
@Serializable
data class ReviewCompleteRequest(
    val completed: Boolean = true,
)

/** Тело `POST /api/v1/sessions/{id}/cards/reorder` (D4). Задаёт новый порядок карточек по id. */
@Serializable
data class ReorderCardsRequest(
    @SerialName("orderedIds") val orderedIds: List<String>,
)

/**
 * Ответ `GET /api/v1/sessions/{id}/transcript/status` (`getTranscriptStatusCore`):
 * статус транскрипции + анализа. 404 — записи/транскрипта ещё нет.
 */
@Serializable
data class SessionTranscriptStatusResponse(
    val status: String,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("analysis_status") val analysisStatus: String = "idle",
    @SerialName("analysis_error") val analysisError: String? = null,
)

/** Ответ `GET /api/v1/sessions/{id}/insight-cards` — обёртка `{ cards }`. */
@Serializable
data class SessionInsightCardsResponse(
    val cards: List<SessionInsightCardDto> = emptyList(),
)

/**
 * Карточка из `getSessionInsightCardsCore`: без session_id/student_id/trainer_id
 * (их даёт путь/контекст запроса). session_id для кэша/домена подставляем из пути.
 */
@Serializable
data class SessionInsightCardDto(
    val id: String,
    val title: String? = null,
    val body: String? = null,
    val quote: String? = null,
    val tags: List<String>? = null,
    @SerialName("front_text") val frontText: String? = null,
    @SerialName("context_text") val contextText: String? = null,
    val source: String? = null,
    @SerialName("trainer_status") val trainerStatus: String? = null,
    @SerialName("student_decision") val studentDecision: String? = null,
    val position: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

// -----------------------------------------------------------------------------
// D2 (#20) — создание инсайтов: вставка (paste) + авто-генерация (write A5).
// Контракт сверен по route-файлам NIVEL: insights/paste, insights/generate
// (core: pasteInsightsFromClaudeCore, generateAiInsightsCore).
// -----------------------------------------------------------------------------

/** Тело `POST /api/v1/sessions/{id}/insights/paste` — markdown-ответ от Claude. */
@Serializable
data class PasteInsightsRequest(
    val markdown: String,
)

/** Успешный ответ paste/generate: `{ ok, count }` — сколько draft-карточек создано. */
@Serializable
data class InsightsResultResponse(
    val ok: Boolean = true,
    val count: Int = 0,
)

/**
 * Тело ошибки paste/generate (400 — парсинг/прекондишн, 502 — сбой анализа):
 * `{ error, line? }`. `line` есть только у ошибок парсинга вставленного markdown.
 */
@Serializable
data class InsightsErrorResponse(
    val error: String? = null,
    val line: Int? = null,
)

// -----------------------------------------------------------------------------
// D1 (#19) — транскрипт тренировки (просмотр, выгрузка).
// Контракт сверен по веб-эталону NIVEL (src/app/sessions/[id]/transcript/page.tsx
// + TranscriptView.tsx) и таблице transcripts (миграция 010_session_transcripts).
// Веб читает raw_text/segments_json/status напрямую из Supabase; /api/v1-эндпоинта
// с ТЕКСТОМ транскрипта в NIVEL ещё нет (есть только .../transcript/status, отдающий
// только статус). DTO выровнен на тот же шейп строки transcripts — реальный
// эндпоинт Фундамента должен отдавать его как есть. Добавлено в конец файла.
// -----------------------------------------------------------------------------

/**
 * Ответ GET /api/v1/sessions/{id}/transcript — строка transcripts один-в-один с
 * вебом: статус обработки + сырой текст + сегменты с таймкодами + длительность.
 *
 * status: "processing" | "ready" | "failed" (значения из transcribeSessionCore).
 * segments — массив сегментов Whisper; пуст для processing/failed.
 */
@Serializable
data class TranscriptResponse(
    val status: String,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("raw_text") val rawText: String? = null,
    @SerialName("segments_json") val segments: List<TranscriptSegmentDto> = emptyList(),
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
)

/**
 * Сегмент транскрипта Whisper (как в TranscriptView.tsx): таймкоды start/end в
 * секундах, текст и avg_logprob — оценка уверенности (низкая → подсветка в UI).
 */
@Serializable
data class TranscriptSegmentDto(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    @SerialName("avg_logprob") val avgLogprob: Double? = null,
)

// -----------------------------------------------------------------------------
// G2 (#31) — регистрация FCM-токена устройства для push-уведомлений.
// -----------------------------------------------------------------------------

/**
 * Тело `POST /api/v1/devices/token`: FCM-токен устройства + платформа.
 * Сервер апсертит по (user_id, platform) — один токен на платформу.
 */
@Serializable
data class DeviceTokenRequest(
    val token: String,
    val platform: String = "android",
)
