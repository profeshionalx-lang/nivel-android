package com.nivel.trainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Индикатор оффлайна (G3, #32): показывается на экранах чтения, когда данные взяты
 * из кэша из-за отсутствия сети. Цвета — из палитры веб-Nivel (globals.css), как на
 * остальных экранах. Неинтрузивная полоса под хедером, не подменяет контент.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF262626)) // --surface-elevated
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("⚠", color = Color(0xFFFBBF24), fontSize = 13.sp) // amber-400
        Text(
            text = "Нет сети — показаны сохранённые данные",
            color = Color(0xFFADAAAA), // --on-surface-variant
            fontSize = 12.sp,
        )
    }
}
