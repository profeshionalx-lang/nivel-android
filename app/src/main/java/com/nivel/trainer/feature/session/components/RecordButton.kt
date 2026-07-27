package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Карточка-кнопка «Записать тренировку» (C2, #11) — нативная замена веб-аплоадера
 * (`AudioUploader`) на странице сессии. Открывает экран записи; сама запись идёт в
 * foreground-сервисе и привязана к этой сессии. Стиль — как у `PasteInsightButton`.
 */
@Composable
internal fun RecordButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🎙", fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Записать тренировку",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Запись в фоне — телефон в карман, экран можно заблокировать",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Text("›", color = OnSurfaceVariant, fontSize = 20.sp)
    }
}
