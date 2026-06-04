package com.nivel.trainer.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Цвета один-в-один из веб-Nivel (src/app/globals.css), как на экранах B4/B5.
private val Background = Color(0xFF0E0E0E)            // --background
private val SurfaceLow = Color(0xFF161616)           // --surface-low
private val SurfaceCard = Color(0xFF1E1E1E)          // --surface-card
private val Primary = Color(0xFFCAFD00)              // --primary (лайм)
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

    SessionDetailContent(
        loading = state.loading,
        error = state.error,
        overview = state.overview,
        onBack = onBack,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )
}

@Composable
private fun SessionDetailContent(
    loading: Boolean,
    error: String?,
    overview: SessionOverview?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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

            overview != null -> SessionBody(overview)

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
private fun SessionBody(overview: SessionOverview) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { StatusBlock(overview.detail) }
        item { AudioSection(overview.audio) }
        item { CardsSection(overview.audio, overview.cards) }
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

/** Секция «Карточки» (веб, trainer): статус анализа + черновики + approved. */
@Composable
private fun CardsSection(audio: SessionAudioStatus?, cards: List<InsightCard>) {
    val drafts = cards.filter { it.trainerStatus == "draft" }
    val approved = cards.filter { it.trainerStatus == "approved" }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Label("Карточки")

        analysisLine(audio)?.let { (text, color) ->
            Text(text = text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        if (drafts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Черновики (${drafts.size})",
                    color = Amber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                drafts.forEach { CardView(it) }
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
                text = "Карточек пока нет.",
                color = OnSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
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
 * Строка анализа карточек (веб: InsightsAnalysisStatus). Показываем только когда
 * есть что сказать: анализ идёт или упал. `null` → строку не рендерим.
 */
private fun analysisLine(audio: SessionAudioStatus?): Pair<String, Color>? = when (audio?.analysisStatus) {
    "processing" -> "Анализ карточек…" to OnSurfaceVariant
    "failed" -> (audio.analysisError?.takeIf { it.isNotBlank() }?.let { "Ошибка анализа: $it" }
        ?: "Ошибка анализа") to ErrorColor
    else -> null
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
