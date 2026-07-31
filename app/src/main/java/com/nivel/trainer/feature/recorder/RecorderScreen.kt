package com.nivel.trainer.feature.recorder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.service.RecordingMode
import com.nivel.trainer.service.RecordingPermissions
import com.nivel.trainer.service.RecordingState
import com.nivel.trainer.service.video.VideoFileNaming
import com.nivel.trainer.service.video.VideoFreeSpace
import com.nivel.trainer.service.video.VideoRecorder
import com.nivel.trainer.service.video.VideoRecordingResult
import com.nivel.trainer.ui.theme.NivelTheme
import kotlinx.coroutines.delay

// Палитра один-в-один из веб-Nivel (src/app/globals.css), как на B4/B5/B6.
private val Background = Color(0xFF0E0E0E)
private val SurfaceCard = Color(0xFF1E1E1E)
private val Primary = Color(0xFFCAFD00)
private val OnPrimary = Color(0xFF000000)
private val BorderDim = Color(0xFF2E2E2E)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)
private val ErrorColor = Color(0xFFFF7351)
private val RecDot = Color(0xFFFF3B30) // «идёт запись» индикатор

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/**
 * Экран записи тренировки (C2, #11; выбор режима — A3, #97).
 *
 * Нативный эквивалент веб-аплоадера (`components/sessions/AudioUploader`): тренер
 * не заливает файл, а записывает прямо в приложении. До старта тренер выбирает режим:
 * «Аудио» (телефон в кармане, как раньше — [RecordingMode.AUDIO]) или «Видео» (телефон
 * на штативе — [RecordingMode.VIDEO]). Запись привязана к выбранной сессии [sessionId];
 * состоянием владеет process-wide
 * [RecordingController][com.nivel.trainer.service.RecordingController] в обоих режимах,
 * но механика разная: аудио идёт через foreground-сервис (C1) и переживает уход с
 * экрана, видео — через CameraX прямо на этом экране (нужна превью-поверхность),
 * поэтому сворачивание/звонок его останавливают (см. [VideoFlow]).
 */
@Composable
fun RecorderScreen(
    sessionId: String,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecorderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Режим выбирается один раз за вход на экран. Saveable, чтобы пережить поворот.
    // Если экран переоткрыли во время активной/только что завершённой записи —
    // подхватываем режим из состояния, а не спрашиваем заново.
    var selectedModeName by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val modeFromState = when (val s = state) {
            is RecordingState.Recording -> s.mode
            is RecordingState.Finished -> s.mode
            is RecordingState.Error -> s.mode
            RecordingState.Idle -> null
        }
        if (selectedModeName == null && modeFromState != null) {
            selectedModeName = modeFromState.name
        }
    }
    val selectedMode = selectedModeName?.let { RecordingMode.valueOf(it) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Header(onBack = onClose)

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedMode) {
                null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ModeSelectContent(onSelect = { selectedModeName = it.name })
                }

                RecordingMode.AUDIO -> AudioFlow(
                    sessionId = sessionId,
                    state = state,
                    viewModel = viewModel,
                    onClose = onClose,
                )

                RecordingMode.VIDEO -> VideoFlow(
                    sessionId = sessionId,
                    state = state,
                    viewModel = viewModel,
                    onClose = onClose,
                )
            }
        }
    }
}

/** Выбор режима до старта записи (A3, #97). Аудио — как раньше, видео — новый режим. */
@Composable
private fun ModeSelectContent(onSelect: (RecordingMode) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Как записываем?",
            color = OnSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        ModeOptionCard(
            glyph = "🎙",
            title = "Аудио",
            subtitle = "Телефон в кармане. Экран можно заблокировать.",
            onClick = { onSelect(RecordingMode.AUDIO) },
        )
        ModeOptionCard(
            glyph = "🎥",
            title = "Видео",
            subtitle = "Телефон на штативе. Экран не гаснет во время записи.",
            onClick = { onSelect(RecordingMode.VIDEO) },
        )
    }
}

