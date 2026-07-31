package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.SessionAudioStatus

/**
 * Секция «Карточки» (веб, trainer): статус авто-анализа + кнопка вставки инсайтов
 * + черновики (ревью) + approved (reorder). Порядок один-в-один с вебом
 * (`sessions/[id]/page.tsx`): сначала блок «Черновики (N)», затем «Approved (N)»,
 * «Карточек пока нет» — только если обе группы пусты.
 *
 * A2 (#96): черновики (`trainerStatus == "draft"`) ревьюятся отдельно через
 * [DraftReviewSection] (approve/reject/edit) — как на вебе `DraftCardsList`
 * отделён от `ApprovedCardsReorderable`. D4 (#22): approved-карточки остаются в
 * едином drag-and-drop списке через long-press ([DraggableCardList]); [onMoveCard]/
 * [onCardDragEnd] работают только над approved-подсписком (см. `SessionDetailViewModel`).
 */
@Composable
internal fun CardsSection(
    audio: SessionAudioStatus?,
    cards: List<InsightCard>,
    generating: Boolean,
    generateError: String?,
    onGenerate: () -> Unit,
    onOpenPaste: () -> Unit,
    onOpenLibrary: () -> Unit,
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit,
    onCardDragEnd: () -> Unit,
    onApproveCard: (String) -> Unit,
    onRejectCard: (String) -> Unit,
    onEditCard: (InsightCard) -> Unit,
) {
    val drafts = cards.filter { it.trainerStatus == "draft" }
    val approved = cards.filter { it.trainerStatus != "draft" }

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

        // #78 — применить готовую коллекцию карточек из библиотеки к этой сессии.
        LibraryButton(onClick = onOpenLibrary)

        if (drafts.isEmpty() && approved.isEmpty()) {
            Text(
                text = "Карточек пока нет — вставьте инсайты выше.",
                color = OnSurfaceVariant,
                fontSize = 14.sp,
            )
        } else {
            if (drafts.isNotEmpty()) {
                DraftReviewSection(
                    cards = drafts,
                    onApprove = onApproveCard,
                    onReject = onRejectCard,
                    onEdit = onEditCard,
                )
            }
            if (approved.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Label("Approved (${approved.size})")
                    // D4: drag-and-drop список. Long-press активирует drag; отпускание фиксирует порядок.
                    DraggableCardList(
                        cards = approved,
                        onMoveCard = onMoveCard,
                        onDragEnd = onCardDragEnd,
                    )
                }
            }
        }
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

/**
 * Карточка-кнопка «Добавить из библиотеки» (#78) — открывает шит выбора коллекции
 * карточек для применения к этой сессии. У этого входа нет прямого веб-эталона:
 * на вебе применение коллекции стартует со страницы `/trainer/cards`, а не с
 * экрана сессии — но именно так просит acceptance #78 (сессия уже известна,
 * не нужно заново выбирать ученика/сессию, как в `ApplyCardSheet.tsx`).
 */
@Composable
private fun LibraryButton(onClick: () -> Unit) {
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
        Text("📚", fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Добавить из библиотеки",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Применить готовую коллекцию карточек",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Text("›", color = OnSurfaceVariant, fontSize = 20.sp)
    }
}
