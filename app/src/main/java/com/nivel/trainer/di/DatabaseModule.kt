package com.nivel.trainer.di

import android.content.Context
import androidx.room.Room
import com.nivel.trainer.data.local.InsightCardDao
import com.nivel.trainer.data.local.NivelDatabase
import com.nivel.trainer.data.local.SessionDao
import com.nivel.trainer.data.local.StudentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-модуль локального кэша: Room-БД и её DAO. БД — кэш чтения; при смене схемы
 * без миграции данные кэша безопасно теряются (источник правды — сервер),
 * поэтому используем `fallbackToDestructiveMigration`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NivelDatabase =
        Room.databaseBuilder(context, NivelDatabase::class.java, NivelDatabase.NAME)
            // Кэш чтения: при смене схемы без миграции данные безопасно пересоздаются
            // (источник правды — сервер, refresh* их восстановит).
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideStudentDao(db: NivelDatabase): StudentDao = db.studentDao()

    @Provides
    fun provideSessionDao(db: NivelDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideInsightCardDao(db: NivelDatabase): InsightCardDao = db.insightCardDao()
}
