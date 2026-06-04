package com.nivel.trainer.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit-описание REST API бэкенда Nivel (эндпоинты `api/v1/...`, репо profeshionalx-lang/NIVEL).
 *
 * Контракт зафиксирован дизайн-доком; эндпоинты добавляются по мере готовности
 * («Фундамент»: A2 auth-обмен, A3–A5 read/write). При ошибке сети репозиторий
 * остаётся на кэше Room (см. AGENTS.md «Зависимость от бэкенда»).
 */
interface NivelApi {

    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    /**
     * Обмен Firebase ID token на bearer-сессию (A2). Используется и в WebView-флоу
     * Гречки, и в Google Sign-In fallback: на входе всегда Firebase ID token.
     */
    @POST("api/v1/auth/token")
    suspend fun exchangeToken(@Body body: TokenRequest): TokenResponse

    /**
     * Список учеников тренера (A3). Реальный контракт — обёртка `{ students: [...] }`
     * со счётчиками `active_goals`/`total_sessions` (сверено по
     * `src/app/api/v1/students/route.ts`). Trainer-only, авторизация по bearer.
     */
    @GET("api/v1/students")
    suspend fun getStudents(): StudentsResponse

    // TODO(#A3): подтвердить путь/шейп сессий ученика.
    @GET("api/v1/students/{studentId}/sessions")
    suspend fun getStudentSessions(@Path("studentId") studentId: String): List<SessionDto>

    // TODO(#A3): подтвердить путь/шейп инсайт-карточек сессии.
    @GET("api/v1/sessions/{sessionId}/cards")
    suspend fun getSessionCards(@Path("sessionId") sessionId: String): List<InsightCardDto>

    // ---------------------------------------------------------------------------
    // B4 (#7) — создание теневого ученика и приглашение (write A5).
    // Добавлено в конец интерфейса, чтобы минимизировать diff в общем файле.
    // ---------------------------------------------------------------------------

    /**
     * Создать теневого ученика (`POST /api/v1/students`, 201). Тело `{ full_name }`.
     * Возвращает claim-ссылку приглашения для шаринга. Trainer-only.
     */
    @POST("api/v1/students")
    suspend fun createStudent(@Body body: CreateStudentRequest): ShadowStudentResponse

    /**
     * Перевыпустить приглашение для незаклеймленного теневого ученика
     * (`POST /api/v1/students/{id}/invite/regenerate`). Возвращает новую claim-ссылку.
     */
    @POST("api/v1/students/{studentId}/invite/regenerate")
    suspend fun regenerateInvite(@Path("studentId") studentId: String): ShadowStudentResponse

    /**
     * Отозвать приглашение ученика (`POST /api/v1/students/{id}/invite/revoke`).
     * Старая ссылка перестаёт работать. Ответ `{ ok }`.
     */
    @POST("api/v1/students/{studentId}/invite/revoke")
    suspend fun revokeInvite(@Path("studentId") studentId: String): OkResponse

    // ---------------------------------------------------------------------------
    // B5 (#8) — профиль ученика (просмотр): цели + сессии + мастер-план (read A3).
    // ---------------------------------------------------------------------------

    /**
     * Профиль ученика с целями и сессиями (`GET /api/v1/students/{id}`).
     * Trainer-only; тренер должен владеть учеником. 404 — нет такого ученика.
     */
    @GET("api/v1/students/{studentId}")
    suspend fun getStudentDetail(@Path("studentId") studentId: String): StudentDetailResponse

    /**
     * Мастер-план ученика (`GET /api/v1/students/{id}/master-plan`).
     * Обёртка `{ plan }`; `plan: null` когда плана ещё нет.
     */
    @GET("api/v1/students/{studentId}/master-plan")
    suspend fun getStudentMasterPlan(@Path("studentId") studentId: String): MasterPlanResponse

    // ---------------------------------------------------------------------------
    // E3 (#26) — управление учеником: правка профиля + статус приглашения.
    // regenerate/revoke объявлены выше (B4). Здесь — PATCH профиля и GET статуса.
    // ---------------------------------------------------------------------------

    /**
     * Правка профиля ученика (`PATCH /api/v1/students/{id}`), тело `{ full_name, avatar_url }`.
     * Trainer-only; тренер должен владеть учеником. Ответ `{ ok }`.
     */
    @PATCH("api/v1/students/{studentId}")
    suspend fun updateStudentProfile(
        @Path("studentId") studentId: String,
        @Body body: UpdateStudentProfileRequest,
    ): OkResponse

    /**
     * Статус приглашения ученика (`GET /api/v1/students/{id}/invite`).
     * TODO(#Фундамент): GET-эндпоинта статуса в `/api/v1` пока НЕТ — путь/шейп заданы
     * по web `getStudentInvite`; до появления эндпоинта вызов вернёт 404, репозиторий
     * трактует это как «статус неизвестен» (best-effort, экран не падает).
     */
    @GET("api/v1/students/{studentId}/invite")
    suspend fun getStudentInvite(@Path("studentId") studentId: String): StudentInviteResponse
}
