package com.nivel.trainer.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.BuildConfig
import com.nivel.trainer.ui.theme.NivelTheme

// Цвета экрана входа взяты один-в-один из веб-Nivel (src/app/globals.css, src/app/login/page.tsx):
// тёмный фон, лаймовый primary, текст подписи приглушённый. Глобальная тема приложения
// (Material) доводится в G4; здесь фиксируем точные значения логина, чтобы экран совпадал с вебом.
private val LoginBackground = Color(0xFF0E0E0E)        // --background
private val LoginPrimary = Color(0xFFCAFD00)           // --primary (кнопка Гречки)
private val LoginOnPrimary = Color(0xFF000000)         // text-black на кнопке Гречки в вебе
private val LoginOnSurfaceVariant = Color(0xFFADAAAA)  // --on-surface-variant (подпись/дисклеймер)
private val LoginError = Color(0xFFFF7351)             // --error
private val GoogleButtonBg = Color(0xFFFFFFFF)         // bg-white
private val GoogleButtonText = Color(0xFF111827)       // text-gray-900

/**
 * Экран входа — порт веб-страницы `/login` (`src/app/login/page.tsx`) один-в-один:
 * логотип «Nivel», подпись, кнопка «Войти через Гречку», кнопка «Войти через Google»,
 * строка ошибки, дисклеймер. Неизбежно-нативное: вход Гречки — через Chrome Custom
 * Tabs ([launchGrechkaAuth]) вместо WebView (Google блокирует OAuth в WebView:
 * `disallowed_useragent` / Error 403); Google — системный Sign-In (fallback).
 *
 * @param onLoggedIn вызывается после успешного сохранения bearer-токена.
 * @param claimToken опциональный токен приглашения (claim-флоу) из deep link.
 * @param authCallbackUri deep link `nivel://auth/callback?...` из Custom Tabs,
 *   проброшенный из MainActivity; обрабатывается один раз.
 * @param onAuthCallbackConsumed вызывается после обработки [authCallbackUri],
 *   чтобы MainActivity сбросил его и не доставил повторно при recomposition.
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    claimToken: String? = null,
    authCallbackUri: android.net.Uri? = null,
    onAuthCallbackConsumed: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(claimToken) { viewModel.setClaimToken(claimToken) }
    LaunchedEffect(state.loggedIn) { if (state.loggedIn) onLoggedIn() }

    // Одноразовый запрос на открытие Custom Tabs.
    LaunchedEffect(state.launchGrechkaTab) {
        val tabState = state.launchGrechkaTab ?: return@LaunchedEffect
        val opened = launchGrechkaAuth(context, tabState)
        if (opened) viewModel.onGrechkaTabLaunched() else viewModel.onGrechkaTabFailed()
    }

    // Доставка deep link из MainActivity в ViewModel.
    LaunchedEffect(authCallbackUri) {
        val uri = authCallbackUri ?: return@LaunchedEffect
        viewModel.handleAuthCallback(uri)
        onAuthCallbackConsumed()
    }

    when (state.step) {
        AuthStep.GRECHKA_BROWSER -> {
            // Ждём возврат из браузера; back — отмена входа.
            BackHandler { viewModel.cancelGrechkaLogin() }
            GrechkaWaiting(modifier = modifier)
        }

        AuthStep.OPTIONS -> LoginOptions(
            error = state.error,
            loading = state.loading,
            onGrechkaClick = viewModel::startGrechkaLogin,
            onGoogleClick = { /* TODO(#5): нативный Google Sign-In, см. ниже */ },
            modifier = modifier,
        )
    }
}

/** Экран ожидания возврата из Chrome Custom Tabs. */
@Composable
private fun GrechkaWaiting(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LoginBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            CircularProgressIndicator(color = LoginPrimary)
            Text(
                text = "Ждём входа через Гречку…",
                color = LoginOnSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoginOptions(
    error: String?,
    loading: Boolean,
    onGrechkaClick: () -> Unit,
    onGoogleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LoginBackground)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 384.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            // Логотип + подпись (как в вебе: h1 «Nivel» + текст подписи)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Nivel",
                    color = LoginPrimary, // веб: kinetic-text (лаймовый градиент); берём базовый лайм
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                )
                Text(
                    text = "Падел-платформа для тренеров и игроков",
                    color = LoginOnSurfaceVariant,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            // Кнопки входа
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = onGrechkaClick,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LoginPrimary,
                        contentColor = LoginOnPrimary,
                    ),
                ) {
                    Text(
                        text = if (loading) "Входим…" else "Войти через Гречку",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }

                if (BuildConfig.GOOGLE_SIGNIN_ENABLED) {
                    Button(
                        onClick = onGoogleClick,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GoogleButtonBg,
                            contentColor = GoogleButtonText,
                        ),
                    ) {
                        Text(
                            text = if (loading) "Входим…" else "Войти через Google",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    color = LoginError,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                text = "Продолжая, вы соглашаетесь с условиями использования.",
                color = LoginOnSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun LoginOptionsPreview() {
    NivelTheme {
        LoginOptions(
            error = null,
            loading = false,
            onGrechkaClick = {},
            onGoogleClick = {},
        )
    }
}
