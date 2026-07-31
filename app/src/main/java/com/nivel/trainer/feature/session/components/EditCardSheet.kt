package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.feature.session.CARD_BODY_MAX_LENGTH
import com.nivel.trainer.feature.session.CARD_SIDES
import com.nivel.trainer.feature.session.CARD_TAGS
import com.nivel.trainer.feature.session.CARD_TITLE_MAX_LENGTH
import com.nivel.trainer.feature.session.EditSheetState

/**
 * Bottom-sheet правки draft/approved-карточки (A2, #96) — порт `EditAiCardModal`.
 * Лимиты полей один-в-один с сервером (`validateAiInsightCardPatch`): заголовок
 * ≤80, описание ≤400, тема/сторона — из фиксированных наборов. Цитата read-only.
 * Не закрывается тапом вне шита во время отправки (см. [SessionDetailViewModel.closeEditSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditCardSheet(
    state: EditSheetState.Open,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onTagChange: (String) -> Unit,
    onSideChange: (String) -> Unit,
    onSave: () -> Unit,
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
                    text = "Редактировать карточку",
                    color = OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(TouchTarget)) {
                    Text("✕", color = OnSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Заголовок (${state.title.trim().length}/$CARD_TITLE_MAX_LENGTH)",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = editFieldColors(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Описание (${state.body.trim().length}/$CARD_BODY_MAX_LENGTH)",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                OutlinedTextField(
                    value = state.body,
                    onValueChange = onBodyChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = editFieldColors(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Тема",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CARD_TAGS.chunked(2).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { tag ->
                                SelectableChip(
                                    label = tag,
                                    selected = tag == state.tag,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onTagChange(tag) },
                                )
                            }
                            if (row.size < 2) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Сторона",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CARD_SIDES.forEach { side ->
                        SelectableChip(
                            label = side,
                            selected = side == state.side,
                            modifier = Modifier.weight(1f),
                            onClick = { onSideChange(side) },
                        )
                    }
                }
            }

            state.quote?.let { quote ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Цитата (не редактируется)",
                        color = OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        text = "«$quote»",
                        color = OnSurfaceVariant,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceElevated, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    )
                }
            }

            state.error?.let { err ->
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    enabled = !state.submitting && state.isValid,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary,
                        disabledContainerColor = Primary.copy(alpha = 0.4f),
                        disabledContentColor = OnPrimary,
                    ),
                ) {
                    Text(
                        text = if (state.submitting) "Сохраняем…" else "Сохранить",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.submitting,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Закрыть", color = OnSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun editFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface,
    focusedContainerColor = SurfaceElevated,
    unfocusedContainerColor = SurfaceElevated,
    focusedBorderColor = Primary,
    unfocusedBorderColor = BorderDim,
    cursorColor = Primary,
)

/** Чип выбора темы/стороны — как радио-группа в вебе (`<select>`/кнопки side). */
@Composable
private fun SelectableChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .background(
                if (selected) Primary else SurfaceElevated,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) OnPrimary else OnSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
