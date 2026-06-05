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
    /**
     * Постоянный сбой (нет файла / 4xx) — повтор бесполезен.
     * [expiredUrl] = true для частного случая 400/403 на signed-URL (протухший TTL):
     * сам PUT повторять смысла нет, но перезапрос свежего URL может помочь (C4).
     */
    data class PermanentFailure(
        val reason: String,
        val expiredUrl: Boolean = false,
    ) : UploadOutcome
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

    /**
     * Один проход конвейера (C3 + устойчивость C4, #13).
     *
     * [onProgress] — доля выгрузки `0f..1f` для прогресс-бара экрана статусов (C5);
     * по умолчанию no-op (когда прогресс не нужен, напр. в тестах).
     *
     * Устойчивость (C4):
     *  - **Хранение до подтверждения:** локальный файл удаляется ТОЛЬКО после
     *    подтверждённого `…/transcribe` (HTTP 200). Любой обрыв/перезапуск оставляет
     *    файл на диске — WorkManager повторит заливку (backoff), запись не теряется.
     *  - **Refresh TTL:** signed upload URL живёт ограниченно; если Supabase отверг
     *    PUT как протухший (400/403), перезапрашиваем свежий URL и повторяем PUT
     *    один раз в этой же попытке (см. [putWithTtlRefresh]). Это покрывает кейс,
     *    когда заливка стартовала спустя долгий backoff после выдачи URL.
     */
    suspend fun upload(
        sessionId: String,
        filePath: String,
        onProgress: (Float) -> Unit = {},
    ): UploadOutcome {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return UploadOutcome.PermanentFailure("Файл записи не найден или пуст: $filePath")
        }
        val ext = file.extension.ifBlank { "m4a" }

        // 1–2. signed upload URL + PUT файла с автоперезапросом URL при протухшем TTL.
        val storagePath = when (val r = putWithTtlRefresh(sessionId, file, ext, onProgress)) {
            is PutResult.Failure -> return r.outcome
            is PutResult.Uploaded -> r.storagePath
        }

        // 3. запуск расшифровки (наш API).
        try {
            api.transcribe(sessionId, TranscribeRequest(storagePath = storagePath))
        } catch (e: HttpException) {
            // 502 = STT провалился (часто детерминированно: битый/неподдерживаемый
            // звук). Повтор = заново upload-url + PUT всего файла, а STT упадёт так же
            // → считаем постоянным сбоем. Прочие 5xx (500/503) — временные, ретраим.
            return if (e.code() == 502) UploadOutcome.PermanentFailure("transcribe 502")
            else httpToOutcome(e, "transcribe")
        } catch (e: IOException) {
            return UploadOutcome.Retry("Сеть (transcribe): ${e.message}")
        }

        // Подтверждённый успех (C4) — только теперь локальный файл можно удалить.
        runCatching { file.delete() }
        return UploadOutcome.Success
    }

    /** Результат фазы «получить URL + залить файл». */
    private sealed interface PutResult {
        data class Uploaded(val storagePath: String) : PutResult
        data class Failure(val outcome: UploadOutcome) : PutResult
    }

    /**
     * upload-url → PUT, с одним перезапросом свежего signed-URL при истёкшем TTL (C4).
     *
     * signed-URL одноразовый под конкретный storagePath: каждый перезапрос даёт новый
     * путь, поэтому storagePath для последующего transcribe берём из УСПЕШНО
     * использованного URL.
     */
    private suspend fun putWithTtlRefresh(
        sessionId: String,
        file: File,
        ext: String,
        onProgress: (Float) -> Unit,
    ): PutResult {
        var attempt = 0
        while (true) {
            val urlResp = try {
                api.requestUploadUrl(sessionId, UploadUrlRequest(ext = ext))
            } catch (e: HttpException) {
                return PutResult.Failure(httpToOutcome(e, "upload-url"))
            } catch (e: IOException) {
                return PutResult.Failure(UploadOutcome.Retry("Сеть (upload-url): ${e.message}"))
            }

            when (val put = putFile(urlResp.uploadUrl, file, ext, onProgress)) {
                null -> return PutResult.Uploaded(urlResp.storagePath)
                is UploadOutcome.PermanentFailure ->
                    // Истёкший signed-URL Supabase отдаёт как 400/403 — это НЕ
                    // постоянный сбой записи: перезапрашиваем URL и пробуем ещё раз.
                    if (put.expiredUrl && attempt < MAX_TTL_REFRESH) {
                        attempt++
                        onProgress(0f) // новый URL ⇒ льём файл заново с нуля
                    } else {
                        return PutResult.Failure(put)
                    }
                else -> return PutResult.Failure(put)
            }
        }
    }

    /** PUT файла на signed-URL. Возвращает не-null исход при сбое, null — успех. */
    private suspend fun putFile(
        uploadUrl: String,
        file: File,
        ext: String,
        onProgress: (Float) -> Unit,
    ): UploadOutcome? =
        withContext(Dispatchers.IO) {
            val body = ProgressRequestBody(file, contentTypeFor(ext).toMediaType(), onProgress)
            val request = Request.Builder()
                .url(uploadUrl)
                .put(body)
                .build()
            try {
                uploadClient.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> null
                        resp.code in 500..599 -> UploadOutcome.Retry("PUT ${resp.code}")
                        // 400/403 на signed-URL = протухший/невалидный TTL — даём
                        // вызывающему шанс перезапросить URL (см. putWithTtlRefresh).
                        resp.code == 400 || resp.code == 403 ->
                            UploadOutcome.PermanentFailure("PUT ${resp.code}", expiredUrl = true)
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

    private companion object {
        /** Сколько раз перезапросить свежий signed-URL при протухшем TTL за одну попытку. */
        const val MAX_TTL_REFRESH = 1
    }
}
