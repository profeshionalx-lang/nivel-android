package com.nivel.trainer.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.SessionAudioStatus
import com.nivel.trainer.domain.SessionDetail
import com.nivel.trainer.domain.SessionOverview
import com.nivel.trainer.service.upload.UploadStage
import com.nivel.trainer.ui.theme.NivelTheme
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Цвета один-в-один из веб-Nivel (src/app/globals.css), как на экранах B4/B5.
private val Background = Color(0xFF0E0E0E)            // --background
private val SurfaceLow = Color(0xFF161616)           // --surface-low
private val SurfaceCard = Color(0xFF1E1E1E)          // --surface-card
private val Primary = Color(0xFFCAFD00)              // --primary (лайм)
private val OnPrimary = Color(0xFF000000)            // text на primary
private val SurfaceElevated = Color(0xFF262626)      // --surface-elevated (поле ввода)
private val BorderDim = Color(0xFF2E2E2E)            // --border-dim
private val Amber = Color(0xFFFBBF24)               // amber-400 (метка черновиков)
private val OnSurface = Color(0xFFF5F5F5)            // --on-surface
private val OnSurfaceVariant = Color(0xFFADAAAA)     // --on-surface-variant
private val ErrorColor = Color(0xFFFF7351)           // --error

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/** Русская локаль для дат (как `ru-RU` в вебе). */
private val RuLocale = Locale("ru", "RU")

/**
 * Экран карточки тренировки (B6, #9) — порт веб-страницы
 * `src/app/sessions/[id]/page.tsx` (trainer-режим): хедер «Сессия N», блок
 * статус/дата, секция аудио/транскрипта (готов/в процессе/ошибка) и секция
 * «Карточки» (черновики + approved, read-only). Упражнения не показываем —
 * как и веб (решение по #9). Состояния загрузки/пусто/ошибка обязательны.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit = {},
    onRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Авто-анализ запускает серверный аналайзер по готовому транскрипту (как pm2 в
    // вебе). Пока статус idle/processing — поллим результат каждые 3с (как setInterval
    // в InsightsAnalysisStatus), чтобы карточки появились без ручного обновления.
    val audio = state.overview?.audio
    LaunchedEffect(audio?.transcriptStatus, audio?.analysisStatus, state.generating) {
        if (!state.generating &&
            audio?.transcriptStatus == "ready" &&
            (audio.analysisStatus == "idle" || audio.analysisStatus == "processing")
        ) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                viewModel.refresh()
            }
        }
    }

    // C5: после успешной заливки строка транскрипта на сервере появляется не мгновенно
    // (запускается STT). Пока заливка идёт/только что доехала, а транскрипта ещё нет —
    // поллим, чтобы экран сам перешёл «заливка → расшифровка» без ручного обновления.
    LaunchedEffect(state.uploadStage, audio == null) {
        val uploadActive = state.uploadStage is UploadStage.Queued ||
            state.uploadStage is UploadStage.Uploading ||
            state.uploadStage is UploadStage.Done
        if (uploadActive && audio == null) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                viewModel.refresh()
            }
        }
    }

    SessionDetailContent(
        loading = state.loading,
        error = state.error,
        overview = state.overview,
        generating = state.generating,
        generateError = state.generateError,
        uploadStage = state.uploadStage,
        completingReview = state.completingReview,
        completeReviewError = state.completeReviewError,
        reorderedCards = state.reorderedCards,
        isOffline = state.isOffline,
        onGenerate = viewModel::generateInsights,
        onOpenPaste = viewModel::openPasteSheet,
        onCompleteReview = viewModel::completeReview,
        onDismissCompleteReviewError = viewModel::dismissCompleteReviewError,
        onMoveCard = viewModel::moveCard,
        onCardDragEnd = viewModel::commitCardReorder,
        onBack = onBack,
        onRecord = onRecord,
        onRetry = viewModel::refresh,
        onRetryUpload = viewModel::retryUpload,
        modifier = modifier,
    )

    val sheet = state.pasteSheet
    if (sheet is PasteSheetState.Open) {
        PasteInsightsSheet(
            state = sheet,
            onMarkdownChange = viewModel::onPasteMarkdownChange,
            onSubmit = viewModel::submitPaste,
            onDismiss = viewModel::closePasteSheet,
        )
    }
}

/** Период поллинга статуса авто-анализа (как `setInterval(3000)` в вебе). */
private const val POLL_INTERVAL_MS = 3_000L

