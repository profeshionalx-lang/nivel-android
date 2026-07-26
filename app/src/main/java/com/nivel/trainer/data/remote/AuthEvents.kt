package com.nivel.trainer.data.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #72 — событийная шина 401. [UnauthorizedInterceptor] эмитит сюда при отказе сервера
 * по протухшему/невалидному bearer; `MainActivity`/`NivelRoot` подписывается и уводит
 * пользователя на экран логина. `extraBufferCapacity = 1` — событие не теряется, если
 * подписчик ещё не успел стартовать (напр. 401 прилетел раньше первой композиции).
 */
@Singleton
class AuthEvents @Inject constructor() {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    fun emitUnauthorized() {
        _unauthorized.tryEmit(Unit)
    }
}
