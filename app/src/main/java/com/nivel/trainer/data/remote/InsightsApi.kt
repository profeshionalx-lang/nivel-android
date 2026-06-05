package com.nivel.trainer.data.remote

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit-описание write-эндпоинтов создания инсайтов (D2, #20, write A5).
 *
 * Вынесено в отдельный интерфейс от [NivelApi], потому что эндпоинты используют
 * собственный OkHttp-клиент с большим таймаутом (`@InsightsClient`): авто-генерация
 * крутит LLM **инлайн** на сервере (web `maxDuration=300`), общий клиент с дефолтным
 * read-timeout её бы оборвал. Эндпоинты сверены по route-файлам NIVEL
 * (`src/app/api/v1/sessions/[id]/insights/{paste,generate}/route.ts`).
 */
interface InsightsApi {

    /**
     * Создать draft-карточки из вставленного markdown от Claude
     * (`POST /api/v1/sessions/{id}/insights/paste`, тело `{ markdown }`).
     * Ошибка парсинга → 400 `{ error, line }` (номер строки). Trainer-only.
     */
    @POST("api/v1/sessions/{sessionId}/insights/paste")
    suspend fun pasteInsights(
        @Path("sessionId") sessionId: String,
        @Body body: PasteInsightsRequest,
    ): InsightsResultResponse

    /**
     * Запустить авто-анализ готового транскрипта и создать draft-карточки
     * (`POST /api/v1/sessions/{id}/insights/generate`, без тела). Анализ идёт
     * инлайн и может занять до 5 минут. Прекондишн → 400; сбой анализа → 502.
     */
    @POST("api/v1/sessions/{sessionId}/insights/generate")
    suspend fun generateInsights(
        @Path("sessionId") sessionId: String,
    ): InsightsResultResponse
}
