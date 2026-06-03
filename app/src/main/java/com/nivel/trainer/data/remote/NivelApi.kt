package com.nivel.trainer.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Retrofit-описание REST API бэкенда Nivel (эндпоинты `api/v1/...`, репо profeshionalx-lang/NIVEL).
 *
 * Read-endpoints учеников/сессий/карточек реализуются в задаче A3 («Фундамент»). На момент B3
 * их контракт зафиксирован дизайн-доком, но сами эндпоинты могут быть ещё не задеплоены — это не
 * блокер (см. AGENTS.md «Зависимость от бэкенда»): репозиторий тянет данные через них, при ошибке
 * остаётся кэш Room. Пути/шейпы выверяются при появлении A3.
 */
interface NivelApi {

    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    // TODO(#A3): подтвердить путь/шейп списка учеников тренера.
    @GET("api/v1/students")
    suspend fun getStudents(): List<StudentDto>

    // TODO(#A3): подтвердить путь/шейп сессий ученика.
    @GET("api/v1/students/{studentId}/sessions")
    suspend fun getStudentSessions(@Path("studentId") studentId: String): List<SessionDto>

    // TODO(#A3): подтвердить путь/шейп инсайт-карточек сессии.
    @GET("api/v1/sessions/{sessionId}/cards")
    suspend fun getSessionCards(@Path("sessionId") sessionId: String): List<InsightCardDto>
}
