package com.nivel.trainer.feature.transcript

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.domain.Transcript
import com.nivel.trainer.domain.TranscriptSegment
import com.nivel.trainer.domain.TranscriptStatus
import com.nivel.trainer.ui.state.EmptyStateView
import com.nivel.trainer.ui.state.RefreshOnResume
import com.nivel.trainer.ui.theme.NivelTheme

// Цвета один-в-один из веб-Nivel (src/app/globals.css), как на других экранах (B4/B5).
private val Background = Color(0xFF0E0E0E)            // --background
private val SurfaceCard = Color(0xFF1E1E1E)          // --surface-card
private val Primary = Color(0xFFCAFD00)              // --primary (лайм)
private val OnPrimary = Color(0xFF000000)            // text на primary
private val OnSurface = Color(0xFFF5F5F5)            // --on-surface
private val OnSurfaceVariant = Color(0xFFADAAAA)     // --on-surface-variant
private val ErrorColor = Color(0xFFFF7351)           // --error (red-400 эквивалент)
private val BorderDim = Color(0xFF2A2A2A)            // --border-dim
// Подсветка сегментов по avg_logprob (как red-500/10, yellow-400/10 в TranscriptView).
private val LowConfBg = Color(0x1AFF7351)            // bg-red-500/10
private val LowConfBorder = Color(0xFFFF7351)        // border-red-400
private val MidConfBg = Color(0x1AFFD93D)            // bg-yellow-400/10
private val MidConfBorder = Color(0xFFFFD93D)        // border-yellow-400

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

private enum class TranscriptTab { SEGMENTS, FULLTEXT }

/**
 * Экран транскрипта тренировки (D1, #19) — порт веб-страницы
 * `src/app/sessions/[id]/transcript/page.tsx` + `TranscriptView.tsx`:
 * хедер «Транскрипт», два таба «По сегментам»/«Сплошной текст», состояния
 * processing (спиннер + автополлинг) / failed (ошибка) / ready (текст), действия
 * «Скачать» (share-intent) и «Копировать» (clipboard). Состояния загрузки/ошибки
 * сети обязательны (mobile-first).
 */
@Composable
fun TranscriptScreen(
    sessionId: String,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TranscriptViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    RefreshOnResume(onRefresh = viewModel::refresh)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // A9 (#79): сброс/удаление успешно завершились — уходим назад на карточку сессии,
    // как веб (`redirect(/sessions/{id})` в resetTranscript/deleteTranscript Server Actions).
    LaunchedEffect(state.navigateBack) {
        if (state.navigateBack) {
            onBack()
            viewModel.onNavigatedBack()
        }
    }

    TranscriptContent(
        sessionId = sessionId,
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        notFound = state.notFound,
        transcript = state.transcript,
        analysisStatus = state.analysisStatus,
        analysisError = state.analysisError,
        actionError = state.actionError,
        queuedMessage = state.queuedMessage,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onActionsClick = viewModel::openActionsSheet,
        onDismissQueuedMessage = viewModel::dismissQueuedMessage,
        modifier = modifier,
    )

    if (state.actionsSheetOpen) {
        ActionsMenuSheet(
            transcriptStatus = state.transcript?.status,
            canRequeueAnalysis = state.canRequeueAnalysis,
            actionInProgress = state.actionInProgress,
            onDismiss = viewModel::closeActionsSheet,
            onReset = { viewModel.requestAction(PendingDestructiveAction.RESET) },
            onRequeue = viewModel::requeueAnalysis,
            onDeleteClick = { viewModel.requestAction(PendingDestructiveAction.DELETE) },
        )
    }

    val pendingAction = state.pendingAction
    if (pendingAction != null) {
        ConfirmActionSheet(
            action = pendingAction,
            inProgress = state.actionInProgress,
            onDismiss = viewModel::cancelPendingAction,
            onConfirm = viewModel::confirmPendingAction,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptContent(
    sessionId: String,
    loading: Boolean,
    error: String?,
    transcript: Transcript?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    notFound: Boolean = false,
    analysisStatus: String? = null,
    analysisError: String? = null,
    actionError: String? = null,
    queuedMessage: String? = null,
    onActionsClick: () -> Unit = {},
    onDismissQueuedMessage: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        // A9 (#79): меню действий доступно только когда есть что сбрасывать/удалять/анализировать.
        Header(onBack = onBack, onActionsClick = if (transcript != null) onActionsClick else null)

        if (queuedMessage != null) {
            InlineBanner(message = queuedMessage, glyph = "⏳", onDismiss = onDismissQueuedMessage)
        }
        if (actionError != null) {
            InlineBanner(message = actionError, glyph = "⚠", color = ErrorColor, onDismiss = onDismissQueuedMessage)
        }

        // #71: pull-to-refresh — рефреш поверх уже загруженного транскрипта, без спиннера.
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = refreshing,
                    state = pullState,
                    containerColor = SurfaceCard,
                    color = Primary,
                )
            },
        ) {
            when {
                loading && transcript == null -> CenterBox { CircularProgressIndicator(color = Primary) }
                error != null && transcript == null -> CenterBox { ErrorState(error, onRetry) }
                transcript != null -> TranscriptBody(
                    sessionId = sessionId,
                    transcript = transcript,
                    analysisStatus = analysisStatus,
                    analysisError = analysisError,
                )
                notFound -> EmptyStateView(title = "Запись ещё не расшифрована.", glyph = "🎙")
                else -> CenterBox { EmptyState() }
            }
        }
    }
}