@Composable
private fun SessionDetailContent(
    loading: Boolean,
    error: String?,
    overview: SessionOverview?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    generating: Boolean = false,
    generateError: String? = null,
    uploadStage: UploadStage = UploadStage.None,
    completingReview: Boolean = false,
    completeReviewError: String? = null,
    reorderedCards: List<com.nivel.trainer.domain.InsightCard>? = null,
    isOffline: Boolean = false,
    onGenerate: () -> Unit = {},
    onOpenPaste: () -> Unit = {},
    onRecord: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    onCompleteReview: () -> Unit = {},
    onDismissCompleteReviewError: () -> Unit = {},
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onCardDragEnd: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Header(title = headerTitle(overview?.detail), onBack = onBack)

        // G3 (#32): баннер «оффлайн» — показываем когда данные из кэша (нет сети).
        if (isOffline) {
            com.nivel.trainer.ui.state.OfflineBanner(onRetry = onRetry)
        }

        when {
            loading && overview == null -> CenterBox { CircularProgressIndicator(color = Primary) }

            error != null && overview == null -> CenterBox { ErrorState(error, onRetry) }

            overview != null -> SessionBody(
                overview = overview,
                generating = generating,
                generateError = generateError,
                uploadStage = uploadStage,
                completingReview = completingReview,
                cards = reorderedCards ?: overview.cards,
                onGenerate = onGenerate,
                onOpenPaste = onOpenPaste,
                onRecord = onRecord,
                onRetryUpload = onRetryUpload,
                onCompleteReview = onCompleteReview,
                onMoveCard = onMoveCard,
                onCardDragEnd = onCardDragEnd,
            )

            else -> CenterBox { EmptyState() }
        }
    }

    // Ошибка завершения разбора — показываем поверх контента (D5).
    if (completeReviewError != null) {
        CompleteReviewErrorBanner(
            message = completeReviewError,
            onDismiss = onDismissCompleteReviewError,
        )
    }
}

/** Хедер веба (glass-nav, justify-between): назад «‹» + центрированный «Сессия N». */
@Composable
private fun Header(title: String, onBack: () -> Unit) {
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
            text = title,
            color = Primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        // Балансир под кнопку назад — заголовок остаётся по центру (как `w-10` в вебе).
        Spacer(Modifier.width(TouchTarget))
    }
}

@Composable
private fun SessionBody(
    overview: SessionOverview,
    generating: Boolean,
    generateError: String?,
    uploadStage: UploadStage,
    completingReview: Boolean,
    cards: List<com.nivel.trainer.domain.InsightCard>,
    onGenerate: () -> Unit,
    onOpenPaste: () -> Unit,
    onRecord: () -> Unit,
    onRetryUpload: () -> Unit,
    onCompleteReview: () -> Unit,
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit,
    onCardDragEnd: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { StatusBlock(overview.detail) }
        item {
            AudioSection(
                audio = overview.audio,
                uploadStage = uploadStage,
                onRecord = onRecord,
                onRetryUpload = onRetryUpload,
            )
        }
        item {
            CardsSection(
                audio = overview.audio,
                cards = cards,
                generating = generating,
                generateError = generateError,
                onGenerate = onGenerate,
                onOpenPaste = onOpenPaste,
                onMoveCard = onMoveCard,
                onCardDragEnd = onCardDragEnd,
            )
        }
        // D5 (#23): кнопка «Завершить разбор» — завершает цикл ревью тренера.
        item {
            CompleteReviewButton(
                reviewCompleted = overview.detail.trainerReviewCompleted,
                completing = completingReview,
                onClick = onCompleteReview,
            )
        }
    }
}

