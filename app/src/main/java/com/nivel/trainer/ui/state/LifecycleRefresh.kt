package com.nivel.trainer.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * #71: перезапрашивает данные каждый раз, когда экран возвращается в foreground
 * (`ON_RESUME`) — без этого ViewModel, привязанный к живому back-stack entry,
 * не видит изменений, сделанных, пока экран был в фоне (напр. возврат из профиля
 * ученика в список — новый ученик, созданный на вебе, иначе не появится).
 *
 * [debounceMs] защищает от очереди запросов при быстрых переходах между экранами:
 * повторные `ON_RESUME` чаще этого интервала игнорируются. Первый `ON_RESUME`
 * (тот, что срабатывает сразу при входе экрана в composition, вслед за начальной
 * загрузкой) тоже в пределах дебаунса — не дублирует уже идущую загрузку.
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
