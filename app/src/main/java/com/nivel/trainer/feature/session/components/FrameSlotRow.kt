package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.feature.frames.FrameSlot
import com.nivel.trainer.service.upload.UploadStage

/**
 * Пучок данных/колбэков [FrameSlotRow], одинаковый для всех карточек экрана сессии —
 * пробрасывается одним параметром через `CardsSection`/`DraftReviewSection`/
 * `DraggableCardList` вместо пяти россыпью (иначе каждый уровень цепочки пришлось бы
 * распаковывать и собирать заново). [stageFor] — текущая стадия заливки кадра
 * (WorkManager, A7 #101) конкретной карточки+слота; колбэки принимают `cardId` и
 * [FrameSlot], т.к. сам ряд слотов рендерится на множестве карточек списка.
 */
internal data class FrameSlotActions(
    val hasLocalVideo: Boolean,
    val stageFor: (cardId: String, slot: FrameSlot) -> UploadStage,
    val onPick: (cardId: String, slot: FrameSlot) -> Unit,
    val onRemove: (cardId: String, slot: FrameSlot) -> Unit,
    val onRetry: (cardId: String, slot: FrameSlot) -> Unit,
    /**
     * Шаг 6 (опционально): «протухший» signed-URL из Room-кэша рендерится Coil'ом как
     * пустой слот (см. `urlBroken` в [FrameSlotBox]) — тап должен перечитать обзор
     * сессии с сервера, чтобы получить свежий `frame_*_url`, а не молча остаться пустым.
     * По умолчанию no-op — вызывающая сторона может не прокидывать (совместимость).
     */
    val onRefresh: () -> Unit = {},
)

/**
 * A8 (#102): ряд слотов кадров «До»/«После» на карточке инсайта — веб-эталон
 * 16:9 + подписи из `InsightFramesRow.tsx` (S7, NIVEL#242), но интерактивный:
 * вход в скрабер (A6, #100), замена, снятие, прогресс заливки (A7, #101).
 *
 * В отличие от веб-компонента, который прячет ряд целиком при 0 кадров, здесь
 * ряд показан ВСЕГДА — решение владельца (issue #102, п.2): пустой слот должен
 * объяснять причину («видео не записывалось» / «момент не определён»), а не
 * молча ничего не показывать. 0/1/2 кадра — свободно, карточка без единого
 * кадра остаётся валидной текстовой карточкой.
 */
@Composable
internal fun FrameSlotRow(
    card: InsightCard,
    actions: FrameSlotActions,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameSlotBox(
            modifier = Modifier.weight(1f),
            label = "До",
            frameUrl = card.frameBeforeUrl,
            momentSeconds = card.momentBeforeSeconds,
            hasLocalVideo = actions.hasLocalVideo,
            stage = actions.stageFor(card.id, FrameSlot.BEFORE),
            onPick = { actions.onPick(card.id, FrameSlot.BEFORE) },
            onRemove = { actions.onRemove(card.id, FrameSlot.BEFORE) },
            onRetry = { actions.onRetry(card.id, FrameSlot.BEFORE) },
            onRefresh = actions.onRefresh,
        )
        FrameSlotBox(
            modifier = Modifier.weight(1f),
            label = "После",
            frameUrl = card.frameAfterUrl,
            momentSeconds = card.momentAfterSeconds,
            hasLocalVideo = actions.hasLocalVideo,
            stage = actions.stageFor(card.id, FrameSlot.AFTER),
            onPick = { actions.onPick(card.id, FrameSlot.AFTER) },
            onRemove = { actions.onRemove(card.id, FrameSlot.AFTER) },
            onRetry = { actions.onRetry(card.id, FrameSlot.AFTER) },
            onRefresh = actions.onRefresh,
        )
    }
}

@Composable
private fun FrameSlotBox(
    label: String,
    frameUrl: String?,
    momentSeconds: Double?,
    hasLocalVideo: Boolean,
    stage: UploadStage,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var urlBroken by remember(frameUrl) { mutableStateOf(false) }
    val effectiveUrl = frameUrl?.takeIf { !urlBroken }

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard),
    ) {
        when {
            // Идёт заливка выбранного кадра (A7) — прогресс поверх, картинка ещё не с сервера.
            stage is UploadStage.Uploading || stage is UploadStage.Queued -> {
                val percent = (stage as? UploadStage.Uploading)?.percent
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, modifier = Modifier.size(28.dp))
                }
                SlotCaption(
                    text = if (percent != null) "Загрузка $percent%" else "В очереди…",
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }

            // Заливка провалилась — тап предлагает повторить (тот же файл, ретрай воркера).
            stage is UploadStage.Failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onRetry),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("⚠", fontSize = 20.sp)
                        Text(
                            text = "Повторить",
                            color = ErrorColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // Подпись «До»/«После» отсутствовала в этой ветке — слот с провалившейся
                // заливкой выглядел «безымянным» среди остальных (fix «после загрузки не
                // сохраняется выбранный кадр», причина C).
                SlotCaption(text = label, modifier = Modifier.align(Alignment.BottomStart))
            }

            // Заливка подтверждена сервером (WorkManager Done), но карточка ещё не
            // перечитана — свежий frame_*_url ещё не пришёл (см. awaitFrameUrl в
            // SessionDetailViewModel). Без этой ветки слот на несколько секунд
            // выглядел как «пусто, можно выбрать» — тренер думал, что кадр потерялся.
            stage is UploadStage.Done && effectiveUrl == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, modifier = Modifier.size(28.dp))
                }
                SlotCaption(text = "Сохраняем…", modifier = Modifier.align(Alignment.BottomStart))
            }

            // Шаг 6: URL был, но не загрузился (протухший signed-URL из Room-кэша) —
            // отличаем от «кадра никогда не было»: тап перечитывает обзор с сервера.
            frameUrl != null && urlBroken -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onRefresh),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⟳", color = Primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Кадр не загрузился — обновить",
                            color = OnSurfaceVariant,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                SlotCaption(text = label, modifier = Modifier.align(Alignment.BottomStart))
            }

            // Кадр уже приложен и подтверждён сервером — миниатюра + заменить/убрать.
            effectiveUrl != null -> {
                AsyncImage(
                    model = effectiveUrl,
                    contentDescription = "Кадр «$label»",
                    contentScale = ContentScale.Crop,
                    onError = { urlBroken = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onPick), // тап по превью = «Заменить»
                )
                SlotCaption(text = label, modifier = Modifier.align(Alignment.BottomStart))
                RemoveButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd))
            }

            // Пусто, но можно выбрать — вход в скрабер (A6).
            hasLocalVideo && momentSeconds != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onPick),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("＋", color = Primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Выбрать кадр",
                            color = OnSurfaceVariant,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                SlotCaption(text = label, modifier = Modifier.align(Alignment.BottomStart))
            }

            // Пусто и недоступно — объясняем причину, не молчим (issue #102, п.2).
            else -> {
                val reason = if (!hasLocalVideo) "Видео не записывалось" else "Момент не определён"
                Box(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = reason,
                        color = OnSurfaceVariant,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                SlotCaption(text = label, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

/** Подпись «До»/«После» в углу слота — тот же приём, что веб `InsightFramesRow`. */
@Composable
private fun SlotCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = OnSurface,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        modifier = modifier
            .padding(4.dp)
            .background(Background.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** «Убрать» — тач-зона 44dp даже поверх маленького 16:9 слота на 390px. */
@Composable
private fun RemoveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(TouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✕",
            color = OnSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Background.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                .padding(6.dp),
        )
    }
}
