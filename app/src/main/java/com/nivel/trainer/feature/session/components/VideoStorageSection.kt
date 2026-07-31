package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.feature.session.LocalVideoUiState
import com.nivel.trainer.feature.session.VideoDeleteIntent

// --- A9 (#103): индикатор занятого места видео + подтверждение удаления ---

/** «N ГБ» с одним знаком после запятой (ru-локаль — запятая); для файла < 100 МБ — «<0,1 ГБ». */
internal fun formatVideoSizeGb(bytes: Long): String {
    val gb = bytes.toDouble() / 1_000_000_000.0
    return if (gb < 0.1) "<0,1 ГБ" else String.format(RuLocale, "%.1f ГБ", gb)
}

/**
 * Индикатор занятого места видео тренировки (A9, #103, п.4 issue). Видео не заливается
 * на сервер (эпик NIVEL#235) — лежит только в `filesDir`, автоудаления нет, поэтому
 * тренер должен видеть, сколько места занято, и иметь возможность удалить вручную, не
 * завершая разбор. [isOrphan] — разбор уже завершён (в т.ч. из веба), а видео на
 * телефоне ещё не удалено (п.5 issue) — показываем подсказку явно, не удаляем молча.
 */
@Composable
internal fun VideoStorageSection(
    state: LocalVideoUiState.Present,
    isOrphan: Boolean,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Видео на телефоне")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Видео на телефоне — ${formatVideoSizeGb(state.sizeBytes)}",
                    color = OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (isOrphan) {
                    Text(
                        text = "Разбор уже завершён — это видео можно удалить",
                        color = Primary,
                        fontSize = 12.sp,
                    )
                }
                state.error?.let { err ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = err,
                            color = ErrorColor,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismissError, modifier = Modifier.size(TouchTarget)) {
                            Text("✕", color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            TextButton(
                onClick = onDelete,
                enabled = !state.deleting,
                modifier = Modifier.heightIn(min = TouchTarget),
            ) {
                Text(
                    text = if (state.deleting) "Удаляем…" else "Удалить",
                    color = ErrorColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * Подтверждение удаления видео (bottom-sheet, п.2/4 issue) — общее и для «Завершить
 * разбор» (когда есть локальное видео), и для ручного удаления из индикатора места.
 * Текст предупреждения — один-в-один с формулировкой issue: кадры от видео не зависят,
 * они уже JPEG на сервере.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoDeleteConfirmSheet(
    sizeBytes: Long,
    intent: VideoDeleteIntent,
    onConfirm: () -> Unit,
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
            Text(
                text = "Удалить видео тренировки?",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Видео тренировки (${formatVideoSizeGb(sizeBytes)}) будет удалено с телефона. " +
                    "Кадры, которые ты уже выбрал, останутся.",
                color = OnSurfaceVariant,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary,
                    ),
                ) {
                    Text(
                        text = if (intent == VideoDeleteIntent.COMPLETE_REVIEW) "Завершить и удалить" else "Удалить",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Отмена", color = OnSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
