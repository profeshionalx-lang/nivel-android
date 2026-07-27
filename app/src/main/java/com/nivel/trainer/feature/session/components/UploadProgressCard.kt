package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Прогресс заливки записи: бар + «Заливка N %» (C5). Тач-зоны не нужны — статус. */
@Composable
internal fun UploadProgressCard(percent: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⏫", fontSize = 16.sp)
            Text(
                text = "Заливка записи",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$percent %",
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
            )
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 6.dp),
            color = Primary,
            trackColor = SurfaceLow,
        )
        Text(
            text = "Не закрывайте приложение до конца заливки — оно само доведёт запись до сервера.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}
