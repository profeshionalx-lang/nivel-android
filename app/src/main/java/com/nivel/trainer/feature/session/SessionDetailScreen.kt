package com.nivel.trainer.feature.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
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
import com.nivel.trainer.ui.theme.NivelTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
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

    SessionDetailContent(
        loading = state.loading,
        error = state.error,
        overview = state.overview,
        generating = state.generating,
        generateError = state.generateError,
        cardActionError = state.cardActionError,
        onGenerate = viewModel::generateInsights,
        onOpenPaste = viewModel::openPasteSheet,
        onApproveCard = viewModel::approveCard,
        onRejectCard = viewModel::rejectCard,
        onEditCard = viewModel::openEditSheet,
        onDismissCardError = viewModel::dismissCardActionError,
        onBack = onBack,
        onRetry = viewModel::refresh,
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

    val editSheet = state.editSheet
    if (editSheet is EditSheetState.Open) {
        EditCardSheet(
            state = editSheet,
            onTitleChange = viewModel::onEditTitleChange,
            onBodyChange = viewModel::onEditBodyChange,
            onTagChange = viewModel::onEditTagChange,
            onSideChange = viewModel::onEditSideChange,
            onSave = viewModel::submitEdit,
            onDismiss = viewModel::closeEditSheet,
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
    cardActionError: String? = null,
    onGenerate: () -> Unit = {},
    onOpenPaste: () -> Unit = {},
    onApproveCard: (String) -> Unit = {},
    onRejectCard: (String) -> Unit = {},
    onEditCard: (InsightCard) -> Unit = {},
    onDismissCardError: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Header(title = headerTitle(overview?.detail), onBack = onBack)

        when {
            loading && overview == null -> CenterBox { CircularProgressIndicator(color = Primary) }

            error != null && overview == null -> CenterBox { ErrorState(error, onRetry) }

            overview != null -> SessionBody(
                overview = overview,
                generating = generating,
                generateError = generateError,
                cardActionError = cardActionError,
                onGenerate = onGenerate,
                onOpenPaste = onOpenPaste,
                onApproveCard = onApproveCard,
                onRejectCard = onRejectCard,
                onEditCard = onEditCard,
                onDismissCardError = onDismissCardError,
            )

            else -> CenterBox { EmptyState() }
        }
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
    cardActionError: String?,
    onGenerate: () -> Unit,
    onOpenPaste: () -> Unit,
    onApproveCard: (String) -> Unit,
    onRejectCard: (String) -> Unit,
    onEditCard: (InsightCard) -> Unit,
    onDismissCardError: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { StatusBlock(overview.detail) }
        item { AudioSection(overview.audio) }
        item {
            CardsSection(
                audio = overview.audio,
                cards = overview.cards,
                generating = generating,
                generateError = generateError,
                cardActionError = cardActionError,
                onGenerate = onGenerate,
                onOpenPaste = onOpenPaste,
                onApproveCard = onApproveCard,
                onRejectCard = onRejectCard,
                onEditCard = onEditCard,
                onDismissCardError = onDismissCardError,
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
 * Секция аудио/транскрипта (веб, trainer). `audio == null` (записи нет) → вместо
 * веб-аплоадера показываем нейтральное «Записи пока нет» (запись идёт через Epic 2).
 */
@Composable
private fun AudioSection(audio: SessionAudioStatus?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("Аудио тренировки")
        when {
            audio == null -> StatusCard(glyph = "🎙", title = "Записи пока нет", subtitle = null)
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

/**
 * Секция «Карточки» (веб, trainer): статус авто-анализа + кнопка вставки инсайтов
 * + черновики + approved. Порядок один-в-один с вебом (`sessions/[id]/page.tsx`).
 */
@Composable
private fun CardsSection(
    audio: SessionAudioStatus?,
    cards: List<InsightCard>,
    generating: Boolean,
    generateError: String?,
    cardActionError: String?,
    onGenerate: () -> Unit,
    onOpenPaste: () -> Unit,
    onApproveCard: (String) -> Unit,
    onRejectCard: (String) -> Unit,
    onEditCard: (InsightCard) -> Unit,
    onDismissCardError: () -> Unit,
) {
    val drafts = cards.filter { it.trainerStatus == "draft" }
    val approved = cards.filter { it.trainerStatus == "approved" }

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

        // Ошибка действия approve/reject — баннер, тап скрывает (как router.refresh в вебе).
        cardActionError?.let { err -> CardActionErrorBanner(err, onDismissCardError) }

        // Черновики — тренерское ревью (swipe-стек + кнопки + edit), как DraftCardsList в вебе.
        if (drafts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Черновики (${drafts.size})",
                    color = Amber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                DraftReview(
                    drafts = drafts,
                    onApprove = onApproveCard,
                    onReject = onRejectCard,
                    onEditCard = onEditCard,
                )
            }
        }

        if (approved.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Approved (${approved.size})",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                approved.forEach { CardView(it) }
            }
        }

        if (cards.isEmpty()) {
            Text(
                text = "Карточек пока нет — вставьте инсайты выше.",
                color = OnSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}

/**
 * Карточка инсайта read-only: заголовок + тело + теги. Используется для approved-карточек
 * (черновики ревьюятся через [DraftReview] — D3; reorder approved — задача D4).
 */
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

// --- D3 (#21): ревью draft-карточек (swipe-стек + кнопки + edit) ---

/** Порог свайпа для принятия/отклонения (как `SWIPE_THRESHOLD = 110` в вебе). */
private val SwipeThreshold = 110.dp

/**
 * Тренерское ревью черновиков (порт `DraftCardsList`): tinder-стек — верхняя карта
 * перетаскивается по горизонтали (вправо = принять, влево = отклонить), за ней видна
 * следующая. Под стеком — три кнопки (отклонить / редактировать / принять) и счётчик
 * «Осталось: N». Локальная очередь синкается с приходящими [drafts] (как `useEffect`
 * в вебе) — после действия экран перечитывает карточки и очередь обновляется.
 */
@Composable
private fun DraftReview(
    drafts: List<InsightCard>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onEditCard: (InsightCard) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var queue by remember { mutableStateOf(drafts) }
    var exiting by remember { mutableStateOf(false) }

    // Синк очереди с сервером (как `setQueue(cards)` в useEffect). Сбрасываем сдвиг.
    LaunchedEffect(drafts) {
        queue = drafts
        offsetX.snapTo(0f)
        exiting = false
    }

    val top = queue.firstOrNull()
    val next = queue.getOrNull(1)

    if (top == null) {
        DraftsDoneCard()
        return
    }

    val thresholdPx = with(LocalDensity.current) { SwipeThreshold.toPx() }

    fun commit(approve: Boolean) {
        if (exiting) return
        val card = queue.firstOrNull() ?: return
        exiting = true
        scope.launch {
            offsetX.animateTo(if (approve) 1400f else -1400f, tween(280))
            queue = queue.drop(1)
            offsetX.snapTo(0f)
            exiting = false
            if (approve) onApprove(card.id) else onReject(card.id)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        ) {
            if (next != null) DraftCardFace(card = next, stacked = true)

            val dx = offsetX.value
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = dx
                        rotationZ = dx / 18f
                        alpha = if (exiting) 0f else 1f - min(abs(dx) / 400f, 0.4f)
                    }
                    .pointerInput(top.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    offsetX.value > thresholdPx -> commit(approve = true)
                                    offsetX.value < -thresholdPx -> commit(approve = false)
                                    else -> scope.launch { offsetX.animateTo(0f, spring()) }
                                }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (!exiting) scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                            },
                        )
                    },
            ) {
                DraftCardFace(card = top, dx = dx)
            }
        }

        // Три кнопки: отклонить (✕) / редактировать (✎) / принять (✓) — как в вебе.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewActionButton(
                glyph = "✕",
                contentDescription = "Отклонить",
                size = 64.dp,
                glyphColor = ErrorColor,
                container = Color(0xFFF4F4F4),
                onClick = { commit(approve = false) },
            )
            ReviewActionButton(
                glyph = "✎",
                contentDescription = "Редактировать",
                size = 64.dp,
                glyphColor = Color(0xFF3F3F3F),
                container = Color(0xFFF4F4F4),
                onClick = { onEditCard(top) },
            )
            ReviewActionButton(
                glyph = "✓",
                contentDescription = "Принять",
                size = 80.dp,
                glyphColor = OnPrimary,
                container = Primary,
                onClick = { commit(approve = true) },
            )
        }

        Text(
            text = "Осталось: ${queue.size}",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Пустое состояние ревью — «Все черновики разобраны» (как в вебе после очистки очереди). */
@Composable
private fun DraftsDoneCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("✓", color = Primary, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(
            text = "Все черновики разобраны",
            color = OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Одобренные карточки уже видны ученику.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Лицо draft-карточки в стеке (порт `DraftCardFace`): светлая карта с меткой «AI
 * черновик», темой, заголовком, телом и цитатой. При перетаскивании ([dx]) сверху
 * проступают подсказки «Принять»/«Отклонить». [stacked] — следующая карта позади.
 */
@Composable
private fun DraftCardFace(card: InsightCard, stacked: Boolean = false, dx: Float = 0f) {
    val title = card.title?.takeIf { it.isNotBlank() } ?: card.frontText.orEmpty()
    val body = card.body?.takeIf { it.isNotBlank() } ?: card.contextText.orEmpty()
    val tag = card.tags.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (stacked) Modifier.graphicsLayer {
                    scaleX = 0.94f
                    scaleY = 0.94f
                    translationY = -12f
                    alpha = 0.6f
                } else Modifier,
            )
            .background(Color(0xFFFFFFFF), RoundedCornerShape(24.dp))
            .padding(24.dp),
    ) {
        if (!stacked) {
            // Подсказка «Принять» (слева, зелёная) при свайпе вправо.
            SwipeHint(
                text = "Принять",
                container = Color(0xFF22C55E),
                alignment = Alignment.TopStart,
                alpha = (dx / 80f).coerceIn(0f, 1f),
            )
            // Подсказка «Отклонить» (справа, красная) при свайпе влево.
            SwipeHint(
                text = "Отклонить",
                container = Color(0xFFEF4444),
                alignment = Alignment.TopEnd,
                alpha = (-dx / 80f).coerceIn(0f, 1f),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "AI черновик",
                    color = Color(0xFFB45309),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .background(Color(0x33F59E0B), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                tag?.let {
                    Text(
                        text = it,
                        color = Color(0xFF6B7280),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                color = Color(0xFF111827),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.weight(1f))
            if (body.isNotBlank()) {
                Text(text = body, color = Color(0xFF374151), fontSize = 14.sp)
            }
            card.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "«$quote»",
                    color = Color(0xFF6B7280),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier
                        .background(Color(0x14F59E0B), RoundedCornerShape(8.dp))
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SwipeHint(text: String, container: Color, alignment: Alignment, alpha: Float) {
    if (alpha <= 0f) return
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(alignment)
                .graphicsLayer { this.alpha = alpha }
                .background(container, RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Круглая кнопка действия ревью (отклонить/редактировать/принять). */
@Composable
private fun ReviewActionButton(
    glyph: String,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    glyphColor: Color,
    container: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(container, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = glyphColor, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Black)
    }
}

/** Баннер ошибки действия approve/reject. Тап скрывает (после refresh данные верны). */
@Composable
private fun CardActionErrorBanner(message: String, onDismiss: () -> Unit) {
    Text(
        text = message,
        color = ErrorColor,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            .background(ErrorColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
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

/**
 * Шит правки карточки (D3, порт `EditAiCardModal`): заголовок (≤80) и описание
 * (≤400) со счётчиками, выбор темы и стороны, цитата read-only, ошибка, кнопки
 * «Сохранить»/«Закрыть». «Сохранить» активна только при валидных полях. Mobile-first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditCardSheet(
    state: EditSheetState.Open,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onTagChange: (String) -> Unit,
    onSideChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
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
                    text = "Редактировать карточку",
                    color = OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(TouchTarget)) {
                    Text("✕", color = OnSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            FieldLabel("Заголовок (${state.title.trim().length}/80)")
            OutlinedTextField(
                value = state.title,
                onValueChange = { if (it.length <= 80) onTitleChange(it) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                shape = RoundedCornerShape(12.dp),
                colors = editFieldColors(),
            )

            FieldLabel("Описание (${state.body.trim().length}/400)")
            OutlinedTextField(
                value = state.body,
                onValueChange = { if (it.length <= 400) onBodyChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                shape = RoundedCornerShape(12.dp),
                colors = editFieldColors(),
            )

            FieldLabel("Тема")
            ChipGrid(options = CARD_TAGS, selected = state.tag, onSelect = onTagChange)

            FieldLabel("Сторона")
            ChipGrid(options = CARD_SIDES, selected = state.side, onSelect = onSideChange)

            state.quote?.let { quote ->
                FieldLabel("Цитата (не редактируется)")
                Text(
                    text = "«$quote»",
                    color = OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
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
                    onClick = onSave,
                    enabled = !state.submitting && state.isValid,
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
                        text = if (state.submitting) "Сохраняем…" else "Сохранить",
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

/** Подпись поля в шите правки (как `<label>` uppercase в вебе). */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = OnSurfaceVariant,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun editFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface,
    focusedContainerColor = SurfaceElevated,
    unfocusedContainerColor = SurfaceElevated,
    focusedBorderColor = Primary,
    unfocusedBorderColor = BorderDim,
    cursorColor = Primary,
)

/**
 * Сетка выбираемых чипов в 2 колонки — нативный пикер темы/стороны (веб использует
 * `<select>`/grid-cols-2). Выбранный чип подсвечен лаймом.
 */
@Composable
private fun ChipGrid(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowOptions.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = TouchTarget)
                            .background(
                                if (isSelected) Primary else SurfaceElevated,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option,
                            color = if (isSelected) OnPrimary else OnSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                        )
                    }
                }
                // Доп. ячейка-распорка, если в ряду один элемент (нечётное число опций).
                if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
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
