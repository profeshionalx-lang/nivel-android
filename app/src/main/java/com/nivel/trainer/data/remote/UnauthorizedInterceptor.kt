package com.nivel.trainer.data.remote

import com.nivel.trainer.data.local.NivelDatabase
import com.nivel.trainer.data.local.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * #72 — сервер отклонил bearer (протух/невалиден): любой ответ 401 от авторизованного
 * клиента чистит [TokenStore] и Room-кэш ([NivelDatabase]), затем эмитит событие в
 * [AuthEvents] — `NivelRoot` уводит пользователя на экран логина вместо вечной ошибки
 * на каждом экране. Кэш чистим и здесь, не только в явном логауте (`AuthRepository`) —
 * иначе следующий тренер, вошедший на этом устройстве после автоматического разлогина
 * по протухшему токену (а не через кнопку «Выйти»), увидел бы кэш предыдущего.
 *
 * [NivelDatabase] зависит только от `@ApplicationContext` — инъекция сюда не создаёт
 * цикл в графе Hilt (в отличие от [com.nivel.trainer.data.repository.AuthRepository],
 * которую сюда внедрить нельзя: она тянет `NivelApi` → `Retrofit` → тот самый
 * `OkHttpClient`, в который встроен этот интерсептор).
 *
 * Регистрируется только на авторизованных клиентах (default/@PipelineClient/
 * @InsightsClient, см. `di/NetworkModule.kt`) — на `@UploadClient` (прямой PUT на
 * Supabase signed URL, без нашего bearer) не вешается: там 401 в принципе невозможен
 * по этой причине, а сам факт добавления интерсептора туда был бы вводящим в
 * заблуждение источником ложных логаутов при сбоях заливки.
 */
class UnauthorizedInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
    private val database: NivelDatabase,
    private val authEvents: AuthEvents,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 401) {
            runBlocking { tokenStore.clear() }
            database.clearAllTables() // блокирующий вызов Room — поток OkHttp, не main
            authEvents.emitUnauthorized()
        }
        return response
    }
}
