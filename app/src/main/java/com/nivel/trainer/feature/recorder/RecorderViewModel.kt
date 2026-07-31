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
) : ViewModel() {

    /** Состояние записи — единый источник правды, переживает пересоздание Activity. */
    val state: StateFlow<RecordingState> = controller.state

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
