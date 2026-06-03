package com.nivel.trainer.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nivel_session")

/**
 * Хранилище bearer-JWT в DataStore (Preferences). Источник токена — обмен Firebase
 * ID token на бэкенде (см. B2/A2). Токен читается интерсептором и кладётся в
 * заголовок Authorization. Шифрование добавим при необходимости отдельной задачей.
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val bearerToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_BEARER]
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { prefs -> prefs[KEY_BEARER] = token }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_BEARER) }
    }

    private companion object {
        val KEY_BEARER = stringPreferencesKey("bearer_token")
    }
}
