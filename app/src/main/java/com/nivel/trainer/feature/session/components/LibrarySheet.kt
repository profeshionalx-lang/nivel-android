package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.feature.session.LibrarySheetState

/**
 * Шит «Добавить из библиотеки» (#78): список коллекций тренера → превью карточек
 * выбранной → «Применить». Mobile-first bottom-sheet (не центрированный диалог).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySheet(
    state: LibrarySheetState,
    onSelectCollection: (com.nivel.trainer.domain.CardCollection) -> Unit,
    onBackToList: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                    text = when (state) {
                        is LibrarySheetState.PreviewCollection -> state.collection.name
                        else -> "Добавить из библиотеки"
                    },
                    color = OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(TouchTarget)) {
                    Text("✕", color = OnSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            when (state) {
                is LibrarySheetState.Closed -> Unit

                is LibrarySheetState.ListCollections -> when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Primary) }

                    state.error != null -> Text(
                        text = state.error,
                        color = ErrorColor,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ErrorColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    )

                    state.collections.isEmpty() -> Text(
                        text = "Библиотека коллекций пуста. Создайте коллекцию в вебе на странице «Карточки».",
                        color = OnSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceElevated, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    )

                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.collections.forEach { collection ->
                            CollectionRow(collection = collection, onClick = { onSelectCollection(collection) })
                        }
                    }
                }

                is LibrarySheetState.PreviewCollection -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.applied) {
                        Text(
                            text = "✓ Коллекция применена — карточки добавлены в сессию.",
                            color = Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                        ) {
                            Text("Готово", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            text = "← Другая коллекция",
                            color = Secondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onBackToList),
                        )

                        when {
                            state.loading -> Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(color = Primary) }

                            state.error != null -> Text(
                                text = state.error,
                                color = ErrorColor,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ErrorColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                            )

                            state.cards.isEmpty() -> Text(
                                text = "В коллекции нет карточек.",
                                color = OnSurfaceVariant,
                                fontSize = 13.sp,
                            )

                            else -> LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(end = 4.dp),
                            ) {
                                items(state.cards, key = { it.id }) { card -> CollectionCardPreviewChip(card) }
                            }
                        }

                        state.applyError?.let { err ->
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

                        Button(
                            onClick = onApply,
                            enabled = !state.applying && !state.loading && state.cards.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = OnPrimary,
                                disabledContainerColor = Primary.copy(alpha = 0.4f),
                                disabledContentColor = OnPrimary,
                            ),
                        ) {
                            Text(
                                text = if (state.applying) "Применяем…" else "Применить к этой сессии",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(collection: com.nivel.trainer.domain.CardCollection, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget + 8.dp)
            .clickable(onClick = onClick)
            .background(SurfaceElevated, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(collection.name, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "${collection.cardsCount} " + pluralCards(collection.cardsCount),
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Text("›", color = OnSurfaceVariant, fontSize = 20.sp)
    }
}

@Composable
private fun CollectionCardPreviewChip(card: com.nivel.trainer.domain.CollectionCardPreview) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .background(SurfaceElevated, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = card.title?.takeIf { it.isNotBlank() } ?: "Без заголовка",
            color = OnSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        card.body?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = OnSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Русское склонение «карточка/карточки/карточек» по числу (веб делает то же для counts). */
private fun pluralCards(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "карточек"
        mod10 == 1 -> "карточка"
        mod10 in 2..4 -> "карточки"
        else -> "карточек"
    }
}
