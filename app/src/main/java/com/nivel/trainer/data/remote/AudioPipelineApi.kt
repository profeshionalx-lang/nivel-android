package com.nivel.trainer.data.remote

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit-интерфейс аудио-конвейера (C3, #12): получение signed upload URL и
 * запуск расшифровки. Вынесен из [NivelApi] в отдельный интерфейс намеренно — он
 * привязан к **другому** OkHttp-клиенту с большими таймаутами (см. NetworkModule,
 * `@PipelineClient`): `…/transcribe` блокируется на сервере до конца STT
 * (`maxDuration=300`), а дефолтный 10-сек read-timeout его бы оборвал.
 *
 * Оба эндпоинта — trainer-only, авторизация по bearer (интерсептор на
 * pipeline-клиенте). PUT самого файла идёт мимо Retrofit — напрямую на Supabase
 * через клиент без интерсептора (наш JWT не должен утекать в Storage).
 */
interface AudioPipelineApi {

    /** Запросить signed upload URL для аудио сессии (`…/audio/upload-url`). */
    @POST("api/v1/sessions/{sessionId}/audio/upload-url")
    suspend fun requestUploadUrl(
        @Path("sessionId") sessionId: String,
        @Body body: UploadUrlRequest,
    ): UploadUrlResponse

    /** Запустить расшифровку загруженного файла (`…/transcribe`). */
    @POST("api/v1/sessions/{sessionId}/transcribe")
    suspend fun transcribe(
        @Path("sessionId") sessionId: String,
        @Body body: TranscribeRequest,
    ): TranscribeResponse
}
