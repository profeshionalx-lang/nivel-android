package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Label(text: String) {
    Text(
        text = text,
        color = OnSurfaceVariant,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
    )
}

/** Лаймовая кнопка действия (submit/retry) — как `kinetic-gradient` в вебе. */
@Composable
internal fun PrimaryActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = OnPrimary,
            disabledContainerColor = Primary.copy(alpha = 0.4f),
            disabledContentColor = OnPrimary,
        ),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
