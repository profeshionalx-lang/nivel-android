package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ошибка заливки записи с ручным повтором (C5). Кнопка ≥48dp по mobile-first. */
@Composable
internal fun UploadFailedCard(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⚠", color = ErrorColor, fontSize = 16.sp)
            Text(
                text = "Не удалось залить запись",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "Запись сохранена на устройстве. Проверьте соединение и повторите.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
        PrimaryActionButton(text = "Повторить заливку", onClick = onRetry)
    }
}
