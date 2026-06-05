package com.nivel.trainer.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.nivel.trainer.BuildConfig
import com.nivel.trainer.data.remote.AuthInterceptor
import com.nivel.trainer.data.remote.InsightsApi
import com.nivel.trainer.data.remote.NivelApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Hilt-модуль сетевого слоя: Json (kotlinx.serialization), OkHttp с bearer-интерсептором
 * и логированием, Retrofit на базовый URL бэкенда, и сам NivelApi.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideNivelApi(retrofit: Retrofit): NivelApi = retrofit.create(NivelApi::class.java)

    // -------------------------------------------------------------------------
    // D2 (#20) — клиент для создания инсайтов с большим таймаутом.
    // Авто-генерация (insights/generate) крутит LLM инлайн на сервере
    // (web `maxDuration=300`), поэтому общий клиент с дефолтным read-timeout её
    // оборвёт. Отдельный квалифицированный клиент/Retrofit под InsightsApi.
    // -------------------------------------------------------------------------

    @Provides
    @Singleton
    @InsightsClient
    fun provideInsightsOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        // Генерация инсайтов идёт инлайн до ~5 мин — даём запас по read/call-timeout.
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(330, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(360, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @InsightsClient
    fun provideInsightsRetrofit(@InsightsClient client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideInsightsApi(@InsightsClient retrofit: Retrofit): InsightsApi =
        retrofit.create(InsightsApi::class.java)
}

/** Квалификатор клиента/Retrofit с увеличенным таймаутом под инлайн-генерацию инсайтов (D2). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InsightsClient
