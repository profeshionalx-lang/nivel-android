package com.nivel.trainer.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.nivel.trainer.BuildConfig
import com.nivel.trainer.data.remote.AudioPipelineApi
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
 * Клиент аудио-конвейера (C3): наш API с bearer, но с большими таймаутами —
 * `…/transcribe` блокируется до конца STT (`maxDuration=300`).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PipelineClient

/**
 * Клиент для прямого PUT файла на Supabase signed-URL: БЕЗ [AuthInterceptor]
 * (наш JWT не должен уходить в Storage) и с большими таймаутами под загрузку.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UploadClient

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

    // --- C3 (#12): аудио-конвейер (большие таймауты, отдельные клиенты) ---

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // Тело не логируем даже в debug — это аудио/бинарь, забьёт лог.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
            else HttpLoggingInterceptor.Level.NONE
        }

    /**
     * Клиент для вызовов нашего API в конвейере (upload-url + transcribe): с
     * bearer-интерсептором, но большим read-timeout — transcribe ждёт STT.
     * callTimeout не задаём (0): общий лимит оборвал бы длинную расшифровку.
     */
    @Provides
    @Singleton
    @PipelineClient
    fun providePipelineClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()

    /**
     * Клиент для прямого PUT файла на Supabase signed-URL: БЕЗ нашего bearer,
     * большой write-timeout под выгрузку длинной записи (≈90 мин ⇒ десятки МБ).
     */
    @Provides
    @Singleton
    @UploadClient
    fun provideUploadClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .build()

    @Provides
    @Singleton
    @PipelineClient
    fun providePipelineRetrofit(@PipelineClient client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

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
    fun provideAudioPipelineApi(@PipelineClient retrofit: Retrofit): AudioPipelineApi =
        retrofit.create(AudioPipelineApi::class.java)

    @Provides
    @Singleton
    fun provideInsightsApi(@InsightsClient retrofit: Retrofit): InsightsApi =
        retrofit.create(InsightsApi::class.java)
}

/** Квалификатор клиента/Retrofit с увеличенным таймаутом под инлайн-генерацию инсайтов (D2). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InsightsClient
