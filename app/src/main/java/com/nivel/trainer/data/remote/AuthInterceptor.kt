package com.nivel.trainer.data.remote

import com.nivel.trainer.data.local.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp-интерсептор: подставляет `Authorization: Bearer <jwt>` ко всем запросам,
 * если токен сохранён. Токен берётся из DataStore (TokenStore). Чтение из Flow —
 * через runBlocking, т.к. интерсептор OkHttp синхронный; вызов идёт на фоновом
 * потоке OkHttp, не на main.
 *
 * TODO: кэшировать токен в памяти (StateFlow в TokenStore), чтобы не блокировать
 * поток OkHttp чтением с диска на каждый запрос.
 */
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenStore.bearerToken.first() }
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
