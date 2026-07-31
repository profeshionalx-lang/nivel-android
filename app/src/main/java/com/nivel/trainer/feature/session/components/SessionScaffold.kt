package com.nivel.trainer.feature.session.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.domain.SessionDetail
import com.nivel.trainer.domain.SessionOverview
import com.nivel.trainer.service.upload.UploadStage
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Хедер веба (glass-nav, justify-between): назад «‹» + центрированный «Сессия N». */
@Composable
internal fun Header(title: String, onBack: () -> Unit) {
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
internal fun SessionBody(
    overview: SessionOverview,
    generating: Boolean,
    generateError: String?,
    uploadStage: UploadStage,
    completingReview: Boolean,
    cards: List<com.nivel.trainer.domain.InsightCard>,
    onGenerate: () -> Unit,
    onOpenPaste: () -> Unit,
    onOpenLibrary: () -> Unit,
    onRecord: () -> Unit,
    onRetryUpload: () -> Unit,
    onCompleteReview: () -> Unit,
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit,
    onCardDragEnd: () -> Unit,
    onApproveCard: (String) -> Unit,
    onRejectCard: (String) -> Unit,
    onEditCard: (com.nivel.trainer.domain.InsightCard) -> Unit,
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
                onOpenLibrary = onOpenLibrary,
                onMoveCard = onMoveCard,
                onCardDragEnd = onCardDragEnd,
                onApproveCard = onApproveCard,
                onRejectCard = onRejectCard,
                onEditCard = onEditCard,
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

@Composable
internal fun EmptyState() {
    Text(text = "Сессия недоступна", color = OnSurfaceVariant, fontSize = 14.sp)
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
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
internal fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// --- Хелперы ---

/** Русская локаль для дат (как `ru-RU` в вебе). */
private val RuLocale = Locale("ru", "RU")

/** Заголовок «Сессия N» (как в вебе); номер может отсутствовать. */
internal fun headerTitle(detail: SessionDetail?): String {
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
