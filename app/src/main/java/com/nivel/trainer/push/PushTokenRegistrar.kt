package com.nivel.trainer.push

import android.util.Log
import com.nivel.trainer.data.local.TokenStore
import com.nivel.trainer.data.remote.DeviceTokenRequest
import com.nivel.trainer.data.remote.NivelApi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отправляет FCM-токен устройства на бэкенд (`POST /api/v1/devices/token`).
 *
 * Вызывается из двух мест:
 *  - [com.nivel.trainer.push.NivelFirebaseMessagingService.onNewToken] — когда FCM
 *    выдаёт/ротирует токен;
 *  - при старте приложения после логина (см. [registerCurrentToken]).
 *
 * Регистрация делается только при наличии bearer-сессии: эндпоинт требует
 * авторизацию, а до логина user_id неизвестен. Если сессии нет — тихо пропускаем;
 * токен зарегистрируется при следующем onNewToken/старте уже после входа.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val api: NivelApi,
    private val tokenStore: TokenStore,
) {
    /** Отправить конкретный FCM-токен на сервер. Best-effort: ошибки не пробрасываем. */
    suspend fun register(fcmToken: String) {
        val session = tokenStore.bearerToken.first()
        if (session.isNullOrBlank()) {
            Log.d(TAG, "skip register: no bearer session yet")
            return
        }
        try {
            api.registerDeviceToken(DeviceTokenRequest(token = fcmToken))
            Log.d(TAG, "device token registered")
        } catch (e: Exception) {
            // Сеть/401 — не критично, повторится при следующем onNewToken/старте.
            Log.w(TAG, "failed to register device token", e)
        }
    }

    private companion object {
        const val TAG = "PushTokenRegistrar"
    }
}