/** Блок статус/заголовок/дата (веб: status label + «Сессия N» + дата). */
@Composable
private fun StatusBlock(detail: SessionDetail) {
    Column {
        Text(
            text = if (detail.status == "completed") "Завершена" else "Запланирована",
            color = OnSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = headerTitle(detail),
            color = OnSurface,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
        )
        sessionDate(detail)?.let { date ->
            Spacer(Modifier.size(4.dp))
            Text(text = date, color = OnSurfaceVariant, fontSize = 13.sp)
        }
    }
}

/**
 * Секция аудио/транскрипта (веб, trainer) + стадии обработки (C5, #14).
 *
 * Полный пайплайн «запись → заливка(%) → расшифровка → инсайты»:
 *  - транскрипта ещё нет (`audio == null`) и идёт заливка ([uploadStage]) — показываем
 *    стадию заливки: в очереди / прогресс % / ошибка с повтором (C4/C5);
 *  - транскрипта нет и заливки нет — кнопка «Записать тренировку» (C2, нативный
 *    эквивалент веб-аплоадера);
 *  - транскрипт готов/в процессе/с ошибкой — статусы один-в-один с вебом.
 *
 * Дальше (анализ → карточки) ведёт секция «Карточки» по `analysisStatus` (B6/D2).
 */
@Composable
private fun AudioSection(
    audio: SessionAudioStatus?,
    uploadStage: UploadStage,
    onRecord: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("Аудио тренировки")
        when {
            // Сервер ещё не завёл транскрипт — показываем стадию заливки (C5).
            audio == null -> when (uploadStage) {
                is UploadStage.Queued ->
                    StatusCard(glyph = "⏫", title = "Заливка в очереди…", subtitle = "Начнётся при подключении к сети")
                is UploadStage.Uploading -> UploadProgressCard(uploadStage.percent)
                is UploadStage.Done ->
                    StatusCard(glyph = "⏳", title = "Запускаем расшифровку…", subtitle = "Обычно занимает 15–30 сек")
                is UploadStage.Failed ->
                    UploadFailedCard(onRetry = onRetryUpload)
                UploadStage.None -> RecordButton(onRecord)
            }
            audio.transcriptStatus == "ready" ->
                StatusCard(glyph = "📄", title = "Транскрипт готов", subtitle = null, accent = Primary)
            audio.transcriptStatus == "processing" ->
                StatusCard(glyph = "⏳", title = "Транскрипция…", subtitle = "Обычно занимает 15–30 сек")
            else ->
                StatusCard(
                    glyph = "⚠",
                    title = "Ошибка транскрипции",
                    subtitle = audio.transcriptError,
                    accent = ErrorColor,
                )
        }
    }
}

/** Прогресс заливки записи: бар + «Заливка N %» (C5). Тач-зоны не нужны — статус. */
@Composable
private fun UploadProgressCard(percent: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⏫", fontSize = 16.sp)
            Text(
                text = "Заливка записи",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$percent %",
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
            )
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 6.dp),
            color = Primary,
            trackColor = SurfaceLow,
        )
        Text(
            text = "Не закрывайте приложение до конца заливки — оно само доведёт запись до сервера.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

/** Ошибка заливки записи с ручным повтором (C5). Кнопка ≥48dp по mobile-first. */
@Composable
private fun UploadFailedCard(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⚠", color = ErrorColor, fontSize = 16.sp)
            Text(
                text = "Не удалось залить запись",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "Запись сохранена на устройстве. Проверьте соединение и повторите.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
        PrimaryActionButton(text = "Повторить заливку", onClick = onRetry)
    }
}

/**
 * Карточка-кнопка «Записать тренировку» (C2, #11) — нативная замена веб-аплоадера
 * (`AudioUploader`) на странице сессии. Открывает экран записи; сама запись идёт в
 * foreground-сервисе и привязана к этой сессии. Стиль — как у `PasteInsightButton`.
 */
@Composable
private fun RecordButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🎙", fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Записать тренировку",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Запись в фоне — телефон в карман, экран можно заблокировать",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Text("›", color = OnSurfaceVariant, fontSize = 20.sp)
    }
}

