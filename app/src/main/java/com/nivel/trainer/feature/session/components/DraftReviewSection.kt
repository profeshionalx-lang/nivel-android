package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.domain.InsightCard

/** Порог свайпа для approve/reject — как `SWIPE_THRESHOLD = 110` (px) в вебе (`DraftCardsList`). */
private val SwipeThreshold = 96.dp

/**
 * A2 (#96): секция ревью draft-карточек — порт `DraftCardsList` (веб): верхняя
 * карточка стопки свайпается вправо (approve) / влево (reject); под стопкой —
 * три кнопки-дублёра жеста (Отклонить/Править/Принять — правки жеста на вебе
 * тоже нет, там тоже отдельная кнопка `EditAiCardModal`). Ничего не показываем,
 * если черновиков нет — секция полностью скрыта (как на вебе `draftCards.length > 0`).
 */
@Composable
internal fun DraftReviewSection(
    cards: List<InsightCard>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onEdit: (InsightCard) -> Unit,
) {
    val top = cards.firstOrNull() ?: return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Черновики (${cards.size})",
            color = Amber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )

        DraftCardStack(top = top, next = cards.getOrNull(1), onApprove = onApprove, onReject = onReject)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewActionButton(
                label = "✗ Отклонить",
                modifier = Modifier.weight(1f),
                onClick = { onReject(top.id) },
            )
            ReviewActionButton(
                label = "✎ Править",
                modifier = Modifier.weight(1f),
                onClick = { onEdit(top) },
            )
            ReviewActionButton(
                label = "✓ Принять",
                modifier = Modifier.weight(1f),
                accent = true,
                onClick = { onApprove(top.id) },
            )
        }

        Text(
            text = "Осталось: ${cards.size}",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Стопка из верхней (перетаскиваемой) и следующей (уменьшенной, "выглядывающей")
 * карточки — как `tinker-stack` в вебе. `remember(top.id)` сбрасывает смещение,
 * когда карточка сверху меняется (approve/reject убрали текущую).
 */
@Composable
private fun DraftCardStack(
    top: InsightCard,
    next: InsightCard?,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    var dragX by remember(top.id) { mutableStateOf(0f) }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        next?.let { peek ->
            DraftCardFace(
                card = peek,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = 0.94f
                        scaleY = 0.94f
                        alpha = 0.6f
                    },
            )
        }
        DraftCardFace(
            card = top,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = dragX
                    rotationZ = dragX / 24f
                }
                .pointerInput(top.id) {
                    detectDragGestures(
                        onDragEnd = {
                            when {
                                dragX > SwipeThreshold.toPx() -> onApprove(top.id)
                                dragX < -SwipeThreshold.toPx() -> onReject(top.id)
                                else -> dragX = 0f
                            }
                        },
                        onDragCancel = { dragX = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragX += amount.x
                    }
                },
        )
    }
}

/** Лицо черновик-карточки: бейдж «AI черновик» + тема, заголовок, тело, цитата. */
@Composable
private fun DraftCardFace(card: InsightCard, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SurfaceCard, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI ЧЕРНОВИК",
                color = Amber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .background(Amber.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            card.tags.getOrNull(0)?.let { tag ->
                Text(text = tag, color = OnSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        val heading = card.title?.takeIf { it.isNotBlank() } ?: card.frontText?.takeIf { it.isNotBlank() }
        heading?.let {
            Text(text = it, color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        (card.body?.takeIf { it.isNotBlank() } ?: card.contextText?.takeIf { it.isNotBlank() })?.let {
            Text(text = it, color = OnSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
        }
        card.quote?.takeIf { it.isNotBlank() }?.let {
            Text(text = "«$it»", color = OnSurfaceVariant, fontSize = 12.sp, fontStyle = FontStyle.Italic)
        }
        // Порядок как в вебе (`AiInsightCard`): заголовок → тело → цитата → кадры.
        InsightFrameThumbnails(card)
    }
}

/** Кнопка ревью (Отклонить/Править/Принять) — тач-зона ≥44dp (mobile-first). */
@Composable
private fun ReviewActionButton(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (accent) Primary else SurfaceCard
    val foreground = if (accent) OnPrimary else OnSurface
    Row(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .background(background, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
