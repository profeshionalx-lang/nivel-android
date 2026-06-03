package com.nivel.trainer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room-БД приложения — кэш чтения (не источник правды). Базовые сущности B3:
 * ученики, сессии, инсайт-карточки. Новые сущности и миграции добавляются в
 * следующих задачах; пока version = 1.
 */
@Database(
    entities = [
        StudentEntity::class,
        SessionEntity::class,
        InsightCardEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NivelDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun sessionDao(): SessionDao
    abstract fun insightCardDao(): InsightCardDao

    companion object {
        const val NAME = "nivel_cache.db"
    }
}