/**
 * Секция «Карточки» (веб, trainer): статус авто-анализа + кнопка вставки инсайтов
 * + черновики + approved. Порядок один-в-один с вебом (`sessions/[id]/page.tsx`).
 *
 * D4 (#22): все карточки объединены в единый список с drag-and-drop через
 * long-press + drag жест. Оптимистичный ребаланс через [onMoveCard];
 * [onCardDragEnd] фиксирует порядок на сервере.
 */
@Composable
private fun CardsSection(
    audio: SessionAudioStatus?,
    cards: List<InsightCard>,
    generating: Boolean,
    generateError: String?,
    onGenerate: () -> Unit,
    onOpenPaste: () -> Unit,
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit,
    onCardDragEnd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Label("Карточки")

        // Статус авто-анализа — только при готовом транскрипте (как InsightsAnalysisStatus).
        if (audio?.transcriptStatus == "ready") {
            AnalysisStatus(
                analysisStatus = audio.analysisStatus,
                analysisError = audio.analysisError,
                generating = generating,
                generateError = generateError,
                onGenerate = onGenerate,
            )
        }

        // Вставить инсайты от Claude — доступно тренеру всегда (как PasteInsightsButton).
        PasteInsightButton(onClick = onOpenPaste)

        if (cards.isEmpty()) {
            Text(
                text = "Карточек пока нет — вставьте инсайты выше.",
                color = OnSurfaceVariant,
                fontSize = 14.sp,
            )
        } else {
            // D4: drag-and-drop список. Все карточки (черновики + approved) в одном
            // reorderable Column. Long-press активирует drag; отпускание фиксирует порядок.
            DraggableCardList(
                cards = cards,
                onMoveCard = onMoveCard,
                onDragEnd = onCardDragEnd,
            )
        }
    }
}

/**
 * D4 (#22): список карточек с drag-and-drop через long-press.
 *
 * Подход: каждая карточка отслеживает свою высоту через `onGloballyPositioned`.
 * При long-press запоминаем `draggedIndex` и накапливаем `dragOffsetY`.
 * На каждый сдвиг пересчитываем целевой индекс через суммарную высоту карточек.
 * При отпускании вызываем [onDragEnd].
 *
 * Работает без внешних зависимостей (только стандартный Compose gesture API).
 */
@Composable
private fun DraggableCardList(
    cards: List<InsightCard>,
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Высоты карточек (заполняются в onGloballyPositioned).
    val cardHeights = remember(cards.size) { mutableListOf<Float>().also { list ->
        repeat(cards.size) { list.add(0f) }
    } }

    // Индекс перетаскиваемой карточки (-1 = не тащим).
    var draggedIndex by remember { mutableStateOf(-1) }
    // Текущее смещение перетаскиваемой карточки по Y.
    var dragOffsetY by remember { mutableStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.forEachIndexed { index, card ->
            val isDragged = index == draggedIndex
            val cardAlpha = if (draggedIndex >= 0 && !isDragged) 0.5f else 1f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isDragged) Modifier.offset { IntOffset(0, dragOffsetY.roundToInt()) }
                        else Modifier
                    )
                    .then(if (isDragged) Modifier.background(SurfaceCard.copy(alpha = 0.95f), RoundedCornerShape(16.dp)) else Modifier)
                    .onGloballyPositioned { coords ->
                        if (index < cardHeights.size) cardHeights[index] = coords.size.height.toFloat()
                    }
                    .pointerInput(cards) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { _ ->
                                draggedIndex = index
                                dragOffsetY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                // Пересчитываем целевой индекс по накопленному смещению.
                                val targetIndex = computeTargetIndex(
                                    fromIndex = draggedIndex,
                                    offsetY = dragOffsetY,
                                    heights = cardHeights,
                                    count = cards.size,
                                )
                                if (targetIndex != draggedIndex) {
                                    onMoveCard(draggedIndex, targetIndex)
                                    // Корректируем смещение: карточки переставились.
                                    val heightDiff = if (targetIndex > draggedIndex) {
                                        -cardHeights.getOrElse(targetIndex) { 0f }
                                    } else {
                                        cardHeights.getOrElse(targetIndex) { 0f }
                                    }
                                    dragOffsetY += heightDiff
                                    draggedIndex = targetIndex
                                }
                            },
                            onDragEnd = {
                                draggedIndex = -1
                                dragOffsetY = 0f
                                onDragEnd()
                            },
                            onDragCancel = {
                                draggedIndex = -1
                                dragOffsetY = 0f
                            },
                        )
                    },
            ) {
                DraggableCardView(card = card, isDragged = isDragged, alpha = cardAlpha)
            }
        }
    }
}

