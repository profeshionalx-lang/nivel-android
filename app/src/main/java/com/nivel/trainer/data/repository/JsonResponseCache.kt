package com.nivel.trainer.data.repository

import com.nivel.trainer.data.local.ResponseCacheDao
import com.nivel.trainer.data.local.ResponseCacheEntity
import com.nivel.trainer.domain.Cached
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic read-cache поверх Room (G3, #32) для экранов чтения без собственных
 * entity (профиль ученика, карточка тренировки, транскрипт). Хранит JSON
 * последнего успешного ответа под стабильным ключом и отдаёт его, когда сеть
 * недоступна. Источник правды — сервер; это лишь снимок для оффлайна.
 */
@Singleton
class JsonResponseCache @Inject constructor(
    private val dao: ResponseCacheDao,
    private val json: Json,
) {

    /** Последний сохранённый ответ по ключу, или null (нет кэша / битый JSON). */
    suspend fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val entity = dao.get(key) ?: return null
        return runCatching { json.decodeFromString(serializer, entity.json) }.getOrNull()
    }

    /** Сохраняет снимок ответа. Сбой сериализации не должен ронять основной поток. */
    suspend fun <T> write(key: String, value: T, serializer: KSerializer<T>) {
        val encoded = runCatching { json.encodeToString(serializer, value) }.getOrNull() ?: return
        dao.upsert(ResponseCacheEntity(key = key, json = encoded, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Единый паттерн «сеть → кэш»: выполняет [networkCall]; при успехе сохраняет
     * результат и отдаёт [Cached] со `stale=false`; при сетевом сбое отдаёт
     * последний кэш со `stale=true`, а если кэша нет — пробрасывает исходную ошибку
     * (UI покажет экран ошибки с «Повторить», как раньше).
     */
    suspend fun <T> fetch(
        key: String,
        serializer: KSerializer<T>,
        networkCall: suspend () -> T,
    ): Result<Cached<T>> = runCatching {
        val fresh = networkCall()
        write(key, fresh, serializer)
        Cached(fresh, stale = false)
    }.recoverCatching { error ->
        val cached = read(key, serializer) ?: throw error
        Cached(cached, stale = true)
    }
}
