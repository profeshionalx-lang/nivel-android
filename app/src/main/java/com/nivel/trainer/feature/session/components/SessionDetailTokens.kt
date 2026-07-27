package com.nivel.trainer.feature.session.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Цвета один-в-один из веб-Nivel (src/app/globals.css), как на экранах B4/B5.
internal val Background = Color(0xFF0E0E0E)            // --background
internal val SurfaceLow = Color(0xFF161616)           // --surface-low
internal val SurfaceCard = Color(0xFF1E1E1E)          // --surface-card
internal val Primary = Color(0xFFCAFD00)              // --primary (лайм)
internal val Secondary = Color(0xFF7CC6FE)            // --secondary (голубой, ссылки)
internal val OnPrimary = Color(0xFF000000)            // text на primary
internal val SurfaceElevated = Color(0xFF262626)      // --surface-elevated (поле ввода)
internal val BorderDim = Color(0xFF2E2E2E)            // --border-dim
internal val Amber = Color(0xFFFBBF24)               // amber-400 (метка черновиков)
internal val OnSurface = Color(0xFFF5F5F5)            // --on-surface
internal val OnSurfaceVariant = Color(0xFFADAAAA)     // --on-surface-variant
internal val ErrorColor = Color(0xFFFF7351)           // --error

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
internal val TouchTarget = 48.dp
