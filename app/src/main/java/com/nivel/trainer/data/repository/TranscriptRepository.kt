package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.Cached
import com.nivel.trainer.domain.Transcript
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий транскрипта тренировки (D1, #19) — просмотр и выгрузка текста.
 * Точечный экран чтения без собственных Room-entity: оффлайн-чтение (G3, #32)
 * обеспечивается generic [JsonResponseCache] — при сетевом сбое отдаём последний
 * сохранённый транскрипт со `stale=true`. Источник правды — сервер.
 */
interface TranscriptRepository {
    suspend fun getTranscript(sessionId: String): Result<Cached<Transcript>>
}

@Singleton
class DefaultTranscriptRepository @Inject constructor(
    private val api: NivelApi,
    private val cache: JsonResponseCache,
) : TranscriptRepository {

    override suspend fun getTranscript(sessionId: String): Result<Cached<Transcript>> =
        cache.fetch("transcript:$sessionId", Transcript.serializer()) {
            api.getTranscript(sessionId).toDomain()
        }
}