@Composable
private fun ModeOptionCard(
    glyph: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, fontSize = 30.sp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = OnSurfaceVariant, fontSize = 12.sp)
        }
        Button(
            onClick = onClick,
            modifier = Modifier.heightIn(min = TouchTarget),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
        ) {
            Text("Выбрать", fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

// --- Аудио-режим (C1/C2, поведение не изменилось — только вынесено в отдельную функцию) ---

@Composable
private fun AudioFlow(
    sessionId: String,
    state: RecordingState,
    viewModel: RecorderViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    // Отказ в разрешении на микрофон — показываем объяснение вместо записи.
    var micDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // POST_NOTIFICATIONS (13+) не критичен: без него запись идёт, но без уведомления.
        // Критичен только микрофон — без него на Android 14+ FGS-microphone не стартует.
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            micDenied = false
            viewModel.start(sessionId)
        } else {
            micDenied = true
        }
    }

    // Запуск при входе на экран. Контроллер `@Singleton` и его стейт process-wide,
    // поэтому при входе мог остаться терминальный стейт ПРОШЛОЙ записи (Finished/Error,
    // если с экрана ушли «Назад» до авто-сброса) — сбрасываем его, чтобы не залипнуть
    // на чужом результате. Если активная запись уже идёт (экран переоткрыт во время
    // записи) — ничего не стартуем, просто покажем таймер.
    LaunchedEffect(Unit) {
        if (state is RecordingState.Finished || state is RecordingState.Error) {
            viewModel.acknowledge()
        }
        if (state !is RecordingState.Recording && !micDenied) {
            if (RecordingPermissions.hasMicPermission(context)) {
                viewModel.start(sessionId)
            } else {
                permissionLauncher.launch(RecordingPermissions.required)
            }
        }
    }

    // По завершении записи: короткий статус, затем сброс и возврат на карточку.
    val finished = state is RecordingState.Finished
    LaunchedEffect(finished) {
        if (finished) {
            delay(1_400)
            viewModel.acknowledge()
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is RecordingState.Recording -> RecordingContent(
                recording = s,
                onStop = viewModel::stop,
            )

            is RecordingState.Finished -> StatusContent(
                glyph = "✓",
                glyphColor = Primary,
                title = "Запись сохранена",
                subtitle = "Транскрипция запущена — обычно занимает 15–30 секунд.",
            )

            is RecordingState.Error -> ErrorContent(
                message = s.message,
                onRetry = {
                    viewModel.acknowledge()
                    if (RecordingPermissions.hasMicPermission(context)) {
                        viewModel.start(sessionId)
                    } else {
                        permissionLauncher.launch(RecordingPermissions.required)
                    }
                },
                onBack = onClose,
            )

            RecordingState.Idle ->
                if (micDenied) {
                    PermissionDeniedContent(
                        glyph = "🎙",
                        title = "Нужен доступ к микрофону",
                        message = "Без него записать тренировку не получится. Разрешите доступ к микрофону.",
                        onGrant = { permissionLauncher.launch(RecordingPermissions.required) },
                        onOpenSettings = { context.openAppSettings() },
                    )
                } else {
                    // Кратковременно: разрешение выдаётся / запись поднимается.
                    CircularProgressIndicator(color = Primary)
                }
        }
    }
}

// --- Видео-режим (A3, #97) ---

/**
 * Видео-flow: разрешение на камеру → превью со штатива → проверка места →
 * «Начать запись» → таймер поверх превью → «Стоп» → готово.
 *
 * [VideoRecorder] и `PreviewView` держим на протяжении ВСЕЙ функции (один `remember`),
 * а не пересоздаём между стадиями «до старта»/«идёт запись» — иначе при каждом тапе
 * «Начать запись» камера будет перепривязываться с видимым миганием превью.
 */
