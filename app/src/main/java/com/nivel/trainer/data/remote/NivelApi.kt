package com.nivel.trainer.data.remote

import retrofit2.http.GET

/**
 * Retrofit-описание REST API бэкенда Nivel (эндпоинты `api/v1/...`, репо profeshionalx-lang/NIVEL).
 * Эндпоинты добавляются по мере готовности контракта (эпик «Фундамент»: A3–A5).
 * Пока — единственный health-check, чтобы каркас сети был рабочим и проверяемым.
 */
interface NivelApi {

    @GET("api/v1/health")
    suspend fun health(): HealthResponse
}
