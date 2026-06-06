package com.nivel.trainer.feature.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.nivel.trainer.BuildConfig

/** Redirect-uri (custom scheme), на который Гречка вернёт токен. */
const val GRECHKA_REDIRECT_URI = "nivel://auth/callback"

/**
 * Открывает вход Гречки в Chrome Custom Tabs.
 *
 * Заменяет прежний [WebView]-флоу: Google блокирует OAuth внутри WebView
 * (`disallowed_useragent`, Error 403). Custom Tabs — это настоящий Chrome,
 * где OAuth работает. Грузим ту же страницу `${GRECHKA_URL}/auth-nivel.html`
 * с `redirect_uri=nivel://auth/callback` и `state=<csrf[~claim]>`. После входа
 * Гречка делает `window.location = nivel://auth/callback?token=...&state=...`,
 * который перехватывает deep-link intent-filter MainActivity (см. AndroidManifest).
 *
 * CSRF-state сверяется на возврате в [AuthViewModel.handleAuthCallback].
 *
 * @return true если Custom Tabs (или браузер) удалось открыть.
 */
fun launchGrechkaAuth(context: Context, state: String): Boolean {
    val url = Uri.parse("${BuildConfig.GRECHKA_URL}/auth-nivel.html")
        .buildUpon()
        .appendQueryParameter("redirect_uri", GRECHKA_REDIRECT_URI)
        .appendQueryParameter("state", state)
        .build()

    return try {
        CustomTabsIntent.Builder()
            .setShowTitle(false)
            .setUrlBarHidingEnabled(true)
            .build()
            .launchUrl(context, url)
        true
    } catch (e: ActivityNotFoundException) {
        // На устройстве нет браузера с поддержкой Custom Tabs и вообще никакого
        // обработчика http(s). Крайне редко, но не падаем.
        false
    }
}