@Composable
private fun VideoFlow(
    sessionId: String,
    state: RecordingState,
    viewModel: RecorderViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Видео-режиму (A4, #98) нужны и камера (кадр), и микрофон (параллельный
    // MediaRecorder звука) — запрашиваем оба разом одним лаунчером.
    var permissionsDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
        permissionsDenied = !(cameraGranted && micGranted)
    }
    val hasCamera = RecordingPermissions.hasCameraPermission(context)
    val hasMic = RecordingPermissions.hasMicPermission(context)
    val hasAllPermissions = hasCamera && hasMic

    LaunchedEffect(Unit) {
        if (state is RecordingState.Finished || state is RecordingState.Error) {
            viewModel.acknowledge()
        }
        if (!hasAllPermissions && !permissionsDenied) {
            permissionLauncher.launch(RecordingPermissions.requiredForVideo)
        }
    }

    if (!hasAllPermissions) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (permissionsDenied) {
                PermissionDeniedContent(
                    glyph = "🎥",
                    title = "Нужен доступ к камере и микрофону",
                    message = "Без них снять видео со звуком не получится. Разрешите доступ к камере и микрофону.",
                    onGrant = { permissionLauncher.launch(RecordingPermissions.requiredForVideo) },
                    onOpenSettings = { context.openAppSettings() },
                )
            } else {
                CircularProgressIndicator(color = Primary)
            }
        }
        return
    }

    // Экран не гаснет весь видео-flow (кадрирование + запись) — телефон на штативе,
    // тренеру важно, чтобы превью/таймер не пропали посреди тренировки.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val videoRecorder = remember { VideoRecorder(context.applicationContext) }
    val previewView = remember { PreviewView(context) }
    DisposableEffect(Unit) {
        onDispose { videoRecorder.release() }
    }
    LaunchedEffect(previewView) {
        videoRecorder.bind(previewView, lifecycleOwner)
    }

    // По завершении записи: короткий статус, затем сброс и возврат на карточку.
    val finished = state is RecordingState.Finished && state.mode == RecordingMode.VIDEO
    LaunchedEffect(finished) {
        if (finished) {
            // #106: обрыв держим на экране дольше обычного «успеха» — тренер должен
            // успеть прочитать, что запись прервалась не по его команде, а не просто
            // мелькнуть тем же временем, что и штатное «Видео сохранено».
            val interrupted = (state as? RecordingState.Finished)?.interrupted == true
            delay(if (interrupted) 3_000 else 1_400)
            viewModel.acknowledge()
            onClose()
        }
    }

    when (val s = state) {
        is RecordingState.Recording -> if (s.mode == RecordingMode.VIDEO) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                VideoRecordingOverlay(
                    recording = s,
                    onStop = {
                        // Останавливаем оба рекордера почти одновременно: CameraX — сама
                        // финализация видео придёт в onVideoFinished асинхронно; звук —
                        // сразу же, иначе MediaRecorder продолжит писать после «Стоп»
                        // (ACTION_STOP идёт в RecordingService, который его и держит).
                        videoRecorder.stop()
                        viewModel.stopVideoAudioSidecar()
                    },
                )
            }
        } else {
            // Чужой (аудио) Recording в этой ветке не встречается — режим фиксирован
            // на входе, но на всякий случай не рисуем ничего конфликтующего.
        }

        is RecordingState.Finished -> if (s.mode == RecordingMode.VIDEO) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                // #106: если CameraX финализировал с ошибкой (обрыв не по команде
                // тренера — например, сбой камеры или нехватка места на середине),
                // но файл непустой — не рисуем это как обычный «успех» молча.
                if (s.interrupted) {
                    StatusContent(
                        glyph = "⚠",
                        glyphColor = ErrorColor,
                        title = "Запись прервалась",
                        subtitle = "Снято ${formatDuration(s.durationMs)} — часть тренировки не попала в " +
                            "запись. Файл сохранён и пригодится для разбора моментов.",
                    )
                } else {
                    StatusContent(
                        glyph = "✓",
                        glyphColor = Primary,
                        title = "Видео сохранено",
                        subtitle = "Файл остался на телефоне — пригодится для разбора моментов.",
                    )
                }
            }
        }

        is RecordingState.Error -> if (s.mode == RecordingMode.VIDEO) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                ErrorContent(message = s.message, onRetry = { viewModel.acknowledge() }, onBack = onClose)
            }
        }

        RecordingState.Idle -> Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            VideoPreStartOverlay(
                onStart = {
                    val file = VideoFileNaming.file(context, sessionId)
                    val startedAt = SystemClock.elapsedRealtime()
                    viewModel.onVideoStarted(sessionId, file)
                    videoRecorder.start(file) { result ->
                        when (result) {
                            is VideoRecordingResult.Success -> viewModel.onVideoFinished(
                                sessionId = sessionId,
                                videoFile = result.file,
                                durationMs = SystemClock.elapsedRealtime() - startedAt,
                                interrupted = result.interrupted,
                            )

                            is VideoRecordingResult.Failure -> viewModel.onVideoError(sessionId, result.message)
                        }
                    }
                },
            )
        }
    }
}

