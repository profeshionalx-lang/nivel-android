package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.InsightsApi
import com.nivel.trainer.data.remote.InsightsErrorResponse
import com.nivel.trainer.data.remote.PasteInsightsRequest
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат создания инсайтов (D2). Разделяем валидационные ошибки (показываем
 * пользователю как есть — это либо номер строки парсинга, либо «нет готового
 * транскрипта») и сетевые/серверные сбои.
 */
sealed interface InsightsResult {
    /** Карточки созданы; [count] — сколько draft-карточек добавлено. */
    data class Success(val count: Int) : InsightsResult

    /** Понятная ошибка от сервера (400/502 с `{ error, line }`) — показать пользователю. */
    data class Failure(val message: String) : InsightsResult
}

/**
 * Репозиторий создания инсайтов (D2, #20): ручная вставка markdown от Claude и
 * авто-генерация из готового транскрипта. Без Room — это точечная запись, источник
 * правды сервер; карточки экран перечитывает через [SessionDetailRepository].
 *
 * Тело ошибки (`{ error, line }`) парсим вручную из `errorBody`, чтобы показать
 * тренеру осмысленное сообщение (для paste — с номером строки, как в вебе).
 */
interface InsightsRepository {
    /** Вставка markdown → draft-карточки. */
    suspend fun pasteInsights(sessionId: String, markdown: String): InsightsResult

    /** Авто-генерация из транскрипта (LLM инлайн, может занять минуты). */
    suspend fun generateInsights(sessionId: String): InsightsResult
}

@Singleton
class DefaultInsightsRepository @Inject constructor(
    private val api: InsightsApi,
    private val json: Json,
) : InsightsRepository {

    override suspend fun pasteInsights(sessionId: String, markdown: String): InsightsResult =
        runCatching { api.pasteInsights(sessionId, PasteInsightsRequest(markdown)) }
            .fold(
                onSuccess = { InsightsResult.Success(it.count) },
                onFailure = { InsightsResult.Failure(messageFor(it, parseError = true)) },
            )

    override suspend fun generateInsights(sessionId: String): InsightsResult =
        runCatching { api.generateInsights(sessionId) }
            .fold(
                onSuccess = { InsightsResult.Success(it.count) },
                onFailure = { InsightsResult.Failure(messageFor(it, parseError = false)) },
            )

    /**
     * Достаём осмысленное сообщение из сбоя. Для HTTP-ошибки парсим тело
     * `{ error, line }`. Для paste (`parseError`) добавляем «Строка N: …», как в вебе.
     */
    private fun messageFor(e: Throwable, parseError: Boolean): String {
        if (e is HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val parsed = body?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<InsightsErrorResponse>(it) }.getOrNull()
            }
            val error = parsed?.error?.takeIf { it.isNotBlank() }
            if (error != null) {
                return if (parseError && parsed.line != null) "Строка ${parsed.line}: $error" else error
            }
            return "Сервер вернул ошибку (${e.code()})."
        }
        return e.message?.takeIf { it.isNotBlank() } ?: "Нет связи с сервером. Попробуйте снова."
    }
}
