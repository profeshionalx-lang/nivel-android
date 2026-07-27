package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.feature.session.InsightsPrompts
import com.nivel.trainer.feature.session.PasteSheetState
import kotlinx.coroutines.delay

/**
 * Шит «Вставить инсайты от Claude» (порт `PasteInsightsButton`-модалки): копирование
 * промпт-шаблона, поле markdown, раскрывашка «Ожидаемый формат», ошибка парсинга
 * (строка N), кнопки «Создать карточки»/«Закрыть». Mobile-first bottom-sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteInsightsSheet(
    state: PasteSheetState.Open,
    onMarkdownChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    var copied by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    // autoFocus как в вебе — фокус в поле markdown сразу при открытии шита.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

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
                    text = "Вставить инсайты от Claude",
                    color = OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(TouchTarget)) {
                    Text("✕", color = OnSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Скопировать промпт-шаблон (тренер вставляет его в Claude вручную).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable {
                        clipboard.setText(AnnotatedString(InsightsPrompts.PROMPT))
                        copied = true
                    }
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (copied) "✓ Промпт скопирован" else "Скопировать промпт-шаблон",
                    color = OnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedTextField(
                value = state.markdown,
                onValueChange = onMarkdownChange,
                placeholder = { Text("Вставьте markdown-ответ от Claude…", color = OnSurfaceVariant) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .focusRequester(focusRequester),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderDim,
                    cursorColor = Primary,
                ),
            )

            // Ожидаемый формат — раскрывашка (как <details> в вебе).
            Text(
                text = if (showFormat) "▾ Ожидаемый формат" else "▸ Ожидаемый формат",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFormat = !showFormat }
                    .padding(vertical = 4.dp),
            )
            if (showFormat) {
                Text(
                    text = InsightsPrompts.FORMAT_EXAMPLE,
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceElevated, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
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
                    onClick = onSubmit,
                    enabled = !state.submitting && state.markdown.isNotBlank(),
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
                        text = if (state.submitting) "Создаём…" else "Создать карточки",
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
