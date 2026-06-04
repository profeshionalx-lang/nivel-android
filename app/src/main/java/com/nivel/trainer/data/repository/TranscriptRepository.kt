package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.Transcript
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий транскрипта тренировки (D1, #19) — просмотр и выгрузка текста.
 * Точечный экран чтения, поэтому без Room-кэша (как [StudentProfileRepository]):
 * тянет строку транскрипта с сервера и отдаёт доменную модель. Источник правды —
 * сервер (см. AGENTS.md «Одна база — один источник правды»).
 */
interface TranscriptRepository {
    suspend fun getTranscript(sessionId: String): Result<Transcript>
}

@Singleton
class DefaultTranscriptRepository @Inject constructor(
    private val api: NivelApi,
) : TranscriptRepository {

    override suspend fun getTranscript(sessionId: String): Result<Transcript> = runCatching {
        api.getTranscript(sessionId).toDomain()
    }
}
