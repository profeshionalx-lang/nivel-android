package com.nivel.trainer.data.remote

import com.nivel.trainer.data.local.TokenStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp-интерсептор: подставляет `Authorization: Bearer <jwt>` ко всем запросам,
 * если токен сохранён. #72: токен берётся из [TokenStore.currentTokenBlocking] —
 * прогретого кэша в памяти, без чтения с диска на каждый запрос (`runBlocking`
 * внутри — fallback только на самый первый вызов сразу после холодного старта).
 */
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.currentTokenBlocking()
        val request = chain.request()
        val authorized = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(authorized)
    }
}
