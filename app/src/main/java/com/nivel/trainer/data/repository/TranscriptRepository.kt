package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.SessionAudioStatus
import com.nivel.trainer.domain.Transcript
import kotlinx.coroutines.CancellationException
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

    override suspend fun getTranscript(sessionId: String): Result<Transcript> = safeCall {
        api.getTranscript(sessionId).toDomain()
    }

    override suspend fun getStatus(sessionId: String): Result<SessionAudioStatus> = safeCall {
        api.getSessionTranscriptStatus(sessionId).toDomain()
    }

    override suspend fun resetTranscript(sessionId: String): Result<Unit> = safeCall {
        api.resetTranscript(sessionId)
        Unit
    }

    override suspend fun deleteTranscript(sessionId: String): Result<Unit> = safeCall {
        api.deleteTranscript(sessionId)
        Unit
    }

    override suspend fun requeueAnalysis(sessionId: String): Result<Unit> = safeCall {
        api.requeueAnalysis(sessionId)
        Unit
    }

    /**
     * `runCatching` catches [CancellationException] along with real errors, which
     * breaks structured concurrency: `loadJob?.cancel()` in [com.nivel.trainer.feature.transcript.TranscriptViewModel]
     * (before reset/delete) wouldn't reliably stop an in-flight call — cancellation
     * would surface as `Result.failure` instead of propagating, letting a stale
     * write land after the state was already cleared. Rethrow it explicitly.
     */
    private inline fun <T> safeCall(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