/** Хедер как glass-nav веба: назад «‹» + заголовок «Транскрипт» + меню действий «⋮» (A9, #79). */
@Composable
private fun Header(onBack: () -> Unit, onActionsClick: (() -> Unit)? = null) {
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
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Транскрипт",
            color = Primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.weight(1f),
        )
        if (onActionsClick != null) {
            IconButton(onClick = onActionsClick, modifier = Modifier.size(TouchTarget)) {
                Text("⋮", color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Ненавязчивый инлайн-баннер (очередь анализа / ошибка действия) с закрытием по тапу. */
@Composable
private fun InlineBanner(message: String, glyph: String, onDismiss: () -> Unit, color: Color = OnSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onDismiss)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(text = message, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TranscriptBody(
    sessionId: String,
    transcript: Transcript,
    analysisStatus: String?,
    analysisError: String?,
) {
    when (transcript.status) {
        TranscriptStatus.PROCESSING -> CenterBox {
            Column {
                ProcessingState()
                AnalysisStatusLine(analysisStatus = analysisStatus, analysisError = analysisError)
            }
        }
        TranscriptStatus.FAILED -> CenterBox {
            Column {
                FailedState(transcript.errorMessage)
                AnalysisStatusLine(analysisStatus = analysisStatus, analysisError = analysisError)
            }
        }
        TranscriptStatus.READY -> ReadyState(
            sessionId = sessionId,
            transcript = transcript,
            analysisStatus = analysisStatus,
            analysisError = analysisError,
        )
    }
}

/**
 * Статус анализа (A9, #79) — отдельная строка от статуса расшифровки, со своей
 * ошибкой. Показывается только когда есть что показать (готовый транскрипт — до
 * этого анализ ещё не запускался).
 */
@Composable
private fun AnalysisStatusLine(analysisStatus: String?, analysisError: String?) {
    if (analysisStatus == null) return
    val label = when (analysisStatus) {
        "ready" -> "Анализ готов"
        "processing" -> "Анализ выполняется…"
        "idle" -> "Анализ в очереди"
        "failed" -> "Ошибка анализа"
        else -> return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = if (analysisStatus == "failed") ErrorColor else OnSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        if (analysisStatus == "failed" && !analysisError.isNullOrBlank()) {
            Text(text = analysisError, color = OnSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Состояние «в процессе»: спиннер + текст (веб: autorenew + автообновление каждые 3с). */
@Composable
private fun ProcessingState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = 430.dp)
            .padding(horizontal = 24.dp)
            .background(SurfaceCard, RoundedCornerShape(24.dp))
            .padding(24.dp),
    ) {
        CircularProgressIndicator(color = Primary, modifier = Modifier.size(40.dp))
        Text(
            text = "Транскрипция в процессе…",
            color = OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Обновляется автоматически каждые 3 секунды",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Состояние ошибки транскрипции (веб: «Ошибка транскрипции» + error_message). */
@Composable
private fun FailedState(errorMessage: String?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .widthIn(max = 430.dp)
            .padding(horizontal = 24.dp)
            .background(SurfaceCard, RoundedCornerShape(24.dp))
            .padding(24.dp),
    ) {
        Text(
            text = "Ошибка транскрипции",
            color = ErrorColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = "Удалите транскрипт и загрузите аудио заново.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

/** Готовый транскрипт: табы + содержимое + действия (скачать/копировать). */
@Composable
private fun ReadyState(
    sessionId: String,
    transcript: Transcript,
    analysisStatus: String? = null,
    analysisError: String? = null,
) {
    var tab by remember { mutableStateOf(TranscriptTab.SEGMENTS) }

    Column(modifier = Modifier.fillMaxSize()) {
        AnalysisStatusLine(analysisStatus = analysisStatus, analysisError = analysisError)

        // Табы — повторяют веб (По сегментам / Сплошной текст).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabButton("По сегментам", tab == TranscriptTab.SEGMENTS) { tab = TranscriptTab.SEGMENTS }
            TabButton("Сплошной текст", tab == TranscriptTab.FULLTEXT) { tab = TranscriptTab.FULLTEXT }
        }

        // Содержимое таба — занимает оставшееся место, скроллится.
        when (tab) {
            TranscriptTab.SEGMENTS -> SegmentsList(
                segments = transcript.segments,
                modifier = Modifier.weight(1f),
            )
            TranscriptTab.FULLTEXT -> FullTextView(
                rawText = transcript.rawText,
                modifier = Modifier.weight(1f),
            )
        }

        ActionsBar(sessionId = sessionId, rawText = transcript.rawText)
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Primary else SurfaceCard
    val fg = if (selected) OnPrimary else OnSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun SegmentsList(segments: List<TranscriptSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text("Сегменты недоступны.", color = OnSurfaceVariant, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(segments, key = { it.id }) { seg -> SegmentRow(seg) }
    }
}

@Composable
private fun SegmentRow(seg: TranscriptSegment) {
    // Подсветка по avg_logprob (как segClass в вебе): < -1.0 красный, < -0.6 жёлтый.
    val conf = confidence(seg.avgLogprob)
    var rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
    rowModifier = when (conf) {
        Confidence.LOW -> rowModifier
            .background(LowConfBg)
            .border(2.dp, LowConfBorder, RoundedCornerShape(12.dp))
        Confidence.MID -> rowModifier
            .background(MidConfBg)
            .border(2.dp, MidConfBorder, RoundedCornerShape(12.dp))
        Confidence.OK -> rowModifier.background(SurfaceCard)
    }

    Row(modifier = rowModifier.padding(12.dp)) {
        Text(
            text = fmtTime(seg.start),
            color = OnSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text = seg.text, color = OnSurface, fontSize = 14.sp)
    }
}

@Composable
private fun FullTextView(rawText: String?, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text(
                    text = rawText?.takeIf { it.isNotBlank() } ?: "Текст недоступен.",
                    color = OnSurface,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

/**
 * Строка действий (веб: Скачать + Копировать). Re-transcribe/Delete — write-флоу
 * других задач эпика, здесь только просмотр/выгрузка по acceptance D1.
 * «Скачать» = нативный share-intent ACTION_SEND (неизбежно-нативное), «Копировать»
 * = системный clipboard.
 */
@Composable
private fun ActionsBar(sessionId: String, rawText: String?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text = rawText.orEmpty()
    val enabled = text.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedAction(
            label = "⤓  Скачать транскрипт",
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { shareTranscript(context, sessionId, text) },
        )
        OutlinedAction(
            label = "Копировать",
            enabled = enabled,
            onClick = { clipboard.setText(AnnotatedString(text)) },
        )
    }
}

@Composable
private fun OutlinedAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, BorderDim, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = OnSurfaceVariant.copy(alpha = alpha),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Text(text = message, color = ErrorColor, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onRetry) {
            Text("Повторить", color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyState() {
    Text(text = "Транскрипт недоступен", color = OnSurfaceVariant, fontSize = 14.sp)
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// --- A9 (#79): меню действий и подтверждение удаления ---

/**
 * Bottom-sheet меню действий (mobile-first — не dropdown в углу). «Расшифровать
 * заново» показываем только при упавшей расшифровке (issue #79: сценарий
 * восстановления после сбоя); «Проанализировать заново» — только когда транскрипт
 * готов (иначе сервер вернёт 400); «Удалить запись» — деструктивно, отделено цветом
 * и ведёт на отдельное подтверждение, а не удаляет сразу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsMenuSheet(
    transcriptStatus: TranscriptStatus?,
    canRequeueAnalysis: Boolean,
    actionInProgress: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onRequeue: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .padding(horizontal = 8.dp)
                .padding(bottom = 24.dp),
        ) {
            if (transcriptStatus == TranscriptStatus.FAILED) {
                ActionRow(label = "Расшифровать заново", enabled = !actionInProgress, onClick = onReset)
            }
            ActionRow(
                label = "Проанализировать заново",
                enabled = !actionInProgress && canRequeueAnalysis,
                onClick = onRequeue,
            )
            ActionRow(
                label = "Удалить запись",
                enabled = !actionInProgress,
                color = ErrorColor,
                onClick = onDeleteClick,
            )
        }
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean, onClick: () -> Unit, color: Color = OnSurface) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = color.copy(alpha = alpha), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Подтверждение сброса/удаления — оба деструктивны (один и тот же
 * `deleteTranscriptCore` на сервере: строка + аудио-файл удаляются безвозвратно),
 * поэтому оба идут через одно и то же подтверждение, различается только текст.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmActionSheet(
    action: PendingDestructiveAction,
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = when (action) {
        PendingDestructiveAction.RESET -> "Расшифровать заново?"
        PendingDestructiveAction.DELETE -> "Удалить запись?"
    }
    val confirmLabel = when (action) {
        PendingDestructiveAction.RESET -> if (inProgress) "Сбрасываем…" else "Сбросить"
        PendingDestructiveAction.DELETE -> if (inProgress) "Удаляем…" else "Удалить"
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = title, color = OnSurface, fontWeight = FontWeight.Black)
            Text(
                text = "Транскрипт и загруженное аудио будут удалены безвозвратно. Отменить это действие нельзя " +
                    "— для новой расшифровки понадобится заново загрузить аудио на экране сессии.",
                color = OnSurfaceVariant,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    enabled = !inProgress,
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorColor,
                        contentColor = Color.White,
                        disabledContainerColor = ErrorColor.copy(alpha = 0.4f),
                        disabledContentColor = Color.White,
                    ),
                ) {
                    Text(confirmLabel, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !inProgress,
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                ) {
                    Text("Отмена", color = OnSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- Логика подсветки / форматирования (как в вебе TranscriptView) ---

private enum class Confidence { OK, MID, LOW }

/** Порог уверенности по avg_logprob: < -1.0 LOW (красный), < -0.6 MID (жёлтый). */
private fun confidence(avg: Double?): Confidence = when {
    avg == null -> Confidence.OK
    avg < -1.0 -> Confidence.LOW
    avg < -0.6 -> Confidence.MID
    else -> Confidence.OK
}

/** Таймкод сегмента «m:ss» (как fmtTime в вебе). */
private fun fmtTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = (total % 60).toString().padStart(2, '0')
    return "$m:$s"
}

/** Android share-intent — отдаём текст транскрипта в системный шаринг. */
private fun shareTranscript(context: Context, sessionId: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "transcript-$sessionId.txt")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Поделиться транскриптом"))
}

// --- Previews ---

private val previewSegments = listOf(
    TranscriptSegment(0, 0.0, 4.0, "Сегодня работаем над приёмом слева.", -0.2),
    TranscriptSegment(1, 4.0, 9.5, "Контролируй высоту замаха, не торопись.", -0.7),
    TranscriptSegment(2, 9.5, 14.0, "[неразборчиво] ниже к сетке", -1.3),
)

private val previewReady = Transcript(
    status = TranscriptStatus.READY,
    errorMessage = null,
    rawText = "Сегодня работаем над приёмом слева. Контролируй высоту замаха, не торопись.",
    segments = previewSegments,
    durationSeconds = 14,
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun TranscriptReadyPreview() {
    NivelTheme {
        TranscriptContent(
            sessionId = "s1",
            loading = false,
            error = null,
            transcript = previewReady,
            onBack = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun TranscriptProcessingPreview() {
    NivelTheme {
        TranscriptContent(
            sessionId = "s1",
            loading = false,
            error = null,
            transcript = previewReady.copy(status = TranscriptStatus.PROCESSING, segments = emptyList(), rawText = null),
            onBack = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun TranscriptFailedPreview() {
    NivelTheme {
        TranscriptContent(
            sessionId = "s1",
            loading = false,
            error = null,
            transcript = previewReady.copy(
                status = TranscriptStatus.FAILED,
                errorMessage = "Groq API: rate limit exceeded",
                segments = emptyList(),
                rawText = null,
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
