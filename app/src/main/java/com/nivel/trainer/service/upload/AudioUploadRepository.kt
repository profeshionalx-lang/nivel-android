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
    /** Временный сбой (сеть / 5xx / истёкший URL) — имеет смысл повторить через backoff. */
    data class Retry(val reason: String) : UploadOutcome
    /** Постоянный сбой (нет файла / 4xx) — повтор бесполезен. */
    data class PermanentFailure(val reason: String) : UploadOutcome
}

/**
 * Конвейер заливки записи (C3, #12) + устойчивость (C4, #13): signed upload URL →
 * PUT файла на Supabase → запуск расшифровки. Без UI/Android-зависимостей — чистая
 * логика, которую вызывает [AudioUploadWorker].
 *
 * Разделение клиентов критично: upload-url/transcribe идут на НАШ API с bearer
 * ([AudioPipelineApi] на pipeline-клиенте), а PUT файла — напрямую на Supabase
 * через [UploadClient] **без** интерсептора (наш JWT не должен утечь в Storage).
 *
 * Устойчивость (C4):
 * - **Истёкший signed URL** (живёт ~2ч): PUT возвращает 4xx-«expiry» (400/401/403);
 *   тогда перезапрашиваем свежий URL и повторяем PUT ([MAX_URL_REFRESH] раз), а если
 *   и после refresh expiry — отдаём [UploadOutcome.Retry], чтобы WorkManager повторил
 *   с новым URL на следующем прогоне.
 * - **Хранение до подтверждения + резюмирование**: после успешного PUT пишем
 *   sidecar-маркер `<file>.uploaded` с `storagePath`. При повторном прогоне (например,
 *   упала только транскрипция) пропускаем повторную заливку 90-мин файла и идём сразу
 *   в `transcribe`. Объект в Storage durable (истекает только URL, не файл).
 * - **Удаление файла** — только после подтверждённого [UploadOutcome.Success]; на
 *   retry/permanent локальный файл сохраняется (защита от потери длинной записи).
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

        // 1+2. Получить storagePath: либо уже подтверждённая заливка (sidecar-маркер),
        // либо запрос signed URL + PUT (с перезапросом истёкшего URL).
        val storagePath = readUploadedMarker(file)
            ?: when (val put = uploadFile(sessionId, file, ext)) {
                is PutResult.Done -> put.storagePath.also { writeUploadedMarker(file, it) }
                is PutResult.Failed -> return put.outcome
            }

        // 3. Запуск расшифровки (наш API).
        try {
            api.transcribe(sessionId, TranscribeRequest(storagePath = storagePath))
        } catch (e: HttpException) {
            // 502 = STT провалился (часто детерминированно: битый/неподдерживаемый
            // звук). Повтор STT упадёт так же → постоянный сбой. Прочие 5xx — временные.
            return if (e.code() == 502) UploadOutcome.PermanentFailure("transcribe 502")
            else httpToOutcome(e, "transcribe")
        } catch (e: IOException) {
            return UploadOutcome.Retry("Сеть (transcribe): ${e.message}")
        }

        // Подтверждённый успех — локальный файл и маркер больше не нужны.
        cleanup(file)
        return UploadOutcome.Success
    }

    /** Результат шага «получить URL + залить файл». */
    private sealed interface PutResult {
        data class Done(val storagePath: String) : PutResult
        data class Failed(val outcome: UploadOutcome) : PutResult
    }

    /**
     * Запросить signed URL и залить файл, перезапрашивая URL при истечении. Объект в
     * Storage durable, поэтому при успехе возвращаем его `storagePath` для transcribe.
     */
    private suspend fun uploadFile(sessionId: String, file: File, ext: String): PutResult {
        var refreshes = 0
        while (true) {
            // signed upload URL (наш API).
            val urlResp = try {
                api.requestUploadUrl(sessionId, UploadUrlRequest(ext = ext))
            } catch (e: HttpException) {
                return PutResult.Failed(httpToOutcome(e, "upload-url"))
            } catch (e: IOException) {
                return PutResult.Failed(UploadOutcome.Retry("Сеть (upload-url): ${e.message}"))
            }

            // PUT напрямую на Supabase signed-URL (без нашего bearer).
            when (val put = putFile(urlResp.uploadUrl, file, ext)) {
                PutAttempt.Success -> return PutResult.Done(urlResp.storagePath)
                is PutAttempt.Expired ->
                    // Истёкший/невалидный URL — перезапросить свежий и повторить PUT.
                    if (refreshes++ < MAX_URL_REFRESH) {
                        continue
                    } else {
                        // Всё ещё expiry после inline-refresh — отдать WorkManager'у:
                        // следующий прогон возьмёт новый URL (в пределах MAX_ATTEMPTS).
                        return PutResult.Failed(UploadOutcome.Retry("signed URL ${put.code} (expired)"))
                    }
                is PutAttempt.Retry -> return PutResult.Failed(UploadOutcome.Retry(put.reason))
                is PutAttempt.Permanent -> return PutResult.Failed(UploadOutcome.PermanentFailure(put.reason))
            }
        }
    }

    /** Исход одного PUT-запроса на signed-URL. */
    private sealed interface PutAttempt {
        data object Success : PutAttempt
        /** Просрочен/невалиден signed URL (400/401/403) — нужен свежий URL. */
        data class Expired(val code: Int) : PutAttempt
        /** Временный сбой (5xx / сеть). */
        data class Retry(val reason: String) : PutAttempt
        /** Постоянный сбой (прочие 4xx: 404/413…). */
        data class Permanent(val reason: String) : PutAttempt
    }

    /** PUT файла на signed-URL. */
    private suspend fun putFile(uploadUrl: String, file: File, ext: String): PutAttempt =
        withContext(Dispatchers.IO) {
            // signed-URL одноразовый под конкретный storagePath (каждый перезапрос —
            // новый путь), поэтому x-upsert не нужен: перезаписывать нечего.
            val request = Request.Builder()
                .url(uploadUrl)
                .put(file.asRequestBody(contentTypeFor(ext).toMediaType()))
                .build()
            try {
                uploadClient.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> PutAttempt.Success
                        resp.code in 500..599 -> PutAttempt.Retry("PUT ${resp.code}")
                        // Supabase отдаёт 400 (jwt expired) на просроченный signed URL;
                        // 401/403 — невалидный токен. Всё это лечится свежим URL.
                        resp.code in EXPIRY_CODES -> PutAttempt.Expired(resp.code)
                        else -> PutAttempt.Permanent("PUT ${resp.code}")
                    }
                }
            } catch (e: IOException) {
                PutAttempt.Retry("Сеть (PUT): ${e.message}")
            }
        }

    private fun httpToOutcome(e: HttpException, step: String): UploadOutcome =
        if (e.code() in 500..599) UploadOutcome.Retry("$step ${e.code()}")
        else UploadOutcome.PermanentFailure("$step ${e.code()}")

    // --- Маркер подтверждённой заливки (хранение до подтверждения, C4) ---

    /** Sidecar-файл рядом с записью: хранит `storagePath` подтверждённой заливки. */
    private fun markerFor(file: File): File = File(file.parentFile, file.name + MARKER_SUFFIX)

    /** Прочитать `storagePath` ранее подтверждённой заливки, если файл записи на месте. */
    private fun readUploadedMarker(file: File): String? {
        val marker = markerFor(file)
        if (!marker.exists()) return null
        return runCatching { marker.readText().trim().ifBlank { null } }.getOrNull()
    }

    private fun writeUploadedMarker(file: File, storagePath: String) {
        runCatching { markerFor(file).writeText(storagePath) }
    }

    /** Удалить локальный файл записи и маркер (только при подтверждённом успехе). */
    private fun cleanup(file: File) {
        runCatching { file.delete() }
        runCatching { markerFor(file).delete() }
    }

    /** MIME аудио по расширению (бэкенд принимает m4a/mp3/wav/ogg/webm/mp4/aac). */
    private fun contentTypeFor(ext: String): String = when (ext.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private companion object {
        /** Сколько раз перезапросить истёкший URL inline в рамках одного прогона. */
        const val MAX_URL_REFRESH = 1

        /** HTTP-коды PUT, означающие просроченный/невалидный signed URL. */
        val EXPIRY_CODES = setOf(400, 401, 403)

        const val MARKER_SUFFIX = ".uploaded"
    }
}
