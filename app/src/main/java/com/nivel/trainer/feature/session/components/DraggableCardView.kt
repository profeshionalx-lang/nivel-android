package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nivel.trainer.domain.InsightCard

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
internal fun DraggableCardView(card: InsightCard, isDragged: Boolean, alpha: Float) {
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
            // A5 (#99): миниатюры приложенных кадров «до»/«после» — минимальный показ,
            // что кадр выбран; полноценные слоты со скрабером — A8.
            if (card.frameBeforeUrl != null || card.frameAfterUrl != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.frameBeforeUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    card.frameAfterUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
        // Drag-хэндл — намёк для пользователя (long-press активирует drag).
        Text(
            text = "⠿",
            color = OnSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 18.sp,
        )
    }
}
