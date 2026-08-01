package com.nivel.trainer.feature.recorder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.service.RecordingController
import com.nivel.trainer.service.RecordingMode
import com.nivel.trainer.service.RecordingState
import com.nivel.trainer.service.video.AudioTrackExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние импорта видео из галереи (A10, #115). Отдельно от [RecordingState] —
 * импорт не режим записи ([RecordingMode] нарочно не расширяем под него, см. issue).
 */
sealed interface ImportUiState {
    data object Idle : ImportUiState

    /** Идёт ремукс звука ([AudioTrackExtractor]) — [percent] 0..100. */
    data class Extracting(val percent: Int) : ImportUiState

    /** Звук извлечён, видео зарегистрировано в [RecordingController] — экран может закрыться. */
    data object Done : ImportUiState

    /** Не удалось извлечь звук (нет дорожки / не AAC / файл недоступен) — текст для UI. */
    data class Error(val message: String) : ImportUiState
}

/**
 * ViewModel экрана записи (C2, #11; режимы — A3/#97; звук видео-режима — A4/#98) —
 * тонкая обёртка над process-wide [RecordingController]. Сам контроллер `@Singleton`
 * и владеет состоянием записи, поэтому ViewModel ничего не дублирует: только
 * прокидывает [state] в UI и переводит команды экрана в вызовы контроллера.
 *
 * Для [RecordingMode.AUDIO] запись ведёт [RecordingService][com.nivel.trainer.service.RecordingService]
 * (интенты через [start]/[stop]). Для [RecordingMode.VIDEO] видео ведёт CameraX
 * прямо на экране (нужна превью-поверхность) — сюда экран репортит её события
 * ([onVideoStarted]/[onVideoFinished]/[onVideoError]); звук (A4, вариант B) —
 * параллельный `MediaRecorder` в том же [RecordingService], который [onVideoStarted]
 * запускает сразу вслед за CameraX, а [stopVideoAudioSidecar] — останавливает. Мёрж
 * видео- и аудио-половинок в единый [RecordingState.Finished] делает сам контроллер.
 *
 * Хэндофф «запись → заливка» здесь НЕ делаем — он уже встроен в контроллер: при
 * завершении аудио-записи (или аудио-сайдкара видео-режима) он сам ставит заливку в
 * очередь WorkManager (C3). Видео на сервер не уезжает (эпик NIVEL#235) — для него
 * заливки нет вовсе.
 */
@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val controller: RecordingController,
    private val audioTrackExtractor: AudioTrackExtractor,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Состояние записи — единый источник правды, переживает пересоздание Activity. */
    val state: StateFlow<RecordingState> = controller.state

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)

    /** Состояние импорта видео из галереи (A10, #115) — отдельно от [state], не режим записи. */
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private var importJob: Job? = null

    /**
     * Импорт видео [uri] для сессии [sessionId]: извлекает звук ремуксом
     * ([AudioTrackExtractor]), ставит его в очередь заливки существующим путём и
     * регистрирует видео в [RecordingController.videoImported]. Персистентный доступ
     * к [uri] (`takePersistableUriPermission`) экран берёт на себя ДО вызова — здесь
     * только извлечение звука и хэндофф.
     */
    fun importVideo(sessionId: String, uri: Uri) {
        if (_importState.value is ImportUiState.Extracting) return
        _importState.value = ImportUiState.Extracting(0)
        importJob = viewModelScope.launch {
            val durationMs = readDurationMs(uri)
            audioTrackExtractor.extract(uri, sessionId) { percent ->
                _importState.value = ImportUiState.Extracting(percent)
            }.onSuccess { audioFile ->
                controller.videoImported(sessionId, uri, durationMs, audioFile.absolutePath)
                _importState.value = ImportUiState.Done
            }.onFailure { e ->
                _importState.value = ImportUiState.Error(e.message ?: "Не удалось извлечь звук из видео")
            }
        }
    }

    /** Отмена импорта (уход с экрана посреди извлечения на большом файле). */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportUiState.Idle
    }

    /** Сброс терминального состояния импорта после того, как UI его показал. */
    fun acknowledgeImport() {
        _importState.value = ImportUiState.Idle
    }

    private suspend fun readDurationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L).also { runCatching { retriever.release() } }
    }

    /** Старт аудио-записи для сессии. Разрешения экран запрашивает заранее (см. RecorderScreen). */
    fun start(sessionId: String) = controller.start(sessionId)

    /** Остановить аудио-запись — контроллер завершит сервис и инициирует заливку. */
    fun stop() = controller.stop()

    /** Сбросить Finished/Error в Idle после того, как UI «забрал» результат. */
    fun acknowledge() = controller.acknowledge()

    /**
     * CameraX начала писать [videoFile] — фиксируем это в общем состоянии и сразу же
     * (A4, #98) просим сервис поднять параллельный `MediaRecorder` для звука —
     * максимально близко по времени к старту CameraX (см. класс-докблок).
     */
    fun onVideoStarted(sessionId: String, videoFile: File) {
        controller.videoStarted(sessionId, videoFile.absolutePath, SystemClock.elapsedRealtime())
        controller.startAudioSidecar(sessionId)
    }

    /**
     * CameraX финализировал файл — видео готово (остаётся только локально).
     * [interrupted] (#106) — запись прервалась не по команде тренера (CameraX
     * финализировал с ошибкой, но файл непустой) — экран должен это показать.
     */
    fun onVideoFinished(sessionId: String, videoFile: File, durationMs: Long, interrupted: Boolean = false) {
        controller.videoFinished(sessionId, videoFile.absolutePath, durationMs, interrupted)
    }

    /** Видеозапись не удалась (нет файла/сбой камеры) — например, обрыв без данных. */
    fun onVideoError(sessionId: String?, message: String) {
        controller.videoError(sessionId, message)
    }

    /** Остановить аудио-сайдкар видео-режима (A4, #98) — зовётся вместе с остановкой CameraX. */
    fun stopVideoAudioSidecar() = controller.stopAudioSidecar()
}
