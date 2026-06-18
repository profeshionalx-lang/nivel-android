package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.Library
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий справочника навыков и упражнений (E6, #29) — данные для read-only
 * экрана «Library», один-в-один с веб-страницей `trainer/library`.
 *
 * Источник — `GET /api/v1/reference` (A3): отдаёт `skills` и `exercises`
 * (`{ id, name }`, локализованы по `?lang`). Точечный экран чтения, без Room-кэша:
 * источник правды — сервер.
 */
interface LibraryRepository {
    /** Полные списки навыков и упражнений тренера. */
    suspend fun getLibrary(): Result<Library>
}

@Singleton
class DefaultLibraryRepository @Inject constructor(
    private val api: NivelApi,
) : LibraryRepository {

    override suspend fun getLibrary(): Result<Library> = runCatching {
        val reference = api.getReference()
        Library(
            skills = reference.skills.map { it.toDomain() },
            exercises = reference.exercises.map { it.toDomain() },
        )
    }
}
