package com.nivel.trainer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application-класс Nivel. Точка входа Hilt — генерирует контейнер DI для всего
 * приложения. Регистрируется в манифесте как android:name=".NivelApp".
 */
@HiltAndroidApp
class NivelApp : Application()
