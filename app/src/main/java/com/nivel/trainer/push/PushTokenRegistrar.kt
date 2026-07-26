package com.nivel.trainer.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.nivel.trainer.data.local.TokenStore
import com.nivel.trainer.data.remote.DeviceTokenRequest
import com.nivel.trainer.data.remote.NivelApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Отправляет FCM-токен устройства на бэкенд (`POST /api/v1/devices/token`) и снимает
 * его локально при логауте.
 *
 * Вызывается из:
 *  - [com.nivel.trainer.push.NivelFirebaseMessagingService.onNewToken] — когда FCM
 *    выдаёт/ротирует токен ([register]);
 *  - холодного старта приложения ([register], напрямую в `MainActivity`);
 *  - [com.nivel.trainer.push.PushTokenRegistrationWorker] — гарантированная
 *    регистрация после логина через WorkManager, если в момент логина не было сети
 *    (#80): [registerCurrentToken] пробрасывает ошибку, чтобы воркер мог ретраить;
 *  - логаута ([forgetLocalToken]) — токен нужно забыть, иначе следующий тренер на
 *    этом же устройстве получал бы пуши прежнего (тот же физический токен под
 *    новым `user_id` в `device_tokens`, пока FCM не выдаст новый).
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
        try {
            registerIfSessionPresent(fcmToken)
        } catch (e: Exception) {
            // Сеть/401 — не критично, повторится при следующем onNewToken/старте.
            Log.w(TAG, "failed to register device token", e)
        }
    }

    /**
     * Регистрация текущего токена устройства (#80) — используется
     * [PushTokenRegistrationWorker]. В отличие от [register], пробрасывает сбой
     * дальше, чтобы WorkManager решил, повторять ли попытку.
     */
    suspend fun registerCurrentToken() {
        val fcmToken = fetchCurrentToken()
        registerIfSessionPresent(fcmToken)
    }

    private suspend fun registerIfSessionPresent(fcmToken: String) {
        val session = tokenStore.bearerToken.first()
        if (session.isNullOrBlank()) {
            Log.d(TAG, "skip register: no bearer session yet")
            return
        }
        api.registerDeviceToken(DeviceTokenRequest(token = fcmToken))
        Log.d(TAG, "device token registered")
    }

    private suspend fun fetchCurrentToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /**
     * Забыть FCM-токен этого устройства локально (логаут, #80). Best-effort:
     * ошибки только логируются — залипший старый токен не должен блокировать выход.
     * После этого FCM выдаст новый токен при следующем обращении (например, для
     * следующего тренера на этом же устройстве), так что прежняя привязка
     * `device_tokens.user_id → token` не сможет достучаться до нового пользователя.
     */
    suspend fun forgetLocalToken() {
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                FirebaseMessaging.getInstance().deleteToken()
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            Log.d(TAG, "local FCM token forgotten")
        } catch (e: Exception) {
            Log.w(TAG, "failed to forget local FCM token", e)
        }
    }

    private companion object {
        const val TAG = "PushTokenRegistrar"
    }
}
