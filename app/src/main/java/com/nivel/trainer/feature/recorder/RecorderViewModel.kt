package com.nivel.trainer.feature.recorder

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.nivel.trainer.service.RecordingController
import com.nivel.trainer.service.RecordingMode
import com.nivel.trainer.service.RecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel экрана записи (C2, #11; режимы — A3/#97) — тонкая обёртка над
 * process-wide [RecordingController]. Сам контроллер `@Singleton` и владеет
 * состоянием записи, поэтому ViewModel ничего не дублирует: только прокидывает
 * [state] в UI и переводит команды экрана в вызовы контроллера.
 *
 * Для [RecordingMode.AUDIO] запись ведёт [RecordingService][com.nivel.trainer.service.RecordingService]
 * (интенты через [start]/[stop]). Для [RecordingMode.VIDEO] запись ведёт CameraX
 * прямо на экране (нужна превью-поверхность) — сюда экран репортит её события
 * ([onVideoStarted]/[onVideoFinished]/[onVideoError]), а ViewModel лишь прокладывает
 * их в контроллер, чтобы состояние оставалось единым источником правды.
 *
 * Хэндофф «запись → заливка» здесь НЕ делаем — он уже встроен в
 * [RecordingController.update]: при [RecordingState.Finished] аудио-записи контроллер
 * сам ставит заливку в очередь WorkManager (C3). Видео на сервер не уезжает (эпик
 * NIVEL#235) — для него заливки нет вовсе.
 */
@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val controller: RecordingController,
) : ViewModel() {

    /** Состояние записи — единый источник правды, переживает пересоздание Activity. */
    val state: StateFlow<RecordingState> = controller.state

    /** Старт аудио-записи для сессии. Разрешения экран запрашивает заранее (см. RecorderScreen). */
    fun start(sessionId: String) = controller.start(sessionId)

    /** Остановить аудио-запись — контроллер завершит сервис и инициирует заливку. */
    fun stop() = controller.stop()

    /** Сбросить Finished/Error в Idle после того, как UI «забрал» результат. */
    fun acknowledge() = controller.acknowledge()

    /** CameraX начал писать [videoFile] — фиксируем это в общем состоянии. */
    fun onVideoStarted(sessionId: String, videoFile: File) {
        controller.update(
            RecordingState.Recording(
                sessionId = sessionId,
                startedElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                mode = RecordingMode.VIDEO,
                videoPath = videoFile.absolutePath,
            ),
        )
    }

    /** CameraX финализировал файл — видео готово (остаётся только локально). */
    fun onVideoFinished(sessionId: String, videoFile: File, durationMs: Long) {
        controller.update(
            RecordingState.Finished(
                sessionId = sessionId,
                durationMs = durationMs,
                mode = RecordingMode.VIDEO,
                videoPath = videoFile.absolutePath,
            ),
        )
    }

    /** Видеозапись не удалась (нет файла/сбой камеры) — например, обрыв без данных. */
    fun onVideoError(sessionId: String?, message: String) {
        controller.update(RecordingState.Error(sessionId, message, RecordingMode.VIDEO))
    }
}