/** Вычисляет целевой индекс по текущему Y-смещению тащимой карточки. */
private fun computeTargetIndex(
    fromIndex: Int,
    offsetY: Float,
    heights: List<Float>,
    count: Int,
): Int {
    var remaining = offsetY
    var target = fromIndex
    if (offsetY > 0) {
        var i = fromIndex + 1
        while (i < count && remaining > 0) {
            val h = heights.getOrElse(i) { 48f } + 12f // gap
            if (remaining > h / 2) target = i
            remaining -= h
            i++
        }
    } else {
        var i = fromIndex - 1
        while (i >= 0 && remaining < 0) {
            val h = heights.getOrElse(i) { 48f } + 12f
            if (remaining < -h / 2) target = i
            remaining += h
            i--
        }
    }
    return target.coerceIn(0, count - 1)
}

/** Карточка инсайта read-only: заголовок + тело + теги. Действия — задачи D2–D4. */
@Composable
private fun CardView(card: InsightCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val heading = card.title?.takeIf { it.isNotBlank() }
            ?: card.frontText?.takeIf { it.isNotBlank() }
        heading?.let {
            Text(
                text = it,
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        card.body?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, color = OnSurfaceVariant, fontSize = 14.sp)
        }
        if (card.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.tags.forEach { tag ->
                    Text(
                        text = tag,
                        color = Primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .background(SurfaceLow, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * D4 (#22): карточка инсайта с визуальной индикацией drag-состояния.
 * В режиме drag (`isDragged=true`) — слегка приподнята (shadow effect через
 * background opacity) и полная непрозрачность. Остальные карточки становятся
 * полупрозрачными ([alpha] < 1.0). Хэндл «⠿» справа намекает на возможность
 * перетащить. Содержимое идентично [CardView].
 */
@Composable
private fun DraggableCardView(card: InsightCard, isDragged: Boolean, alpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .background(
                if (isDragged) SurfaceElevated else SurfaceCard,
                RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val heading = card.title?.takeIf { it.isNotBlank() }
                ?: card.frontText?.takeIf { it.isNotBlank() }
            heading?.let {
                Text(
                    text = it,
                    color = OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            card.body?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, color = OnSurfaceVariant, fontSize = 14.sp)
            }
            if (card.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.tags.forEach { tag ->
                        Text(
                            text = tag,
                            color = Primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            modifier = Modifier
                                .background(SurfaceLow, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            // Статус-лейбл (черновик / approved).
            val statusColor = if (card.trainerStatus == "draft") Amber else OnSurfaceVariant
            val statusLabel = if (card.trainerStatus == "draft") "ЧЕРНОВИК" else "APPROVED"
            Text(
                text = statusLabel,
                color = statusColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
        }
        // Drag-хэндл — намёк для пользователя (long-press активирует drag).
        Text(
            text = "⠿",
            color = OnSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 18.sp,
        )
    }
}

// --- D2 (#20): статус авто-анализа и вставка инсайтов ---

/**
 * Статус авто-анализа транскрипта (порт веб-`InsightsAnalysisStatus`). Рендерится
 * только при готовом транскрипте. idle/processing → спиннер; failed → ошибка с
 * «Повторить анализ»; ready → «Перегенерировать инсайты». Ручная генерация
 * (`generating`) и её ошибка (`generateError`) приоритетнее серверного статуса.
 */
@Composable
private fun AnalysisStatus(
    analysisStatus: String,
    analysisError: String?,
    generating: Boolean,
    generateError: String?,
    onGenerate: () -> Unit,
) {
    when {
        generating -> AnalysisSpinnerCard(
            title = "ИИ анализирует транскрипт…",
            subtitle = "Карточки появятся автоматически",
        )
        generateError != null -> AnalysisFailedCard(generateError, onGenerate)
        analysisStatus == "processing" -> AnalysisSpinnerCard(
            title = "ИИ анализирует транскрипт…",
            subtitle = "Карточки появятся автоматически",
        )
        analysisStatus == "idle" -> AnalysisSpinnerCard(
            title = "Анализ в очереди…",
            subtitle = "Появится в течение 5 минут",
        )
        analysisStatus == "failed" -> AnalysisFailedCard(
            message = analysisError?.takeIf { it.isNotBlank() } ?: "Не удалось проанализировать",
            onRetry = onGenerate,
        )
        else -> RegenerateButton(onGenerate) // ready
    }
}

@Composable
private fun AnalysisSpinnerCard(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = Primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(text = subtitle, color = OnSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AnalysisFailedCard(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⚠", color = ErrorColor, fontSize = 16.sp)
            Text(
                text = "Не удалось проанализировать",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = message,
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        PrimaryActionButton(text = "Повторить анализ", onClick = onRetry)
    }
}

/** Кнопка «Перегенерировать инсайты» (ready) — bordered, центрированная (как в вебе). */
@Composable
private fun RegenerateButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("↻ ", color = OnSurface, fontSize = 14.sp)
        Text(
            text = "Перегенерировать инсайты",
            color = OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Карточка-кнопка «Вставить инсайты» — открывает шит (порт `PasteInsightsButton`). */
@Composable
private fun PasteInsightButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("📋", fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Вставить инсайты",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Скопируйте ответ Claude и вставьте сюда",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Text("›", color = OnSurfaceVariant, fontSize = 20.sp)
    }
}

/** Лаймовая кнопка действия (submit/retry) — как `kinetic-gradient` в вебе. */
@Composable
private fun PrimaryActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = OnPrimary,
            disabledContainerColor = Primary.copy(alpha = 0.4f),
            disabledContentColor = OnPrimary,
        ),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/**
 * Шит «Вставить инсайты от Claude» (порт `PasteInsightsButton`-модалки): копирование
 * промпт-шаблона, поле markdown, раскрывашка «Ожидаемый формат», ошибка парсинга
 * (строка N), кнопки «Создать карточки»/«Закрыть». Mobile-first bottom-sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteInsightsSheet(
    state: PasteSheetState.Open,
    onMarkdownChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    var copied by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    // autoFocus как в вебе — фокус в поле markdown сразу при открытии шита.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Вставить инсайты от Claude",
                    color = OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(TouchTarget)) {
                    Text("✕", color = OnSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Скопировать промпт-шаблон (тренер вставляет его в Claude вручную).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable {
                        clipboard.setText(AnnotatedString(InsightsPrompts.PROMPT))
                        copied = true
                    }
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (copied) "✓ Промпт скопирован" else "Скопировать промпт-шаблон",
                    color = OnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedTextField(
                value = state.markdown,
                onValueChange = onMarkdownChange,
                placeholder = { Text("Вставьте markdown-ответ от Claude…", color = OnSurfaceVariant) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .focusRequester(focusRequester),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderDim,
                    cursorColor = Primary,
                ),
            )

            // Ожидаемый формат — раскрывашка (как <details> в вебе).
            Text(
                text = if (showFormat) "▾ Ожидаемый формат" else "▸ Ожидаемый формат",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFormat = !showFormat }
                    .padding(vertical = 4.dp),
            )
            if (showFormat) {
                Text(
                    text = InsightsPrompts.FORMAT_EXAMPLE,
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceElevated, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    color = ErrorColor,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ErrorColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSubmit,
                    enabled = !state.submitting && state.markdown.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary,
                        disabledContainerColor = Primary.copy(alpha = 0.4f),
                        disabledContentColor = OnPrimary,
                    ),
                ) {
                    Text(
                        text = if (state.submitting) "Создаём…" else "Создать карточки",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.submitting,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Закрыть", color = OnSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Карточка-статус (аудио): глиф + заголовок (+ подзаголовок). */
@Composable
private fun StatusCard(glyph: String, title: String, subtitle: String?, accent: Color = OnSurfaceVariant) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = glyph, color = accent, fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, color = OnSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        color = OnSurfaceVariant,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun EmptyState() {
    Text(text = "Сессия недоступна", color = OnSurfaceVariant, fontSize = 14.sp)
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
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// --- D5 (#23): завершение разбора ---

/**
 * Кнопка «Завершить разбор» (D5, #23). Тренер нажимает после того как все карточки
 * заполнены — сервер атомарно ставит `trainer_review_completed = true` и отправляет
 * Telegram-уведомление ученику. Повторное нажатие безопасно (сервер идемпотентен).
 * После завершения превращается в статус-чип «Разбор завершён».
 */
@Composable
private fun CompleteReviewButton(
    reviewCompleted: Boolean,
    completing: Boolean,
    onClick: () -> Unit,
) {
    if (reviewCompleted) {
        // Статус-чип — разбор уже завершён.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("✓", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(
                text = "Разбор завершён — ученик уведомлён",
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        PrimaryActionButton(
            text = if (completing) "Отправляем…" else "Завершить разбор",
            onClick = onClick,
            enabled = !completing,
        )
    }
}

/**
 * Баннер-ошибка завершения разбора (D5). Показывается поверх контента (как toast).
 * Закрывается крестиком. Не блокирует скролл.
 */
@Composable
private fun CompleteReviewErrorBanner(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ErrorColor.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(TouchTarget),
            ) {
                Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- Хелперы ---

/** Заголовок «Сессия N» (как в вебе); номер может отсутствовать. */
private fun headerTitle(detail: SessionDetail?): String {
    val number = detail?.sessionNumber
    return if (number != null) "Сессия $number" else "Сессия"
}

/**
 * Дата сессии. API детали не отдаёт `created_at`, поэтому берём `completed_at`
 * (если завершена) или `scheduled_at`. Формат «d MMMM yyyy» в ru-локали (UTC).
 */
private fun sessionDate(detail: SessionDetail): String? {
    val raw = detail.completedAt ?: detail.scheduledAt
    return parseUtc(raw)?.format(DATE_FMT)
}

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RuLocale)

/** Парс ISO-времени в UTC (с зоной / без зоны / просто дата). Невалид → null. */
private fun parseUtc(raw: String?): OffsetDateTime? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC) }
        .recoverCatching { java.time.LocalDateTime.parse(value).atOffset(ZoneOffset.UTC) }
        .recoverCatching { java.time.LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC) }
        .getOrNull()
}

// --- Previews ---

private val previewDetail = SessionDetail(
    id = "s1",
    goalId = "g1",
    sessionNumber = 3,
    status = "completed",
    trainerNotes = null,
    scheduledAt = "2026-06-02T14:00:00Z",
    completedAt = "2026-06-02T15:00:00Z",
)

private fun previewCard(id: String, title: String, status: String, tag: String) = InsightCard(
    id = id,
    sessionId = "s1",
    studentId = null,
    trainerId = null,
    title = title,
    body = "Короткий разбор момента и рекомендация для отработки.",
    quote = null,
    frontText = null,
    contextText = null,
    tags = listOf(tag),
    source = null,
    trainerStatus = status,
    studentDecision = null,
    position = 0,
    createdAt = null,
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionReadyPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail,
                audio = SessionAudioStatus("ready", null, "ready", null),
                cards = listOf(
                    previewCard("c1", "Приём слева под давлением", "draft", "техника"),
                    previewCard("c2", "Выход к сетке", "approved", "тактика"),
                ),
            ),
            onBack = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionProcessingEmptyPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail.copy(status = "planned", completedAt = null),
                audio = SessionAudioStatus("processing", null, "idle", null),
                cards = emptyList(),
            ),
            onBack = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionUploadingPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail.copy(status = "completed"),
                audio = null,
                cards = emptyList(),
            ),
            uploadStage = UploadStage.Uploading(percent = 42),
            onBack = {},
            onRetry = {},
        )
    }
}
