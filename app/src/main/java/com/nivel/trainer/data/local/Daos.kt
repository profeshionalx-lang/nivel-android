package com.nivel.trainer.data.local

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO кэша чтения. Каждый DAO умеет: наблюдать (Flow для UI), upsert'ить снимок с сервера
 * и атомарно заменять набор (replace = очистить + вставить), т.к. сервер — источник правды
 * и удалённые на сервере записи не должны «зависать» в кэше.
 */

@Dao
interface StudentDao {

    // Порядок один-в-один с вебом (`trainer/students/page.tsx`): новые сверху.
    // createdAt — ISO-строка, лексикографическая сортировка совпадает с временной.
    @Query("SELECT * FROM students ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<StudentEntity>>

    @Upsert
    suspend fun upsertAll(students: List<StudentEntity>)

    @Query("DELETE FROM students")
    suspend fun clear()

    /** Полностью заменяет кэш списком с сервера (атомарно). */
    @Transaction
    suspend fun replaceAll(students: List<StudentEntity>) {
        clear()
        upsertAll(students)
    }
}

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions WHERE studentId = :studentId ORDER BY sessionNumber DESC")
    fun observeByStudent(studentId: String): Flow<List<SessionEntity>>

    /** G3 (#32): единственная сессия по id — для offline-чтения экрана деталей. */
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): SessionEntity?

    @Upsert
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE studentId = :studentId")
    suspend fun clearForStudent(studentId: String)

    /** Заменяет кэш сессий конкретного ученика (атомарно). */
    @Transaction
    suspend fun replaceForStudent(studentId: String, sessions: List<SessionEntity>) {
        clearForStudent(studentId)
        upsertAll(sessions)
    }
}

@Dao
interface InsightCardDao {

    @Query("SELECT * FROM insight_cards WHERE sessionId = :sessionId ORDER BY position ASC")
    fun observeBySession(sessionId: String): Flow<List<InsightCardEntity>>

    /** G3 (#32): разовое чтение карточек из кэша (suspend, не Flow). */
    @Query("SELECT * FROM insight_cards WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun getBySession(sessionId: String): List<InsightCardEntity>

    @Upsert
    suspend fun upsertAll(cards: List<InsightCardEntity>)

    @Query("DELETE FROM insight_cards WHERE sessionId = :sessionId")
    suspend fun clearForSession(sessionId: String)

    /** Заменяет кэш карточек конкретной сессии (атомарно). */
    @Transaction
    suspend fun replaceForSession(sessionId: String, cards: List<InsightCardEntity>) {
        clearForSession(sessionId)
        upsertAll(cards)
    }
}
