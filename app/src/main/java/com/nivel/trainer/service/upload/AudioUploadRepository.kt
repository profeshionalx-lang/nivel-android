package com.nivel.trainer.service.upload

import com.nivel.trainer.data.remote.AudioPipelineApi
import com.nivel.trainer.data.remote.TranscribeRequest
import com.nivel.trainer.data.remote.UploadUrlRequest
import com.nivel.trainer.di.UploadClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Исход одной попытки заливки — определяет реакцию воркера (success/retry/fail). */
sealed interface UploadOutcome {
    data object Success : UploadOutcome
    /** Временный сбой (сеть / 5xx) — имеет смысл повторить через backoff. */
    data class Retry(val reason: String) : UploadOutcome
    /** Постоянный сбой (нет файла / 4xx) — повтор бесполезен. */
    data class PermanentFailure(val reason: String) : UploadOutcome
}

/**
 * Конвейер заливки записи (C3, #12): signed upload URL → PUT файла на Supabase →
 * запуск расшифровки. Без UI/Android-зависимостей — чистая логика, которую
 * вызывает [AudioUploadWorker].
 *
 * Разделение клиентов критично: upload-url/transcribe идут на НАШ API с bearer
 * ([AudioPipelineApi] на pipeline-клиенте), а PUT файла — напрямую на Supabase
 * через [UploadClient] **без** интерсептора (наш JWT не должен утечь в Storage).
 *
 * Маппинг ошибок: IO/5xx → [UploadOutcome.Retry] (WorkManager повторит с backoff),
 * 4xx/нет файла → [UploadOutcome.PermanentFailure].
 */
@Singleton
class AudioUploadRepository @Inject constructor(
    private val api: AudioPipelineApi,
    @UploadClient private val uploadClient: OkHttpClient,
) {

    suspend fun upload(sessionId: String, filePath: String): UploadOutcome {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return UploadOutcome.PermanentFailure("Файл записи не найден или пуст: $filePath")
        }
        val ext = file.extension.ifBlank { "m4a" }

        // 1. signed upload URL (наш API).
        val urlResp = try {
            api.requestUploadUrl(sessionId, UploadUrlRequest(ext = ext))
        } catch (e: HttpException) {
            return httpToOutcome(e, "upload-url")
        } catch (e: IOException) {
            return UploadOutcome.Retry("Сеть (upload-url): ${e.message}")
        }

        // 2. PUT файла напрямую на Supabase signed-URL (без нашего bearer).
        putFile(urlResp.uploadUrl, file, ext)?.let { return it }

        // 3. запуск расшифровки (наш API).
        try {
            api.transcribe(sessionId, TranscribeRequest(storagePath = urlResp.storagePath))
        } catch (e: HttpException) {
            return httpToOutcome(e, "transcribe")
        } catch (e: IOException) {
            return UploadOutcome.Retry("Сеть (transcribe): ${e.message}")
        }

        // Подтверждённый успех — локальный файл больше не нужен. C4 расширит
        // политику хранения/докачки/refresh TTL; здесь удаляем после подтверждения.
        runCatching { file.delete() }
        return UploadOutcome.Success
    }

    /** PUT файла на signed-URL. Возвращает не-null исход при сбое, null — успех. */
    private suspend fun putFile(uploadUrl: String, file: File, ext: String): UploadOutcome? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(uploadUrl)
                .put(file.asRequestBody(contentTypeFor(ext).toMediaType()))
                .header("x-upsert", "true")
                .build()
            try {
                uploadClient.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> null
                        resp.code in 500..599 -> UploadOutcome.Retry("PUT ${resp.code}")
                        else -> UploadOutcome.PermanentFailure("PUT ${resp.code}")
                    }
                }
            } catch (e: IOException) {
                UploadOutcome.Retry("Сеть (PUT): ${e.message}")
            }
        }

    private fun httpToOutcome(e: HttpException, step: String): UploadOutcome =
        if (e.code() in 500..599) UploadOutcome.Retry("$step ${e.code()}")
        else UploadOutcome.PermanentFailure("$step ${e.code()}")

    /** MIME аудио по расширению (бэкенд принимает m4a/mp3/wav/ogg/webm/mp4/aac). */
    private fun contentTypeFor(ext: String): String = when (ext.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }
}