/** Превью-стадия видео: проверка места + крупная кнопка «Начать запись». */
@Composable
private fun VideoPreStartOverlay(onStart: () -> Unit) {
    val context = LocalContext.current
    val estimatedMinutes = remember { VideoFreeSpace.estimatedMinutesRemaining(context) }
    var forceStart by rememberSaveable { mutableStateOf(false) }
    val lowSpace = estimatedMinutes < VideoFreeSpace.LOW_SPACE_WARNING_MINUTES

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (lowSpace && !forceStart) {
            LowSpaceWarningCard(
                estimatedMinutes = estimatedMinutes,
                onContinueAnyway = { forceStart = true },
            )
        } else {
            Text(
                text = if (estimatedMinutes >= 60) {
                    "Свободного места хватит примерно на ${estimatedMinutes / 60} ч. Поставьте телефон на зарядку для длинной тренировки."
                } else {
                    "Свободного места хватит примерно на $estimatedMinutes мин."
                },
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            ) {
                Text("Начать запись", fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** Предупреждение о нехватке места (A3, #97) — блокирует старт, но не приложение. */
@Composable
private fun LowSpaceWarningCard(estimatedMinutes: Int, onContinueAnyway: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⚠ Мало места", color = ErrorColor, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Text(
            text = "Свободного места хватит примерно на $estimatedMinutes мин видео. " +
                "Освободите место или снимите короче — иначе запись может прерваться.",
            color = OnSurfaceVariant,
            fontSize = 13.sp,
        )
        Button(
            onClick = onContinueAnyway,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
        ) {
            Text("Всё равно записывать", fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** Активная видеозапись: таймер + «Стоп» поверх превью камеры. */
@Composable
private fun VideoRecordingOverlay(recording: RecordingState.Recording, onStop: () -> Unit) {
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording.startedElapsedRealtimeMs) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val elapsedMs = (nowMs - recording.startedElapsedRealtimeMs).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .background(SurfaceCard, RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(10.dp).background(RecDot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "REC ${formatDuration(elapsedMs)}",
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(top = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
        ) {
            Text("Стоп", fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

// --- Общие блоки ---

/** Хедер как на остальных экранах: «‹» назад + центрированный заголовок «Запись». */
@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(TouchTarget)) {
            Text("‹", color = OnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "Запись",
            color = Primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(TouchTarget))
    }
}

/** Активная запись: «идёт запись» + живой таймер + крупная кнопка «Стоп» (аудио). */
@Composable
private fun RecordingContent(
    recording: RecordingState.Recording,
    onStop: () -> Unit,
) {
    // Тик таймера от монотонных часов (как в уведомлении): не зависит от перевода
    // системного времени. Обновляем 4×/сек — секунды не «прыгают».
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording.startedElapsedRealtimeMs) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val elapsedMs = (nowMs - recording.startedElapsedRealtimeMs).coerceAtLeast(0)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(RecDot, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Идёт запись",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
        }

        Text(
            text = formatDuration(elapsedMs),
            color = OnSurface,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
        )

        Text(
            text = "Положите телефон в карман — запись продолжится с заблокированным экраном. Остановить можно здесь или из шторки.",
            color = OnSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )

        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = OnPrimary,
            ),
        ) {
            Text("Стоп", fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** Объяснение при отказе в доступе к микрофону/камере + пути выдачи. */
@Composable
private fun PermissionDeniedContent(
    glyph: String,
    title: String,
    message: String,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(glyph, fontSize = 44.sp)
        Text(
            text = title,
            color = OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            color = OnSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onGrant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = OnPrimary,
            ),
        ) {
            Text("Разрешить", fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Открыть настройки", color = OnSurface, fontSize = 14.sp)
        }
    }
}

/** Ошибка записи: текст + повтор/назад. */
@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("⚠", fontSize = 44.sp, color = ErrorColor)
        Text(
            text = "Не удалось записать",
            color = OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            color = OnSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = OnPrimary,
            ),
        ) {
            Text("Повторить", fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Назад", color = OnSurface, fontSize = 14.sp)
        }
    }
}

/** Нейтральный статус-экран (например, «Запись сохранена»). */
@Composable
private fun StatusContent(
    glyph: String,
    glyphColor: Color,
    title: String,
    subtitle: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(glyph, fontSize = 48.sp, color = glyphColor)
        Text(
            text = title,
            color = OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            color = OnSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Открыть системные настройки приложения (для выдачи разрешения вручную). */
private fun android.content.Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    startActivity(intent)
}

/** Длительность в `H:MM:SS` (часы только когда есть) или `MM:SS`. */
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

// --- Preview ---

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun RecordingPreview() {
    NivelTheme {
        Box(
            modifier = Modifier
                .background(Background)
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            RecordingContent(
                recording = RecordingState.Recording(
                    sessionId = "s1",
                    startedElapsedRealtimeMs = SystemClock.elapsedRealtime() - 125_000,
                    mode = RecordingMode.AUDIO,
                    outputPath = "/tmp/a.m4a",
                ),
                onStop = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun PermissionDeniedPreview() {
    NivelTheme {
        Box(
            modifier = Modifier
                .background(Background)
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            PermissionDeniedContent(
                glyph = "🎙",
                title = "Нужен доступ к микрофону",
                message = "Без него записать тренировку не получится. Разрешите доступ к микрофону.",
                onGrant = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun ModeSelectPreview() {
    NivelTheme {
        Box(
            modifier = Modifier
                .background(Background)
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            ModeSelectContent(onSelect = {})
        }
    }
}
