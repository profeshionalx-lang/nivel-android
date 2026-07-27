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
 * + черновики + approved. Порядок один-в-один с вебом (`sessions/[id]/page.tsx`).
 *
 * D4 (#22): все карточки объединены в единый список с drag-and-drop через
 * long-press + drag жест. Оптимистичный ребаланс через [onMoveCard];
 * [onCardDragEnd] фиксирует порядок на сервере.
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

        // #78 — применить готовую коллекцию карточек из библиотеки к этой сессии.
        LibraryButton(onClick = onOpenLibrary)

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
