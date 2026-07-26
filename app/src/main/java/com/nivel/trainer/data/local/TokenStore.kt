package com.nivel.trainer.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранилище bearer-JWT — typed DataStore, зашифрованный через Tink ([TokenSerializer],
 * ключ в Android Keystore — см. `di/CryptoModule.kt`). На диске (`$TOKEN_FILE_NAME`)
 * лежит только шифротекст, открытый JWT туда не попадает.
 *
 * Ключ Android Keystore не переживает восстановление на другое/сброшенное устройство —
 * файл и Tink-keyset исключены из Auto Backup (`res/xml/backup_rules.xml` +
 * `data_extraction_rules.xml`), а [ReplaceFileCorruptionHandler] на случай, если
 * расшифровка всё же не удалась (испорченный файл, потерянный ключ) — тогда токен
 * просто сбрасывается в `null` вместо падения приложения в краш-луп на каждом старте.
 *
 * #72: кэш в памени ([cached]) — [currentTokenBlocking] отдаёт значение мгновенно, без
 * блокировки диска, как только оно прогрето (после первого чтения/сохранения/логаута).
 * `runBlocking` остаётся единственным fallback-путём — на самый первый вызов сразу
 * после холодного старта, пока фоновый прогрев ещё не успел выполниться.
 *
 * Миграция: до этой задачи токен лежал в открытом виде в Preferences DataStore
 * (`nivel_session`, ключ `bearer_token`). При первом обращении [migrateLegacyToken]
 * переносит значение (если есть) в зашифрованное хранилище и удаляет старый файл —
 * так существующие пользователи не разлогиниваются задачей. [migrationLock] не даёт
 * миграции разъехаться с параллельным логином/логаутом (check-then-act иначе мог бы
 * затереть свежесохранённый токен устаревшим значением из легаси-файла).
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    aead: Aead,
) {
    private val dataStore: DataStore<TokenData> = DataStoreFactory.create(
        serializer = TokenSerializer(aead),
        corruptionHandler = ReplaceFileCorruptionHandler { TokenData() },
        produceFile = { File(context.filesDir, "datastore/$TOKEN_FILE_NAME") },
    )

    private val migrationLock = Mutex()

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
        migrationLock.withLock {
            dataStore.updateData { it.copy(bearerToken = token) }
        }
        cached = token
        warmedUp = true
    }

    suspend fun clear() {
        migrationLock.withLock {
            dataStore.updateData { it.copy(bearerToken = null) }
        }
        cached = null
        warmedUp = true
    }

    /**
     * Быстрый синхронный путь для [com.nivel.trainer.data.remote.AuthInterceptor] —
     * поток OkHttp не может suspend-читать Flow. Обычно отдаёт уже прогретый [cached].
     * Пока прогрев из [init] не завершился (холодный старт) — единственный fallback:
     * `runBlocking`, сперва догоняющий [migrateLegacyToken] (идемпотентна, см. её
     * докблок), чтобы авторизованный запрос не улетел без токена только потому, что
     * фоновая миграция ещё не успела дописать его из легаси-хранилища.
     */
    fun currentTokenBlocking(): String? {
        if (warmedUp) return cached
        val token = runBlocking {
            migrateLegacyToken()
            bearerToken.first()
        }
        cached = token
        warmedUp = true
        return token
    }

    /**
     * #72: перенос токена из старого незашифрованного `nivel_session` (см. класс-докблок).
     * Идемпотентна: без легаси-файла (уже смигрировали или свежая установка) — мгновенный
     * no-op. Может безопасно вызываться параллельно из [init] и [currentTokenBlocking] —
     * [migrationLock] сериализует фактическую работу, повторный вызов просто увидит
     * удалённый файл и выйдет на первой строке.
     */
    private suspend fun migrateLegacyToken() {
        val legacyFile = File(context.filesDir, "datastore/$LEGACY_FILE_NAME")
        if (!legacyFile.exists()) return
        migrationLock.withLock {
            if (!legacyFile.exists()) return@withLock // смигрировано, пока ждали лок
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
                // Испорченный старый файл или любая другая неожиданная ошибка чтения —
                // не блокируем работу приложения; в худшем случае пользователь просто
                // увидит экран логина и войдёт заново.
                Log.w("TokenStore", "не удалось смигрировать легаси-токен", e)
            } finally {
                legacyFile.delete()
            }
        }
    }

    private companion object {
        const val TOKEN_FILE_NAME = "nivel_session_encrypted.pb"
        const val LEGACY_FILE_NAME = "nivel_session.preferences_pb"
        val LEGACY_KEY = stringPreferencesKey("bearer_token")
    }
}
