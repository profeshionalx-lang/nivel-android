package com.nivel.trainer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application-класс Nivel. Точка входа Hilt — генерирует контейнер DI для всего
 * приложения. Регистрируется в манифесте как android:name=".NivelApp".
 *
 * Реализует [Configuration.Provider] (C3): WorkManager инициализируется
 * on-demand с [HiltWorkerFactory], чтобы воркеры заливки могли инжектить
 * зависимости (API, OkHttp) через Hilt. Дефолтный `WorkManagerInitializer`
 * отключён в манифесте — иначе он поднимет WorkManager без нашей фабрики.
 */
@HiltAndroidApp
class NivelApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
