package com.nivel.trainer.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранилище bearer-JWT — typed DataStore, зашифрованный через Tink ([TokenSerializer],
 * ключ в Android Keystore — см. `di/CryptoModule.kt`). На диске (`$TOKEN_FILE_NAME`)
 * лежит только шифротекст, открытый JWT туда не попадает.
 *
 * #72: кэш в памени ([cached]) — [currentTokenBlocking] отдаёт значение мгновенно, без
 * блокировки диска, как только оно прогрето (после первого чтения/сохранения/логаута).
 * `runBlocking` остаётся единственным fallback-путём — на самый первый вызов сразу
 * после холодного старта, пока фоновый прогрев ещё не успел выполниться.
 *
 * Миграция: до этой задачи токен лежал в открытом виде в Preferences DataStore
 * (`nivel_session`, ключ `bearer_token`). При первом обращении [migrateLegacyToken]
 * переносит значение (если есть) в зашифрованное хранилище и удаляет старый файл —
 * так существующие пользователи не разлогиниваются задачей.
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    aead: Aead,
) {
    private val dataStore: DataStore<TokenData> = DataStoreFactory.create(
        serializer = TokenSerializer(aead),
        produceFile = { File(context.filesDir, "datastore/$TOKEN_FILE_NAME") },
    )

    @Volatile private var cached: String? = null
    @Volatile private var warmedUp = false

    val bearerToken: Flow<String?> = dataStore.data.map { it.bearerToken }

    init {
        // Прогрев в фоне: обычный путь запросов не должен упираться в диск вовсе.
        // Скоуп синглтона — живёт весь процесс, отдельно не отменяем.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            migrateLegacyToken()
            cached = bearerToken.first()
            warmedUp = true
        }
    }

    suspend fun saveToken(token: String) {
        dataStore.updateData { it.copy(bearerToken = token) }
        cached = token
        warmedUp = true
    }

    suspend fun clear() {
        dataStore.updateData { it.copy(bearerToken = null) }
        cached = null
        warmedUp = true
    }

    /**
     * Быстрый синхронный путь для [com.nivel.trainer.data.remote.AuthInterceptor] —
     * поток OkHttp не может suspend-читать Flow. Обычно отдаёт уже прогретый [cached];
     * `runBlocking` — только пока прогрев из [init] ещё не завершился (холодный старт).
     */
    fun currentTokenBlocking(): String? {
        if (warmedUp) return cached
        val token = runBlocking { bearerToken.first() }
        cached = token
        warmedUp = true
        return token
    }

    /** #72: перенос токена из старого незашифрованного `nivel_session` (см. класс-докблок). */
    private suspend fun migrateLegacyToken() {
        val legacyFile = File(context.filesDir, "datastore/$LEGACY_FILE_NAME")
        if (!legacyFile.exists()) return
        try {
            val alreadyHasToken = dataStore.data.first().bearerToken != null
            if (!alreadyHasToken) {
                val legacyStore = PreferenceDataStoreFactory.create(produceFile = { legacyFile })
                val legacyToken = legacyStore.data.first()[LEGACY_KEY]
                if (!legacyToken.isNullOrBlank()) {
                    dataStore.updateData { it.copy(bearerToken = legacyToken) }
                }
            }
        } catch (e: Exception) {
            // Повреждённый старый файл — не блокируем работу приложения; в худшем
            // случае пользователь просто увидит экран логина и войдёт заново.
            Log.w("TokenStore", "не удалось смигрировать легаси-токен", e)
        } finally {
            legacyFile.delete()
        }
    }

    private companion object {
        const val TOKEN_FILE_NAME = "nivel_session_encrypted.pb"
        const val LEGACY_FILE_NAME = "nivel_session.preferences_pb"
        val LEGACY_KEY = stringPreferencesKey("bearer_token")
    }
}
