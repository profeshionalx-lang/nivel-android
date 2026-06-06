package com.nivel.trainer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room-БД приложения — кэш чтения (не источник правды). Базовые сущности B3:
 * ученики, сессии, инсайт-карточки. Новые сущности и миграции добавляются в
 * следующих задачах. version = 2: в StudentEntity добавлены счётчики
 * целей/сессий (B4). version = 3: добавлен `response_cache` — generic JSON-кэш
 * ответов экранов чтения для оффлайна (G3, #32). Миграция деструктивная — кэш
 * безопасно пересоздаётся.
 */
@Database(
    entities = [
        StudentEntity::class,
        SessionEntity::class,
        InsightCardEntity::class,
        ResponseCacheEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class NivelDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun sessionDao(): SessionDao
    abstract fun insightCardDao(): InsightCardDao
    abstract fun responseCacheDao(): ResponseCacheDao

    companion object {
        const val NAME = "nivel_cache.db"
    }
}
