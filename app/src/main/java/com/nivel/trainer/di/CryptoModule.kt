package com.nivel.trainer.di

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * #72 — шифрование bearer-токена: Tink [Aead] (AES256-GCM), ключ которого хранится
 * в Android Keystore (`android-keystore://`), а не в коде и не на диске в открытом
 * виде. Используется [com.nivel.trainer.data.local.TokenSerializer] для typed
 * DataStore токена (см. [com.nivel.trainer.data.local.TokenStore]).
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        val keysetManager = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS_NAME)
            .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
            .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
        return keysetManager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private const val KEYSET_NAME = "nivel_token_keyset"
    private const val KEYSET_PREFS_NAME = "nivel_token_keyset_prefs"
    private const val MASTER_KEY_ALIAS = "nivel_token_master_key"
}
