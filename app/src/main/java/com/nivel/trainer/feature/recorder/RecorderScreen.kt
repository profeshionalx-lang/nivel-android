package com.nivel.trainer.feature.recorder

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.service.RecordingPermissions
import com.nivel.trainer.service.RecordingState
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
 * Экран записи тренировки (C2, #11).
 *
 * Нативный эквивалент веб-аплоадера (`components/sessions/AudioUploader`): тренер
 * не заливает файл, а записывает прямо в приложении. Запись привязана к выбранной
 * сессии [sessionId]; владеет ей process-wide
 * [RecordingController][com.nivel.trainer.service.RecordingController] через
 * foreground-сервис (C1), поэтому она переживает уход с экрана и лок (можно
 * остановить из уведомления в шторке).
 *
 * Поток: вход → запрос разрешений → старт → живой таймер + «Стоп» → по «Стоп»
 * запись завершается и уходит на заливку (хэндофф в контроллере, C3) → короткий
 * статус → авто-возврат на карточку (там появится «Транскрипция…»).
 */
@Composable
fun RecorderScreen(
    sessionId: String,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecorderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Отказ в разрешении на микрофон — показываем объяснение вместо записи.
    // Saveable, чтобы после поворота экрана не дёргать системный диалог повторно.
    var micDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // POST_NOTIFICATIONS (13+) не критичен: без него запись идёт, но без уведомления.
        // Критичен только микрофон — без него на Android 14+ FGS-microphone не стартует.
        val micGranted = result[android.Manifest.permission.RECORD_AUDIO] == true
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
    // Ключ — факт завершения (а не весь объект), чтобы не перезапускаться на иных апдейтах.
    val finished = state is RecordingState.Finished
    LaunchedEffect(finished) {
        if (finished) {
            delay(1_400)
            viewModel.acknowledge()
            onClose()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Header(onBack = onClose)

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
}

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

/** Активная запись: «идёт запись» + живой таймер + крупная кнопка «Стоп». */
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

/** Объяснение при отказе в доступе к микрофону + пути выдачи. */
@Composable
private fun PermissionDeniedContent(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("🎙", fontSize = 44.sp)
        Text(
            text = "Нужен доступ к микрофону",
            color = OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Без него записать тренировку не получится. Разрешите доступ к микрофону.",
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
                    outputPath = "/tmp/a.m4a",
                    startedElapsedRealtimeMs = SystemClock.elapsedRealtime() - 125_000,
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
            PermissionDeniedContent(onGrant = {}, onOpenSettings = {})
        }
    }
}
