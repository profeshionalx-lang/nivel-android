package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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

// --- D5 (#23): завершение разбора ---

/**
 * Кнопка «Завершить разбор» (D5, #23). Тренер нажимает после того как все карточки
 * заполнены — сервер атомарно ставит `trainer_review_completed = true` и отправляет
 * Telegram-уведомление ученику. Повторное нажатие безопасно (сервер идемпотентен).
 * После завершения превращается в статус-чип «Разбор завершён».
 */
@Composable
internal fun CompleteReviewButton(
    reviewCompleted: Boolean,
    completing: Boolean,
    onClick: () -> Unit,
) {
    if (reviewCompleted) {
        // Статус-чип — разбор уже завершён.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("✓", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(
                text = "Разбор завершён — ученик уведомлён",
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        PrimaryActionButton(
            text = if (completing) "Отправляем…" else "Завершить разбор",
            onClick = onClick,
            enabled = !completing,
        )
    }
}

/**
 * Баннер-ошибка завершения разбора (D5). Показывается поверх контента (как toast).
 * Закрывается крестиком. Не блокирует скролл.
 *
 * Разметка вынесена в общий [com.nivel.trainer.ui.state.ErrorBanner] (fix «после
 * загрузки не сохраняется выбранный кадр») — этот composable оставлен как тонкая
 * делегация, чтобы не трогать вызывающие места (`SessionDetailScreen.kt`).
 */
@Composable
internal fun CompleteReviewErrorBanner(message: String, onDismiss: () -> Unit) {
    com.nivel.trainer.ui.state.ErrorBanner(message = message, onDismiss = onDismiss)
}
