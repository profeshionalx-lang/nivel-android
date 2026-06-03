package com.nivel.trainer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nivel.trainer.ui.theme.NivelTheme

/**
 * Стартовый экран-каркас (B1). Полноценный дашборд тренера — в следующих
 * задачах; пока здесь точка входа на экран «Ученики» (B4).
 *
 * @param onOpenStudents переход на экран списка учеников.
 */
@Composable
fun HomeScreen(
    onOpenStudents: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "Nivel",
                style = MaterialTheme.typography.headlineMedium,
            )
            Button(
                onClick = onOpenStudents,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Ученики")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    NivelTheme {
        HomeScreen()
    }
}
