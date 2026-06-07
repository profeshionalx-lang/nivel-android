package com.nivel.trainer.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    /**
     * Инсайт-карточки сессии (`GET /api/v1/sessions/{id}/insight-cards`).
     * Обёртка `{ cards }`; карточки упорядочены по position (`getSessionInsightCardsCore`).
     * Путь/шейп исправлены по реальному route NIVEL (был стаб `…/cards` без обёртки).
     */
    @GET("api/v1/sessions/{sessionId}/insight-cards")
    suspend fun getSessionCards(@Path("sessionId") sessionId: String): SessionInsightCardsResponse

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

    // E1 (#24) — создать тренировку (Вариант А: без упражнений, POST /api/v1/sessions/for-student).
    @POST("api/v1/sessions/for-student")
    suspend fun createSessionForStudent(@Body body: CreateSessionForStudentRequest): CreateSessionForStudentResponse

    // ---------------------------------------------------------------------------
    // E2 (#25) — создание цели для ученика: справочник проблем + write-эндпоинт.
    // ---------------------------------------------------------------------------

    /**
     * Справочники для составления целей/сессий/карточек (`GET /api/v1/reference`).
     * Локализация через `?lang=ru|en`. Trainer-only. Для целей берём `problems`.
     */
    @GET("api/v1/reference")
    suspend fun getReference(@Query("lang") lang: String = "ru"): ReferenceResponse

    /**
     * Создать цель ученику (`POST /api/v1/students/{id}/goals`, 201). Тело
     * `{ problemId?, customProblem? }` — проблема из справочника и/или свой текст.
     * Trainer-only; тренер должен владеть учеником. Ответ `{ ok, goalId }`.
     */
    @POST("api/v1/students/{studentId}/goals")
    suspend fun createStudentGoal(
        @Path("studentId") studentId: String,
        @Body body: CreateGoalRequest,
    ): CreateGoalResponse

    // ---------------------------------------------------------------------------
    // E5 (#28) — редактирование мастер-плана: план, секции, пункты (write A5).
    // ---------------------------------------------------------------------------

    /** Создать пустой мастер-план ученику (`POST .../master-plan`, 201). */
    @POST("api/v1/students/{studentId}/master-plan")
    suspend fun createMasterPlan(@Path("studentId") studentId: String): CreatedResponse

    /** Добавить секцию в план (`POST .../master-plan/sections`, 201). */
    @POST("api/v1/students/{studentId}/master-plan/sections")
    suspend fun addMasterPlanSection(
        @Path("studentId") studentId: String,
        @Body body: AddMasterPlanSectionRequest,
    ): CreatedResponse

    /** Удалить секцию (и её пункты каскадом) (`DELETE .../sections/{sectionId}`). */
    @DELETE("api/v1/students/{studentId}/master-plan/sections/{sectionId}")
    suspend fun deleteMasterPlanSection(
        @Path("studentId") studentId: String,
        @Path("sectionId") sectionId: String,
    ): OkResponse

    /** Добавить пункт в секцию (`POST .../sections/{sectionId}/items`, 201). */
    @POST("api/v1/students/{studentId}/master-plan/sections/{sectionId}/items")
    suspend fun addMasterPlanItem(
        @Path("studentId") studentId: String,
        @Path("sectionId") sectionId: String,
        @Body body: AddMasterPlanItemRequest,
    ): CreatedResponse

    /** Удалить пункт мастер-плана (`DELETE .../master-plan/items/{itemId}`). */
    @DELETE("api/v1/students/{studentId}/master-plan/items/{itemId}")
    suspend fun deleteMasterPlanItem(
        @Path("studentId") studentId: String,
        @Path("itemId") itemId: String,
    ): OkResponse

    // ---------------------------------------------------------------------------
    // B6 (#9) — карточка тренировки (просмотр): детали сессии + статус аудио.
    // Карточки берутся из исправленного `getSessionCards` (…/insight-cards) выше.
    // ---------------------------------------------------------------------------

    /**
     * Детали сессии (`GET /api/v1/sessions/{id}`). Trainer-only, ownership
     * проверяется сервером; 404 — нет такой сессии у тренера.
     */
    @GET("api/v1/sessions/{sessionId}")
    suspend fun getSessionDetail(@Path("sessionId") sessionId: String): SessionDetailResponse

    /**
     * Статус обработки аудио (`GET /api/v1/sessions/{id}/transcript/status`):
     * транскрипция + анализ. 404 — записи/транскрипта ещё нет.
     */
    @GET("api/v1/sessions/{sessionId}/transcript/status")
    suspend fun getSessionTranscriptStatus(@Path("sessionId") sessionId: String): SessionTranscriptStatusResponse

    // ---------------------------------------------------------------------------
    // D1 (#19) — транскрипт тренировки (просмотр, выгрузка), read A4.
    // ---------------------------------------------------------------------------

    /**
     * Транскрипт сессии (`GET /api/v1/sessions/{id}/transcript`): статус обработки
     * + сырой текст + сегменты. Trainer-only, ownership на сервере.
     *
     * TODO(#Фундамент): в репо NIVEL есть только `.../transcript/status` (без текста).
     * Эндпоинт с raw_text/segments нужно добавить тем же шейпом, что веб читает из
     * таблицы `transcripts` (см. [TranscriptResponse]). До его появления вызов
     * вернёт 404 — экран покажет состояние ошибки, не падая.
     */
    @GET("api/v1/sessions/{sessionId}/transcript")
    suspend fun getTranscript(@Path("sessionId") sessionId: String): TranscriptResponse

    // ---------------------------------------------------------------------------
    // D5 (#23) — завершить разбор тренировки: фиксируем ревью и уведомляем ученика.
    // ---------------------------------------------------------------------------

    // ---------------------------------------------------------------------------
    // D4 (#22) — переупорядочивание карточек drag-and-drop.
    // ---------------------------------------------------------------------------

    /**
     * Переупорядочить карточки тренировки (`POST /api/v1/sessions/{id}/cards/reorder`).
     * Тело `{ orderedIds: string[] }` — новый порядок карточек. Trainer-only.
     * Ответ `{ ok: true }`.
     */
    @POST("api/v1/sessions/{sessionId}/cards/reorder")
    suspend fun reorderCards(
        @Path("sessionId") sessionId: String,
        @Body body: ReorderCardsRequest,
    ): OkResponse

    /**
     * Завершить разбор тренировки (`POST /api/v1/sessions/{id}/review-complete`).
     * Атомарный guard на сервере: повторный вызов когда `trainer_review_completed=true`
     * — no-op (zero affected rows), ответ всё равно `{ ok: true }`.
     * Уведомление ученику (Telegram) отправляется сервером при переходе false→true.
     */
    @POST("api/v1/sessions/{sessionId}/review-complete")
    suspend fun completeReview(
        @Path("sessionId") sessionId: String,
        @Body body: ReviewCompleteRequest = ReviewCompleteRequest(),
    ): OkResponse

    // ---------------------------------------------------------------------------
    // E4 (#27) — библиотека карточек-шаблонов и коллекции (read + ведение + apply).
    // Контракт read — NIVEL#200/PR#201; write-эндпоинты применения/коллекций уже есть.
    // ---------------------------------------------------------------------------

    /**
     * Библиотека карточек-шаблонов тренера (`GET /api/v1/cards`): шаблоны (дедуп по
     * template_id, со статистикой решений) + ученики для шита применения. Trainer-only.
     */
    @GET("api/v1/cards")
    suspend fun getCardLibrary(): CardLibraryResponse

    /**
     * Коллекции шаблонов тренера (`GET /api/v1/collections`). Обёртка `{ collections }`;
     * у каждой — id шаблонов и счётчик карточек. Trainer-only.
     */
    @GET("api/v1/collections")
    suspend fun getCollections(): TrainerCollectionsResponse

    /** Создать коллекцию (`POST /api/v1/collections`, 201). Тело `{ name }`, ответ `{ ok, id }`. */
    @POST("api/v1/collections")
    suspend fun createCollection(@Body body: CreateCollectionRequest): CreatedResponse

    /** Добавить шаблон в коллекцию (`POST /api/v1/collections/{id}/cards`, 201). */
    @POST("api/v1/collections/{collectionId}/cards")
    suspend fun addCardToCollection(
        @Path("collectionId") collectionId: String,
        @Body body: AddCardToCollectionRequest,
    ): OkResponse

    /** Убрать шаблон из коллекции (`DELETE /api/v1/collections/{id}/cards/{templateId}`). */
    @DELETE("api/v1/collections/{collectionId}/cards/{templateId}")
    suspend fun removeCardFromCollection(
        @Path("collectionId") collectionId: String,
        @Path("templateId") templateId: String,
    ): OkResponse

    /**
     * Применить шаблон к сессии ученика (`POST /api/v1/sessions/{id}/templates/apply`, 201).
     * Тело `{ templateId }`. Создаёт approved-карточку у ученика сессии. Trainer-only.
     */
    @POST("api/v1/sessions/{sessionId}/templates/apply")
    suspend fun applyTemplateToSession(
        @Path("sessionId") sessionId: String,
        @Body body: ApplyTemplateRequest,
    ): CreatedResponse

    /**
     * Применить коллекцию к сессии ученика (`POST /api/v1/collections/{id}/apply`).
     * Тело `{ sessionId }`. Применяет все шаблоны коллекции; ответ `{ ok, applied }`.
     */
    @POST("api/v1/collections/{collectionId}/apply")
    suspend fun applyCollectionToSession(
        @Path("collectionId") collectionId: String,
        @Body body: ApplyCollectionRequest,
    ): ApplyCollectionResponse

    // ---------------------------------------------------------------------------
    // G2 (#31) — регистрация FCM-токена устройства для серверных push-уведомлений.
    // ---------------------------------------------------------------------------

    /**
     * Зарегистрировать (апсертнуть) FCM-токен устройства (`POST /api/v1/devices/token`).
     * Тело `{ token, platform }`. Bearer-авторизация. Вызывается при получении нового
     * токена (FirebaseMessagingService.onNewToken) и при старте после логина.
     */
    @POST("api/v1/devices/token")
    suspend fun registerDeviceToken(@Body body: DeviceTokenRequest): OkResponse
}
