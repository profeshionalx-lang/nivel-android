package com.nivel.trainer.feature.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nivel.trainer.BuildConfig

/**
 * WebView c флоу входа Гречки.
 *
 * Грузит `${GRECHKA_URL}/auth-nivel.html?redirect_uri=<callback>&state=<state>` —
 * ту же страницу, на которую редиректит веб-роут `/api/auth/grechka`. Гречка после
 * входа редиректит на `redirect_uri?token=<firebaseIdToken>&state=...`. Мы НЕ даём
 * WebView реально открыть callback на бэкенде (там он повесил бы httpOnly-куку, а нам
 * нужен bearer): перехватываем редирект в [WebViewClient.shouldOverrideUrlLoading],
 * достаём Firebase ID token из `?token=` и отдаём наружу через [onFirebaseIdToken].
 *
 * @param state значение OAuth-state (CSRF), переданное в auth-nivel.html; сверяется
 *   с тем, что вернулось в редиректе.
 * @param claimToken опциональный токен приглашения — едет внутри state (`csrf~claim`),
 *   как в веб-роуте `/api/auth/grechka`.
 *
 * Примечание: поведение Гречки внутри WebView не проверялось вживую (нет окружения).
 * Перехват сделан строго по callback-пути и имени параметра `token`, как у веб-роута
 * `/api/auth/grechka-callback`. Если Гречка вернёт токен иначе — поправить здесь.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GrechkaWebView(
    state: String,
    onFirebaseIdToken: (idToken: String) -> Unit,
    onError: (message: String) -> Unit,
    modifier: Modifier = Modifier,
    claimToken: String? = null,
) {
    var loading by remember { mutableStateOf(true) }

    // redirect_uri — тот же callback, что у веба; токен ловим из него, на сервер не идём.
    val redirectUri = "${BuildConfig.NIVEL_URL}/api/auth/grechka-callback"
    val callbackPath = "/api/auth/grechka-callback"
    val initialUrl = remember(state, claimToken) {
        Uri.parse("${BuildConfig.GRECHKA_URL}/auth-nivel.html")
            .buildUpon()
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean = handleCallback(request.url)

                        private fun handleCallback(url: Uri?): Boolean {
                            if (url == null) return false
                            if (!url.path.orEmpty().endsWith(callbackPath)) return false

                            // Это редирект на наш callback — перехватываем, не грузим.
                            val token = url.getQueryParameter("token")
                            val returnedState = url.getQueryParameter("state")
                            // Гречка возвращает state в виде `csrf` или `csrf~claim`; сверяем
                            // только csrf-часть с тем, что отправляли.
                            val returnedCsrf = returnedState?.substringBefore("~")
                            val expectedCsrf = state.substringBefore("~")

                            when {
                                token.isNullOrBlank() ->
                                    onError("Не удалось получить токен от Гречки.")
                                returnedCsrf != expectedCsrf ->
                                    onError("Сессия истекла, попробуйте ещё раз.")
                                else -> onFirebaseIdToken(token)
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            // Только для основного документа, не для под-ресурсов.
                            if (request.isForMainFrame) {
                                loading = false
                                onError("Не удалось загрузить страницу входа.")
                            }
                        }
                    }
                    loadUrl(initialUrl)
                }
            },
        )

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
