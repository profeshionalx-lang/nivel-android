package com.nivel.trainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = NivelBlue,
    onPrimary = NivelOnPrimary,
    background = NivelBackground,
    onBackground = NivelOnBackground,
    surface = NivelSurface,
)

private val DarkColors = darkColorScheme(
    primary = NivelBlue,
    onPrimary = NivelOnPrimary,
    primaryContainer = NivelBlueDark,
)

/**
 * Корневая тема приложения (Material3, mobile-first). Палитра — производная веб-Nivel;
 * детальный брендинг доводится в G4.
 */
@Composable
fun NivelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NivelTypography,
        content = content,
    )
}
