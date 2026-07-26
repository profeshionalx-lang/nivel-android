package com.nivel.trainer.data.local

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import java.io.InputStream
import java.io.OutputStream

/** Значение typed DataStore токена — обёртка, а не голая String, для расширяемости. */
data class TokenData(val bearerToken: String? = null)

/**
 * #72 — сериализатор typed DataStore для [TokenData]: шифрует байты через Tink [Aead]
 * (AES256-GCM, ключ в Android Keystore, см. `di/CryptoModule.kt`) перед записью на диск
 * и расшифровывает при чтении. В файле лежит только шифротекст — открытый JWT туда
 * не попадает.
 */
class TokenSerializer(private val aead: Aead) : Serializer<TokenData> {

    override val defaultValue: TokenData = TokenData()

    override suspend fun readFrom(input: InputStream): TokenData {
        val encrypted = input.readBytes()
        if (encrypted.isEmpty()) return defaultValue
        val plain = try {
            aead.decrypt(encrypted, ASSOCIATED_DATA)
        } catch (e: Exception) {
            throw CorruptionException("Не удалось расшифровать токен", e)
        }
        return TokenData(bearerToken = plain.toString(Charsets.UTF_8).ifBlank { null })
    }

    override suspend fun writeTo(t: TokenData, output: OutputStream) {
        val plain = t.bearerToken.orEmpty().toByteArray(Charsets.UTF_8)
        output.write(aead.encrypt(plain, ASSOCIATED_DATA))
    }

    private companion object {
        /** AAD не привязываем к динамическим данным — токен не пара ключ/значение. */
        val ASSOCIATED_DATA = ByteArray(0)
    }
}
