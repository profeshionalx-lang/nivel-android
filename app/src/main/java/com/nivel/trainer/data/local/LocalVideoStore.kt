package com.nivel.trainer.data.local

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nivel.trainer.service.video.VideoFileNaming
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Метаданные локального видео тренировки (A4, #98). Видео само на сервер не уезжает
 * (эпик NIVEL#235) — телефон должен помнить, какой `.mp4` к какой сессии относится и
 * когда начался, чтобы экран сессии (A6/A8) мог открыть скрабер кадра.
 *
 * [audioStartOffsetMs] — рассинхрон стартов видео/аудио рекордеров (вариант B), см.
 * [com.nivel.trainer.service.RecordingState.Recording]; `null`, если запись
 * восстановлена сканом каталога ([LocalVideoStore.recoverFromDisk]) — оффсет оттуда не
 * извлечь.
 */
@Serializable
data class VideoRecord(
    val sessionId: String,
    val videoPath: String,
    val startedAtEpochMs: Long,
    val startedAtElapsedRealtimeMs: Long,
    val durationMs: Long,
    val audioStartOffsetMs: Long? = null,
    val sizeBytes: Long,
)

/**
 * Хранилище метаданных локальных видео (A4, #98) — **не Room**: `NivelDatabase» — кэш
 * чтения с `fallbackToDestructiveMigration()`, локальные данные там молча погибнут при
 * следующем bump'е схемы (см. эпик NIVEL#235). DataStore-Preferences, ключ на сессию —
 * `video:<sessionId>` → JSON [VideoRecord].
 *
 * Подстраховка: если запись в DataStore потерялась (испорченный файл — редкость, но
 * теоретически возможна), а `.mp4` на диске остался, [recoverFromDisk] восстанавливает
 * связь по имени файла (`session-<id>-<epochMs>.mp4`, см. [VideoFileNaming]) — без
 * длительности и оффсета звука (их достоверно не знаем), но с путём и временем старта,
 * которых достаточно, чтобы включить кнопку «Выбрать кадр» (A8).
 */
@Singleton
class LocalVideoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { File(context.filesDir, "datastore/$MANIFEST_FILE_NAME") },
    )
    private val json = Json { ignoreUnknownKeys = true }

    /** Живое состояние записи о видео сессии — экран сессии решает по нему, показывать ли «Выбрать кадр». */
    fun observe(sessionId: String): Flow<VideoRecord?> =
        dataStore.data.map { prefs -> decode(prefs[keyFor(sessionId)]) ?: recoverFromDisk(sessionId) }

    suspend fun get(sessionId: String): VideoRecord? =
        decode(dataStore.data.first()[keyFor(sessionId)]) ?: recoverFromDisk(sessionId)

    suspend fun put(record: VideoRecord) {
        dataStore.edit { it[keyFor(record.sessionId)] = json.encodeToString(VideoRecord.serializer(), record) }
    }

    /** Удаление записи — вызывается из «Завершить разбор» (A9), здесь только API. */
    suspend fun remove(sessionId: String) {
        dataStore.edit { it.remove(keyFor(sessionId)) }
    }

    /**
     * Стирает локальный `.mp4` и запись о нём (A9, #103) — единственный путь избавиться
     * от видео: после подтверждённого сервером «Завершить разбор» либо вручную с экрана
     * сессии. Файл удаляем первым: если его уже нет (гонка, ручное удаление руками) —
     * не страшно, [File.delete] просто вернёт false, а запись в DataStore всё равно
     * очистим, чтобы индикатор занятого места не показывал висящую ссылку.
     */
    suspend fun deleteVideo(sessionId: String) {
        get(sessionId)?.let { record -> runCatching { File(record.videoPath).delete() } }
        remove(sessionId)
    }

    private fun decode(raw: String?): VideoRecord? =
        raw?.let { runCatching { json.decodeFromString<VideoRecord>(it) }.getOrNull() }

    private fun recoverFromDisk(sessionId: String): VideoRecord? {
        val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(context.filesDir, VideoFileNaming.RECORDINGS_DIR)
        val prefix = "session-$safeId-"
        val match = dir.listFiles { file -> file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".mp4") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        val epochMs = match.name.removePrefix(prefix).removeSuffix(".mp4").toLongOrNull() ?: return null
        return VideoRecord(
            sessionId = sessionId,
            videoPath = match.absolutePath,
            startedAtEpochMs = epochMs,
            startedAtElapsedRealtimeMs = 0L,
            durationMs = 0L,
            audioStartOffsetMs = null,
            sizeBytes = match.length(),
        )
    }

    private fun keyFor(sessionId: String) = stringPreferencesKey("video:$sessionId")

    private companion object {
        const val MANIFEST_FILE_NAME = "video_manifest.preferences_pb"
    }
}
