package com.nivel.trainer.service.upload

import com.nivel.trainer.data.remote.AttachFrameRequest
import com.nivel.trainer.data.remote.FrameUploadUrlRequest
import com.nivel.trainer.data.remote.InsightsApi
import com.nivel.trainer.di.UploadClient
import com.nivel.trainer.feature.frames.FrameSlot
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

/**
 * Конвейер заливки кадра (A7, #101): signed upload URL → PUT JPEG на Supabase →
 * привязка к слоту карточки. Зеркалит [AudioUploadRepository] (тот же
 * [UploadOutcome], тот же приём с refresh протухшего signed-URL) — единственная
 * содержательная разница: MIME всегда `image/jpeg`, а третий шаг attach вместо
 * transcribe.
 *
 * Разделение клиентов то же, что у аудио: `upload-url`/`frames` (attach) идут на
 * наш API с bearer через [InsightsApi] (`@InsightsClient` — карточные write-ручки
 * уже там: approve/reject/updateCard), а PUT самого файла — напрямую на Supabase
 * через [UploadClient] **без** интерсептора (наш JWT не должен утечь в Storage).
 */
@Singleton
class FrameUploadRepository @Inject constructor(
    private val api: InsightsApi,
    @UploadClient private val uploadClient: OkHttpClient,
) {

    /**
     * Один проход конвейера.
     *
     * [onProgress] — доля выгрузки `0f..1f`, по умолчанию no-op.
     *
     * Устойчивость (по образцу C4, аудио):
     *  - **Хранение до подтверждения:** локальный JPEG удаляется ТОЛЬКО после
     *    подтверждённого attach (HTTP 200 на `POST …/frames`). Обрыв/перезапуск
     *    оставляет файл на диске — WorkManager повторит заливку, кадр не теряется.
     *  - **Refresh TTL:** если Supabase отверг PUT как протухший (400/403),
     *    перезапрашиваем свежий upload-URL и повторяем PUT один раз в этой же
     *    попытке (см. [putWithTtlRefresh]) — актуально после долгого backoff.
     */
    suspend fun upload(
        cardId: String,
        slot: FrameSlot,
        filePath: String,
        onProgress: (Float) -> Unit = {},
    ): UploadOutcome {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return UploadOutcome.PermanentFailure("Файл кадра не найден или пуст: $filePath")
        }

        // 1–2. signed upload URL + PUT файла с автоперезапросом URL при протухшем TTL.
        val storagePath = when (val r = putWithTtlRefresh(cardId, slot, file, onProgress)) {
            is PutResult.Failure -> return r.outcome
            is PutResult.Uploaded -> r.storagePath
        }

        // 3. привязка к слоту карточки (наш API).
        try {
            api.attachFrame(cardId, AttachFrameRequest(slot = slot.routeValue, storagePath = storagePath))
        } catch (e: HttpException) {
            return httpToOutcome(e, "attach")
        } catch (e: IOException) {
            return UploadOutcome.Retry("Сеть (attach): ${e.message}")
        }

        // Подтверждённый успех — только теперь локальный файл можно удалить.
        runCatching { file.delete() }
        return UploadOutcome.Success
    }

    /** Результат фазы «получить URL + залить файл». */
    private sealed interface PutResult {
        data class Uploaded(val storagePath: String) : PutResult
        data class Failure(val outcome: UploadOutcome) : PutResult
    }

    /**
     * upload-url → PUT, с одним перезапросом свежего signed-URL при истёкшем TTL.
     *
     * signed-URL одноразовый под конкретный storagePath: каждый перезапрос даёт новый
     * путь, поэтому storagePath для attach берём из УСПЕШНО использованного URL.
     */
    private suspend fun putWithTtlRefresh(
        cardId: String,
        slot: FrameSlot,
        file: File,
        onProgress: (Float) -> Unit,
    ): PutResult {
        var attempt = 0
        while (true) {
            val urlResp = try {
                api.requestFrameUploadUrl(cardId, FrameUploadUrlRequest(slot = slot.routeValue, ext = "jpg"))
            } catch (e: HttpException) {
                return PutResult.Failure(httpToOutcome(e, "upload-url"))
            } catch (e: IOException) {
                return PutResult.Failure(UploadOutcome.Retry("Сеть (upload-url): ${e.message}"))
            }

            when (val put = putFile(urlResp.uploadUrl, file, onProgress)) {
                null -> return PutResult.Uploaded(urlResp.storagePath)
                is UploadOutcome.PermanentFailure ->
                    // Истёкший signed-URL Supabase отдаёт как 400/403 — это НЕ
                    // постоянный сбой файла: перезапрашиваем URL и пробуем ещё раз.
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

    /** PUT кадра на signed-URL. Возвращает не-null исход при сбое, null — успех. */
    private suspend fun putFile(
        uploadUrl: String,
        file: File,
        onProgress: (Float) -> Unit,
    ): UploadOutcome? =
        withContext(Dispatchers.IO) {
            val body = ProgressRequestBody(file, JPEG_MEDIA_TYPE, onProgress)
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

    private companion object {
        /** Кадр — всегда JPEG (см. `FrameScrubberViewModel.saveJpeg`). */
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()

        /** Сколько раз перезапросить свежий signed-URL при протухшем TTL за одну попытку. */
        const val MAX_TTL_REFRESH = 1
    }
}
