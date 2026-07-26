package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.SessionAudioStatus
import com.nivel.trainer.domain.Transcript
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий транскрипта тренировки (D1, #19) — просмотр и выгрузка текста.
 * Точечный экран чтения, поэтому без Room-кэша (транскрипт готовится асинхронно и
 * быстро устаревает — кэшировать нечего, тренер всегда хочет актуальный статус):
 * тянет строку транскрипта с сервера и отдаёт доменную модель. Источник правды —
 * сервер (см. AGENTS.md «Одна база — один источник правды»).
 */
interface TranscriptRepository {
    suspend fun getTranscript(sessionId: String): Result<Transcript>

    /** Статус транскрипции + анализа (A9, #79) — отдельно от текста, для меню действий. */
    suspend fun getStatus(sessionId: String): Result<SessionAudioStatus>

    /** Сброс транскрипта (A9, #79): удаляет строку + файл, тренер грузит аудио заново. */
    suspend fun resetTranscript(sessionId: String): Result<Unit>

    /** Удаление транскрипта (A9, #79): деструктивно, подтверждается в UI до вызова. */
    suspend fun deleteTranscript(sessionId: String): Result<Unit>

    /** Повторная постановка анализа в очередь (A9, #79) — доступно только на `ready`. */
    suspend fun requeueAnalysis(sessionId: String): Result<Unit>
}

@Singleton
class DefaultTranscriptRepository @Inject constructor(
    private val api: NivelApi,
) : TranscriptRepository {

    override suspend fun getTranscript(sessionId: String): Result<Transcript> = runCatching {
        api.getTranscript(sessionId).toDomain()
    }

    override suspend fun getStatus(sessionId: String): Result<SessionAudioStatus> = runCatching {
        api.getSessionTranscriptStatus(sessionId).toDomain()
    }

    override suspend fun resetTranscript(sessionId: String): Result<Unit> = runCatching {
        api.resetTranscript(sessionId)
        Unit
    }

    override suspend fun deleteTranscript(sessionId: String): Result<Unit> = runCatching {
        api.deleteTranscript(sessionId)
        Unit
    }

    override suspend fun requeueAnalysis(sessionId: String): Result<Unit> = runCatching {
        api.requeueAnalysis(sessionId)
        Unit
    }
}
