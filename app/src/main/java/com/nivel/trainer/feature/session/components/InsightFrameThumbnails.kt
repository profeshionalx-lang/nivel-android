package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nivel.trainer.domain.InsightCard

/**
 * A5 (#99): минимальный показ приложенных кадров «до»/«после» — маленькие
 * миниатюры-бейджи, подтверждающие что кадр уже выбран. Полноценные слоты
 * 16:9 с подписями «До»/«После» (веб `InsightFramesRow`) — задача A8, здесь
 * умышленно не воспроизводятся.
 *
 * Подключается только к draft-карточке ([DraftReviewSection]/`DraftCardFace`) —
 * веб-эталон `AiInsightCard.tsx` показывает кадры прямо в компактной карточке
 * черновика. У approved-карточек (`ApprovedInsightCard.tsx`) кадры на вебе
 * видны только в развёрнутом виде (по тапу открывается bottom-sheet), а не в
 * свёрнутом ряду списка — у Android-версии approved-списка ([DraggableCardView])
 * пока нет такого развёрнутого состояния, поэтому здесь его не показываем
 * (не изобретаем UI сверх веб-эталона, AGENTS.md §1).
 *
 * Ничего не рендерим, если у карточки нет ни одного URL кадра — экран
 * выглядит как раньше (acceptance #99). Подписанный URL кадра (`session-frames`,
 * TTL ~24ч) может протухнуть — тогда Storage отдаёт 400/403, Coil сообщает об
 * этом через `onError`, и мы тихо гасим только свою миниатюру, не ломая
 * карточку целиком (тот же приём, что в вебе `InsightFramesRow`/`onError`).
 */
@Composable
internal fun InsightFrameThumbnails(card: InsightCard) {
    var beforeBroken by remember(card.id) { mutableStateOf(false) }
    var afterBroken by remember(card.id) { mutableStateOf(false) }

    val beforeUrl = card.frameBeforeUrl?.takeIf { !beforeBroken }
    val afterUrl = card.frameAfterUrl?.takeIf { !afterBroken }
    if (beforeUrl == null && afterUrl == null) return

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        beforeUrl?.let { FrameThumbnail(url = it, onBroken = { beforeBroken = true }) }
        afterUrl?.let { FrameThumbnail(url = it, onBroken = { afterBroken = true }) }
    }
}

private val ThumbnailSize = 40.dp

@Composable
private fun FrameThumbnail(url: String, onBroken: () -> Unit) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { onBroken() },
        modifier = Modifier
            .size(ThumbnailSize)
            .clip(RoundedCornerShape(8.dp)),
    )
}
