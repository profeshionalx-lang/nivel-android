package com.nivel.trainer.feature.frames

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nivel.trainer.ui.state.EmptyStateView
import kotlin.math.roundToInt

// Цвета — один-в-один из веб-Nivel (globals.css), как на остальных экранах приложения.
private val Background = Color(0xFF0E0E0E)
private val SurfaceCard = Color(0xFF1E1E1E)
private val Primary = Color(0xFFCAFD00)
private val OnPrimary = Color(0xFF000000)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)
private val BorderDim = Color(0xFF2E2E2E)

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp
private val ThumbnailWidth = 64.dp

/**
 * Экран скрабера кадра (A6, #100) — сердце эпика видео-инсайтов. Веб-эталона нет и не
 * будет (скрабера в вебе не планируется, NIVEL#235) — экран согласован владельцем как
 * новый, стиль держится существующих экранов приложения (цвета/тач-зоны/отступы).
 *
 * [onResult] — экран нашёл кадр и сохранил JPEG, [onCancel] — тренер передумал; в обоих
 * случаях вызывающая сторона сама решает, что показать дальше (см. `NivelNavHost`, где
 * [onResult] кладёт [FrameSelectionResult] в `savedStateHandle` предыдущей записи).
 */
@Composable
fun FrameScrubberScreen(
    sessionId: String,
    cardId: String,
    slot: FrameSlot,
    onResult: (FrameSelectionResult) -> Unit,
    onCancel: () -> Unit,
    viewModel: FrameScrubberViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId, cardId, slot) { viewModel.load(sessionId, cardId, slot) }
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        when (val current = state) {
            is FrameScrubberUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            is FrameScrubberUiState.NoVideo -> BlockingMessage(glyph = "🎥", message = current.message, onCancel = onCancel)
            is FrameScrubberUiState.Error -> BlockingMessage(glyph = "⚠", message = current.message, onCancel = onCancel)
            is FrameScrubberUiState.Ready -> ReadyContent(
                state = current,
                slotLabel = if (slot == FrameSlot.BEFORE) "до" else "после",
                onThumbnailSelected = viewModel::onThumbnailSelected,
                onSliderIndexChanged = { viewModel.onSliderIndexChanged(it.roundToInt()) },
                onExpandWindow = viewModel::onExpandWindow,
                onCancel = onCancel,
                onConfirm = {
                    viewModel.onConfirm(
                        cardId = cardId,
                        slot = slot,
                        onResult = onResult,
                        // Ошибка сохранения — редкий случай (диск/декод); экран просто
                        // возвращает saving=false и тренер может попробовать ещё раз
                        // (полноценный тост/снекбар — не в acceptance A6).
                        onError = {},
                    )
                },
            )
        }
    }
}

@Composable
private fun BlockingMessage(glyph: String, message: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyStateView(title = message, glyph = glyph)
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("Закрыть", color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReadyContent(
    state: FrameScrubberUiState.Ready,
    slotLabel: String,
    onThumbnailSelected: (Int) -> Unit,
    onSliderIndexChanged: (Float) -> Unit,
    onExpandWindow: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(TouchTarget)) {
                Text("✕", color = OnSurface, fontSize = 18.sp)
            }
            Text(
                text = "Кадр «$slotLabel»",
                color = OnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // Крупное превью текущего выбора — кадр из уже разобранного окна (#117: слайдер
        // листает готовые кадры, ничего не декодируя). Честный OPTION_CLOSEST — только в
        // итоговом JPEG при «Выбрать кадр» (см. FrameScrubberViewModel.onConfirm).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            val preview = state.previewBitmap
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (state.buildingFilmstrip) {
                CircularProgressIndicator(color = Primary, modifier = Modifier.size(28.dp))
            }
        }

        Spacer(Modifier.size(8.dp))
        Text(
            text = formatTimestamp(state.selectedTimeMs),
            color = OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(8.dp))
        // Дискретный слайдер (#117: точность до кадра не нужна, «в рамках полсекунды
        // нормально») — value/valueRange/steps индексируют state.frames, не миллисекунды
        // видео; листание ничего не декодирует, поэтому спиннер тут не нужен.
        val lastIndex = (state.frames.size - 1).coerceAtLeast(0)
        Slider(
            value = state.selectedIndex.toFloat(),
            onValueChange = onSliderIndexChanged,
            valueRange = 0f..lastIndex.toFloat(),
            steps = (lastIndex - 1).coerceAtLeast(0),
            enabled = state.frames.size > 1,
            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary, inactiveTrackColor = BorderDim),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = TouchTarget),
        )

        // Плёнка миниатюр — горизонтальный скролл со snap (mobile-first: overflow-x, не
        // вертикальный список), тач-зона каждого кадра ≥44dp даже на landscape-видео,
        // где сама миниатюра ниже (см. heightIn на Box ниже).
        if (state.buildingFilmstrip) {
            val progress = state.buildProgress
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                LinearProgressIndicator(
                    progress = { if (progress != null && progress.second > 0) progress.first / progress.second.toFloat() else 0f },
                    color = Primary,
                    trackColor = BorderDim,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = if (progress != null) "Строим плёнку… ${progress.first}/${progress.second}" else "Строим плёнку…",
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        if (state.frames.isNotEmpty()) {
            val listState = rememberLazyListState()
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                itemsIndexed(state.frames, key = { _, frame -> frame.videoTimeMs }) { index, frame ->
                    val selected = index == state.selectedIndex
                    Box(
                        modifier = Modifier
                            .heightIn(min = TouchTarget)
                            .width(ThumbnailWidth)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Primary.copy(alpha = 0.2f) else SurfaceCard)
                            .clickable { onThumbnailSelected(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = frame.bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .width(ThumbnailWidth)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.size(4.dp))
        if (!state.expanded) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onExpandWindow, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text("Расширить окно (±30 с)", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TouchTarget),
            ) {
                Text("Отмена", color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onConfirm,
                enabled = !state.saving && state.frames.isNotEmpty(),
                modifier = Modifier
                    .weight(2f)
                    .heightIn(min = TouchTarget),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = Primary.copy(alpha = 0.4f),
                    disabledContentColor = OnPrimary,
                ),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(color = OnPrimary, modifier = Modifier.size(18.dp))
                } else {
                    Text("Выбрать кадр", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * `мм:сс,д` (десятые доли) — владелец решил, что точность до кадра не нужна («в рамках
 * полсекунды нормально», #117), сетка позиций — 500мс и крупнее, показывать миллисекунды
 * было бы обманчивой точностью.
 */
private fun formatTimestamp(timeMs: Long): String {
    val totalMs = timeMs.coerceAtLeast(0)
    val minutes = totalMs / 60_000
    val seconds = (totalMs % 60_000) / 1_000
    val tenths = (totalMs % 1_000) / 100
    return "%02d:%02d,%d".format(minutes, seconds, tenths)
}
