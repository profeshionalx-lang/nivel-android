package com.nivel.trainer.service.video

import android.content.Context
import android.os.StatFs
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Имя файла видеозаписи тренировки (A3, #97). Формат совпадает с аудио
 * (`session-<id>-<epochMs>.m4a` в [com.nivel.trainer.service.RecordingService]),
 * только расширение другое — по нему сканом каталога можно восстановить связь
 * с сессией без отдельного хранилища метаданных (Room под это не заводим, см.
 * `LocalVideoStore` в задаче A4/#98).
 */
object VideoFileNaming {
    const val RECORDINGS_DIR = "recordings"

    fun file(context: Context, sessionId: String): File {
        val safeId = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        return File(dir, "session-$safeId-${System.currentTimeMillis()}.mp4")
    }
}

/**
 * Оценка свободного места под видео (A3, #97). Битрейт `Quality.HD` (720p, без звука)
 * у CameraX ложится в ~2–3 ГБ/час (см. эпик NIVEL#235) — берём консервативную
 * середину диапазона, чтобы предупреждение появлялось с запасом, а не впритык.
 */
object VideoFreeSpace {
    /** Грубая оценка мегабайт в минуту записи при Quality.HD без звука. */
    private const val ESTIMATED_MB_PER_MINUTE = 45

    /** Ниже этого порога — предупреждаем тренера перед стартом записи. */
    const val LOW_SPACE_WARNING_MINUTES = 10

    /** Сколько минут видео примерно влезет в свободное место `filesDir`. */
    fun estimatedMinutesRemaining(context: Context): Int {
        val stat = StatFs(context.filesDir.absolutePath)
        val freeBytes = stat.availableBytes
        val freeMb = freeBytes / (1024 * 1024)
        return (freeMb / ESTIMATED_MB_PER_MINUTE).toInt()
    }
}

/**
 * Человекочитаемый размер видеофайла (A9, #103) — индикатор занятого места на экране
 * сессии и текст предупреждения перед удалением («Видео тренировки (N ГБ)…»).
 */
object VideoFileSize {
    private const val MB = 1024.0 * 1024.0
    private const val GB = 1024.0 * MB

    /** «1,3 ГБ» для файлов от гигабайта, «180 МБ» для файлов меньше. */
    fun format(bytes: Long): String {
        val gb = bytes / GB
        if (gb >= 1.0) return String.format(Locale("ru", "RU"), "%.1f ГБ", gb)
        val mb = (bytes / MB).roundToInt().coerceAtLeast(1)
        return "$mb МБ"
    }
}

/**
 * Результат остановки видеозаписи: файл готов ([Success]) или запись не удалась
 * ([Failure] — например, файл пустой/не создался вовсе).
 */
sealed interface VideoRecordingResult {
    data class Success(val file: File) : VideoRecordingResult
    data class Failure(val message: String) : VideoRecordingResult
}

/**
 * Тонкая обёртка над CameraX (превью + `Recorder`) для видеозаписи тренировки (A3, #97).
 *
 * Не Hilt-синглтон: живёт по времени жизни экрана записи (создаётся `remember{}` в
 * [com.nivel.trainer.feature.recorder.RecorderScreen]), потому что CameraX завязан на
 * [LifecycleOwner] и [PreviewView] — тренеру нужно видеть кадр, чтобы навести штатив.
 * Единственный источник правды о состоянии записи всё равно
 * [com.nivel.trainer.service.RecordingController] — эта обёртка только запускает/
 * останавливает нижележащий `Recorder` и репортит события наверх колбэками.
 *
 * Пишем **без звука** (`withAudioEnabled()` не вызываем): звук в видео-режиме
 * добавит параллельный `MediaRecorder` в A4 (#98), иначе конфликт за микрофон с ним же.
 */
class VideoRecorder(private val context: Context) {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    /**
     * Поднять камеру и привязать превью+рекордер к [lifecycleOwner]. Из-за того, что
     * привязка идёт к жизненному циклу экрана — сворачивание/звонок (`ON_STOP`)
     * автоматически отпускают камеру, останавливая активную запись (см. [stop]
     * вызывается неявно CameraX финализацией — обработчик события ставится в [start]).
     */
    suspend fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        val provider = getCameraProvider()
        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val capture = VideoCapture.withOutput(recorder)

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture,
        )
        videoCapture = capture
    }

    /**
     * Начать запись в [file]. [onEvent] зовётся на главном потоке при финализации
     * (успех/ошибка/обрыв — сворачивание, звонок, нехватка места на середине).
     * Повторный вызов во время активной записи игнорируется.
     */
    fun start(file: File, onEvent: (VideoRecordingResult) -> Unit) {
        val capture = videoCapture ?: run {
            onEvent(VideoRecordingResult.Failure("Камера не готова"))
            return
        }
        if (activeRecording != null) return

        val outputOptions = FileOutputOptions.Builder(file).build()
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            // Без .withAudioEnabled() — видео пишем без звука (см. класс-doc).
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    activeRecording = null
                    val hasError = event.hasError()
                    // Даже при ошибке (обрыв — сворачивание/звонок) CameraX финализирует
                    // контейнер: если файл непустой, он играбелен — считаем это успехом,
                    // а не падением, чтобы частичная запись не терялась.
                    if (file.exists() && file.length() > 0) {
                        onEvent(VideoRecordingResult.Success(file))
                    } else {
                        onEvent(
                            VideoRecordingResult.Failure(
                                if (hasError) {
                                    "Запись прервана: ${event.cause?.message ?: "ошибка камеры"}"
                                } else {
                                    "Запись слишком короткая или повреждена"
                                },
                            ),
                        )
                    }
                }
            }
    }

    /** Остановить активную запись (кнопка «Стоп»); финализация придёт в колбэк [start]. */
    fun stop() {
        activeRecording?.stop()
        activeRecording = null
    }

    /** Отвязать камеру (уход с экрана записи) — CameraX сам остановит активную запись. */
    fun release() {
        activeRecording?.stop()
        activeRecording = null
        runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        videoCapture = null
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (cont.isActive) cont.resume(future.get())
            },
            ContextCompat.getMainExecutor(context),
        )
    }
}
