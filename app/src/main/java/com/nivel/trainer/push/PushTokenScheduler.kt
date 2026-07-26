package com.nivel.trainer.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Планировщик гарантированной регистрации FCM-токена после логина (#80). Ставит
 * [PushTokenRegistrationWorker] уникальной работой с требованием сети и
 * экспоненциальным backoff — покрывает случай логина без сети, когда прямой
 * best-effort вызов из `MainActivity` не смог отправить токен и не повторится сам.
 */
@Singleton
class PushTokenScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Вызывается сразу после успешного логина (см. `AuthRepository.loginWithFirebaseIdToken`). */
    fun enqueueAfterLogin() {
        val request = OneTimeWorkRequestBuilder<PushTokenRegistrationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val TAG = "push-token-registration"
        private const val UNIQUE_NAME = "push-token-registration"
        private const val BACKOFF_SECONDS = 30L
    }
}
