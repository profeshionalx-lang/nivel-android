package com.nivel.trainer.service.video

import android.content.Context
import android.os.StatFs
import android.view.OrientationEventListener
import android.view.Surface
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
import kotlin.coroutines.resume
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
 * Результат остановки видеозаписи: файл готов ([Success]) или запись не удалась
 * ([Failure] — например, файл пустой/не создался вовсе).
 *
 * [Success.interrupted] (#106) — true, если CameraX финализировал `Recording` с
 * `hasError() == true` (обрыв не по команде тренера — например, поворот экрана
 * успел проскочить до фикса, сбой камеры, нехватка места на середине), но файл
 * при этом непустой и играбельный. Частичный mp4 — осознанный успех (годится для
 * выбора кадров), но тренер должен видеть, что запись прервалась не сама.
 */
sealed interface VideoRecordingResult {
    data class Success(val file: File, val interrupted: Boolean = false) : VideoRecordingResult
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

    // Провайдер сохраняется из bind() (#106), чтобы release() не дёргал повторный
    // блокирующий ProcessCameraProvider.getInstance(context).get() на главном потоке —
    // future там гарантированно уже разрешён к моменту первого bind(), но повторный
    // вызов getInstance() всё равно синхронный API и в теории может подвиснуть.
    private var cameraProvider: ProcessCameraProvider? = null

    // #106: activity больше не пересоздаётся на поворот (см. AndroidManifest —
    // configChanges), поэтому Preview/VideoCapture держат targetRotation, снятый
    // ОДИН раз в bind(). Раньше пересоздание Activity само переустанавливало его —
    // теперь это отслеживаем отдельно через сенсор, иначе после физического
    // поворота (телефон на штативе, лёг горизонтально) записанный файл выйдет
    // повёрнутым, хотя сама запись больше не прерывается.
    private var rotationListener: OrientationEventListener? = null

    /**
     * Поднять камеру и привязать превью+рекордер к [lifecycleOwner]. Из-за того, что
     * привязка идёт к жизненному циклу экрана — сворачивание/звонок (`ON_STOP`)
     * автоматически отпускают камеру, останавливая активную запись (см. [stop]
     * вызывается неявно CameraX финализацией — обработчик события ставится в [start]).
     */
    suspend fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        val provider = getCameraProvider()
        cameraProvider = provider
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
        trackRotation(preview, capture)
    }

    /**
     * Держит `targetRotation` [preview]/[capture] в актуальном состоянии по показаниям
     * сенсора (#106) — без этого после поворота посреди записи (или до старта, пока
     * тренер наводит штатив) итоговый mp4 записался бы с устаревшей ориентацией, хотя
     * сама запись теперь и не прерывается. Пороги — стандартная раскладка 4 квадрантов
     * по 90°, со сдвигом на полквадранта, как в примерах CameraX.
     */
    private fun trackRotation(preview: Preview, capture: VideoCapture<Recorder>) {
        rotationListener?.disable()
        rotationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                preview.targetRotation = rotation
                capture.targetRotation = rotation
            }
        }.apply { enable() }
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
                        onEvent(VideoRecordingResult.Success(file, interrupted = hasError))
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

    /**
     * Отвязать камеру (уход с экрана записи) — CameraX сам остановит активную запись.
     * Использует провайдер, сохранённый в [bind] (#106) — без повторного блокирующего
     * `ProcessCameraProvider.getInstance().get()` на главном потоке.
     */
    fun release() {
        activeRecording?.stop()
        activeRecording = null
        rotationListener?.disable()
        rotationListener = null
        cameraProvider?.unbindAll()
        cameraProvider = null
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
