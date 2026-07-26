package com.nivel.trainer.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Гарантированная регистрация FCM-токена после логина (#80). Прямой вызов из
 * `MainActivity` — best-effort и не повторяется, если в момент логина не было
 * сети. Этот воркер даёт WorkManager-повтор с backoff: `NetworkType.CONNECTED` в
 * ограничениях (см. [PushTokenScheduler]) плюс ретраи здесь, пока попытки не
 * закончатся или сеть не появится.
 */
@HiltWorker
class PushTokenRegistrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val registrar: PushTokenRegistrar,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        registrar.registerCurrentToken()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
    }

    companion object {
        // runAttemptCount стартует с 0, поэтому итог — MAX_ATTEMPTS + 1 попыток
        // всего (как в AudioUploadWorker).
        private const val MAX_ATTEMPTS = 5
    }
}
