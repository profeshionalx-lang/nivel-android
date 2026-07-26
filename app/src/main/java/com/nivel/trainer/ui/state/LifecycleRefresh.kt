package com.nivel.trainer.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * #71: перезапрашивает данные, когда экран возвращается в foreground (`ON_RESUME`) БЕЗ
 * пересоздания composable — приложение свернули (домой/переключение задач) и развернули
 * обратно, не покинув экран через навигацию. Composable в этом случае не размонтируется,
 * поэтому обычный `LaunchedEffect(id) { load(id) }` в начале экрана не перезапускает загрузку
 * — вот для этого случая и нужен явный наблюдатель за жизненным циклом.
 *
 * Возврат на экран ЧЕРЕЗ навигацию (back-stack pop) — другой случай: Navigation Compose
 * размонтирует composable при уходе и создаёт заново при возврате, так что там рефреш уже
 * даёт `LaunchedEffect(id) { load(id) }`/`LaunchedEffect(Unit)` на самом экране (см.
 * `StudentsListScreen`) — это надёжнее, чем полагаться на точный момент `ON_RESUME`
 * (при подключении наблюдателя к уже-`RESUMED` lifecycle Android может синтетически
 * реплеить `ON_RESUME` сразу же, неотличимо от «просто открыли экран»).
 *
 * [debounceMs] защищает от очереди запросов, если `ON_RESUME` прилетает чаще этого
 * интервала (быстрое сворачивание/разворачивание, системные тонкости жизненного цикла).
 */
@Composable
fun RefreshOnResume(debounceMs: Long = 2_000, onRefresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnRefresh = rememberUpdatedState(onRefresh)
    DisposableEffect(lifecycleOwner) {
        var lastRefreshAt = System.currentTimeMillis()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = System.currentTimeMillis()
                if (now - lastRefreshAt >= debounceMs) {
                    lastRefreshAt = now
                    currentOnRefresh.value()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
