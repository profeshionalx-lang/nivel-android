package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nivel.trainer.domain.InsightCard
import kotlin.math.roundToInt

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
internal fun DraggableCardList(
    cards: List<InsightCard>,
    frameActions: FrameSlotActions,
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
                DraggableCardView(card = card, isDragged = isDragged, alpha = cardAlpha, frameActions = frameActions)
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
