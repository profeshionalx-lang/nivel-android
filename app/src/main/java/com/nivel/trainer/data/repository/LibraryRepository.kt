package com.nivel.trainer.data.repository

import com.nivel.trainer.data.remote.CreateLibraryItemRequest
import com.nivel.trainer.data.remote.NivelApi
import com.nivel.trainer.data.toDomain
import com.nivel.trainer.domain.LibraryItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий библиотеки навыков и упражнений (E6, #77): `GET/POST /api/v1/skills`
 * и `/exercises` (NIVEL#225). Ищется онлайн на каждый запрос — без Room-кэша, как
 * в issue: справочник маленький (первые 50 по алфавиту без `q`), тянуть заново
 * дешевле, чем поддерживать кэш поисковой выдачи в актуальном состоянии.
 */
interface LibraryRepository {
    suspend fun searchSkills(query: String): Result<List<LibraryItem>>
    suspend fun searchExercises(query: String): Result<List<LibraryItem>>
    suspend fun createSkill(nameRu: String, nameEn: String?): Result<LibraryItem>
    suspend fun createExercise(nameRu: String, nameEn: String?): Result<LibraryItem>
}

@Singleton
class DefaultLibraryRepository @Inject constructor(
    private val api: NivelApi,
) : LibraryRepository {

    override suspend fun searchSkills(query: String): Result<List<LibraryItem>> = runCatching {
        api.searchSkills(query.ifBlank { null }).skills.map { it.toDomain() }
    }

    override suspend fun searchExercises(query: String): Result<List<LibraryItem>> = runCatching {
        api.searchExercises(query.ifBlank { null }).exercises.map { it.toDomain() }
    }

    override suspend fun createSkill(nameRu: String, nameEn: String?): Result<LibraryItem> = runCatching {
        val response = api.createSkill(CreateLibraryItemRequest(nameRu = nameRu.trim(), nameEn = nameEn?.trim()?.ifBlank { null }))
        // Сервер на дубликат имени отдаёт id существующей записи, а не саму запись —
        // возвращаем то, что тренер ввёл (совпадёт с существующим именем при дубле).
        LibraryItem(id = response.id, nameRu = nameRu.trim(), nameEn = nameEn?.trim()?.ifBlank { null })
    }

    override suspend fun createExercise(nameRu: String, nameEn: String?): Result<LibraryItem> = runCatching {
        val response = api.createExercise(CreateLibraryItemRequest(nameRu = nameRu.trim(), nameEn = nameEn?.trim()?.ifBlank { null }))
        LibraryItem(id = response.id, nameRu = nameRu.trim(), nameEn = nameEn?.trim()?.ifBlank { null })
    }
}
