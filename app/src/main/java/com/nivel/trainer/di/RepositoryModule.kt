package com.nivel.trainer.di

import com.nivel.trainer.data.repository.DefaultInsightsRepository
import com.nivel.trainer.data.repository.DefaultSessionDetailRepository
import com.nivel.trainer.data.repository.DefaultStudentProfileRepository
import com.nivel.trainer.data.repository.DefaultStudentRepository
import com.nivel.trainer.data.repository.DefaultLibraryRepository
import com.nivel.trainer.data.repository.DefaultTranscriptRepository
import com.nivel.trainer.data.repository.DefaultTrainerOverviewRepository
import com.nivel.trainer.data.repository.InsightsRepository
import com.nivel.trainer.data.repository.LibraryRepository
import com.nivel.trainer.data.repository.SessionDetailRepository
import com.nivel.trainer.data.repository.StudentProfileRepository
import com.nivel.trainer.data.repository.StudentRepository
import com.nivel.trainer.data.repository.TrainerOverviewRepository
import com.nivel.trainer.data.repository.TranscriptRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-модуль биндингов репозиториев: UI/ViewModel'и инжектят интерфейсы,
 * реализации скрыты за DI. Это и есть «единый вход для UI».
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindStudentRepository(impl: DefaultStudentRepository): StudentRepository

    @Binds
    @Singleton
    abstract fun bindStudentProfileRepository(impl: DefaultStudentProfileRepository): StudentProfileRepository

    @Binds
    @Singleton
    abstract fun bindSessionDetailRepository(impl: DefaultSessionDetailRepository): SessionDetailRepository

    // D2 (#20) — создание инсайтов (вставка + авто-генерация).
    @Binds
    @Singleton
    abstract fun bindInsightsRepository(impl: DefaultInsightsRepository): InsightsRepository

    // D1 (#19) — транскрипт тренировки.
    @Binds
    @Singleton
    abstract fun bindTranscriptRepository(impl: DefaultTranscriptRepository): TranscriptRepository

    // A6 (#76) — агрегат домашнего экрана тренера.
    @Binds
    @Singleton
    abstract fun bindTrainerOverviewRepository(
        impl: DefaultTrainerOverviewRepository,
    ): TrainerOverviewRepository

    // E6 (#77) — библиотека навыков и упражнений.
    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: DefaultLibraryRepository): LibraryRepository
}
