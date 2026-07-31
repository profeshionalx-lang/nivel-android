package com.nivel.trainer.di

import com.nivel.trainer.service.video.NoOpPendingFrameUploadsChecker
import com.nivel.trainer.service.video.PendingFrameUploadsChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-модуль биндингов видео-конвейера (эпик NIVEL#235). Отдельно от
 * [RepositoryModule] — это не репозитории данных с сервера, а локальные сервисы.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VideoModule {

    // A9 (#103) — стоп-условие удаления видео по незалитым кадрам; заглушка,
    // пока заливка кадров (#101) не реализована. См. докблок интерфейса.
    @Binds
    @Singleton
    abstract fun bindPendingFrameUploadsChecker(
        impl: NoOpPendingFrameUploadsChecker,
    ): PendingFrameUploadsChecker
}
