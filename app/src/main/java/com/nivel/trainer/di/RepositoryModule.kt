package com.nivel.trainer.di

import com.nivel.trainer.data.repository.DefaultInsightCardRepository
import com.nivel.trainer.data.repository.DefaultSessionDetailRepository
import com.nivel.trainer.data.repository.DefaultSessionRepository
import com.nivel.trainer.data.repository.DefaultStudentProfileRepository
import com.nivel.trainer.data.repository.DefaultStudentRepository
import com.nivel.trainer.data.repository.InsightCardRepository
import com.nivel.trainer.data.repository.SessionDetailRepository
import com.nivel.trainer.data.repository.SessionRepository
import com.nivel.trainer.data.repository.StudentProfileRepository
import com.nivel.trainer.data.repository.StudentRepository
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
    abstract fun bindSessionRepository(impl: DefaultSessionRepository): SessionRepository

    @Binds
    @Singleton
    abstract fun bindInsightCardRepository(impl: DefaultInsightCardRepository): InsightCardRepository

    @Binds
    @Singleton
    abstract fun bindStudentProfileRepository(impl: DefaultStudentProfileRepository): StudentProfileRepository

    @Binds
    @Singleton
    abstract fun bindSessionDetailRepository(impl: DefaultSessionDetailRepository): SessionDetailRepository
}
