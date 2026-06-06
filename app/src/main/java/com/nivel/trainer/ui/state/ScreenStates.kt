package com.nivel.trainer.ui.state

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException

/**
 * G3 (#32) — общие состояния экранов: загрузка / ошибка / пусто / оффлайн-индикатор.
 *
 * Контракт оффлайн-чтения (single-source-of-truth по AGENTS.md):
 *  - данные читаются из кэша (Room) и видны мгновенно, даже без сети;
 *  - если фоновое обновление с сервера упало по сети, а кэш есть — НЕ прячем данные,
 *    а показываем сверху ненавязчивый [OfflineBanner] «показаны сохранённые данные»;
 *  - если кэша нет вовсе — показываем полноценный [FullScreenError]/[LoadingState].
 *
 * Цвета — один-в-один из веб-Nivel (`globals.css`), как в feature-экранах.
 */

private val Background = Color(0xFF0E0E0E)
private val SurfaceCard = Color(0xFF1E1E1E)
private val Primary = Color(0xFFCAFD00)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)
private val ErrorColor = Color(0xFFFF7351)
private val Amber = Color(0xFFFBBF24)

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/**
 * Сетевая ли это ошибка (нет связи), а не серверная/логическая. Используется, чтобы
 * показать «оффлайн»-формулировку вместо общей ошибки. Retrofit/OkHttp при отсутствии
 * сети бросают [IOException] (или его наследников — Unknown host / timeout / connect).
 */
fun isNetworkError(error: Throwable?): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is IOException) return true
        cause = cause.cause
    }
    return false
}

/**
 * Ненавязчивый баннер «работаем оффлайн» поверх кэшированных данных (G3). Показывается,
 * когда обновление с сервера сорвалось по сети, но кэш есть — данные на экране устарели,
 * но валидны. Кнопка «Обновить» повторяет запрос. Mobile-first: тач-зона ретрая ≥48dp.
 */
@Composable
fun OfflineBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = "Нет сети — показаны сохранённые данные",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("☁", color = Amber, fontSize = 16.sp)
        Text(
            text = message,
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = TouchTarget),
        ) {
            Text("Обновить", color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/** Центрированный спиннер на весь доступный экран (первичная загрузка при пустом кэше). */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    CenterBox(modifier) { CircularProgressIndicator(color = Primary) }
}

/**
 * Полноэкранная ошибка с кнопкой «Повторить» (G3). Когда кэша нет и показать нечего.
 * Для сетевой ошибки формулировка — про отсутствие связи, иначе — текст ошибки.
 */
@Composable
fun FullScreenError(
    error: Throwable?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackMessage: String = "Что-то пошло не так. Попробуйте снова.",
) {
    val message = when {
        isNetworkError(error) -> "Нет подключения к интернету. Проверьте сеть и повторите."
        else -> error?.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
    }
    val glyph = if (isNetworkError(error)) "📡" else "⚠"
    CenterBox(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(glyph, fontSize = 40.sp)
            Spacer(Modifier.size(16.dp))
            Text(
                text = message,
                color = ErrorColor,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Повторить", color = Primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Полноэкранное «пусто» с глифом и подписью (G3). Загрузка завершена, данных нет. */
@Composable
fun EmptyStateView(
    title: String,
    modifier: Modifier = Modifier,
    glyph: String = "📭",
) {
    CenterBox(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, fontSize = 40.sp)
            Spacer(Modifier.size(16.dp))
            Text(text = title, color = OnSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CenterBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center,
    ) { content() }
}
