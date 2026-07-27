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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- D2 (#20): статус авто-анализа и вставка инсайтов ---

/**
 * Статус авто-анализа транскрипта (порт веб-`InsightsAnalysisStatus`). Рендерится
 * только при готовом транскрипте. idle/processing → спиннер; failed → ошибка с
 * «Повторить анализ»; ready → «Перегенерировать инсайты». Ручная генерация
 * (`generating`) и её ошибка (`generateError`) приоритетнее серверного статуса.
 */
@Composable
internal fun AnalysisStatus(
    analysisStatus: String,
    analysisError: String?,
    generating: Boolean,
    generateError: String?,
    onGenerate: () -> Unit,
) {
    when {
        generating -> AnalysisSpinnerCard(
            title = "ИИ анализирует транскрипт…",
            subtitle = "Карточки появятся автоматически",
        )
        generateError != null -> AnalysisFailedCard(generateError, onGenerate)
        analysisStatus == "processing" -> AnalysisSpinnerCard(
            title = "ИИ анализирует транскрипт…",
            subtitle = "Карточки появятся автоматически",
        )
        analysisStatus == "idle" -> AnalysisSpinnerCard(
            title = "Анализ в очереди…",
            subtitle = "Появится в течение 5 минут",
        )
        analysisStatus == "failed" -> AnalysisFailedCard(
            message = analysisError?.takeIf { it.isNotBlank() } ?: "Не удалось проанализировать",
            onRetry = onGenerate,
        )
        else -> RegenerateButton(onGenerate) // ready
    }
}

@Composable
private fun AnalysisSpinnerCard(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = Primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(text = subtitle, color = OnSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AnalysisFailedCard(message: String, onRetry: () -> Unit) {
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
                text = "Не удалось проанализировать",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = message,
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        PrimaryActionButton(text = "Повторить анализ", onClick = onRetry)
    }
}

/** Кнопка «Перегенерировать инсайты» (ready) — bordered, центрированная (как в вебе). */
@Composable
private fun RegenerateButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("↻ ", color = OnSurface, fontSize = 14.sp)
        Text(
            text = "Перегенерировать инсайты",
            color = OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
