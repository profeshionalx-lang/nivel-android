package com.nivel.trainer.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nivel.trainer.MainActivity
import com.nivel.trainer.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Приём push-уведомлений FCM (G2, #31).
 *
 * - [onNewToken] — FCM выдал/ротировал токен устройства → регистрируем его на
 *   бэкенде через [PushTokenRegistrar] (POST /api/v1/devices/token, bearer).
 * - [onMessageReceived] — пришёл пуш в foreground (или data-only): строим
 *   локальное уведомление через [NotificationCompat]. Тап открывает MainActivity
 *   с deep link из `data.deeplink` (напр. `nivel://session/{id}`).
 *
 * Сервер шлёт `notification` + `data` (см. src/lib/fcm.ts в репо NIVEL). Когда
 * приложение в фоне, систему рисует трей-уведомление сама из блока `notification`;
 * этот хэндлер обрабатывает foreground и data-payload (deeplink).
 *
 * @AndroidEntryPoint + field injection — Hilt умеет инжектить в Service.
 */
@AndroidEntryPoint
class NivelFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var tokenRegistrar: PushTokenRegistrar

    // Короткоживущий scope под фоновую регистрацию токена (onNewToken — не suspend).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch { tokenRegistrar.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val payload = message.data
        val notification = message.notification

        // Заголовок/текст: предпочитаем notification-блок, иначе data-поля.
        val title = notification?.title ?: payload["title"] ?: getString(R.string.app_name)
        val body = notification?.body ?: payload["body"] ?: ""
        val deeplink = payload["deeplink"]

        showNotification(title, body, deeplink)
    }

    private fun showNotification(title: String, body: String, deeplink: String?) {
        ensureChannel()

        // Тап открывает MainActivity; deep link (nivel://...) кладём как data Uri —
        // MainActivity (singleTask) получит его в onCreate/onNewIntent.
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!deeplink.isNullOrBlank()) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(deeplink)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            deeplink.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Уведомления Nivel",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Разборы тренировок, напоминания, статусы"
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "nivel_push"
        const val NOTIFICATION_ID = 2001
    }
}
