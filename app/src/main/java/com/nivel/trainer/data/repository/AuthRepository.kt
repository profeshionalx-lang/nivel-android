package com.nivel.trainer.data.repository

import com.nivel.trainer.data.local.TokenStore
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.remote.TokenRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий авторизации: единый вход для UI входа/сплэша.
 *
 * Любой путь входа (WebView Гречки или Google Sign-In fallback) приводит к одному
 * и тому же: получаем Firebase ID token → меняем на bearer через `POST
 * /api/v1/auth/token` (A2) → сохраняем bearer в [TokenStore]. После сохранения
 * [com.nivel.trainer.data.remote.AuthInterceptor] подставляет его в запросы.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: NivelApi,
    private val tokenStore: TokenStore,
) {
    /** true, если bearer-токен уже сохранён (используется сплэшем для выбора экрана). */
    val isLoggedIn: Flow<Boolean> = tokenStore.bearerToken.map { !it.isNullOrBlank() }

    suspend fun hasToken(): Boolean = !tokenStore.bearerToken.first().isNullOrBlank()

    /**
     * Обменивает Firebase ID token на bearer-сессию и сохраняет её.
     * @param idToken Firebase ID token (из redirect Гречки или Google Sign-In).
     * @param claimToken опциональный токен приглашения (claim-флоу).
     * @throws Exception при сетевой ошибке или отказе бэкенда (401/422).
     */
    suspend fun loginWithFirebaseIdToken(idToken: String, claimToken: String? = null) {
        val response = api.exchangeToken(TokenRequest(idToken = idToken, claimToken = claimToken))
        tokenStore.saveToken(response.token)
    }

    /** Logout: чистит bearer-токен. Следующие запросы пойдут без авторизации. */
    suspend fun logout() {
        tokenStore.clear()
    }
}
