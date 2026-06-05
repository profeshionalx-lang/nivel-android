package com.nivel.trainer.feature.recorder

import androidx.lifecycle.ViewModel
import com.nivel.trainer.service.RecordingController
import com.nivel.trainer.service.RecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel экрана записи (C2, #11) — тонкая обёртка над process-wide
 * [RecordingController]. Сам контроллер `@Singleton` и владеет состоянием записи
 * (через [RecordingService][com.nivel.trainer.service.RecordingService]), поэтому
 * ViewModel ничего не дублирует: только прокидывает [state] в UI и переводит
 * команды экрана в вызовы контроллера.
 *
 * Хэндофф «запись → заливка» здесь НЕ делаем — он уже встроен в
 * [RecordingController.update]: при [RecordingState.Finished] контроллер сам ставит
 * заливку в очередь WorkManager (C3). Экрану остаётся только показать результат.
 */
@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val controller: RecordingController,
) : ViewModel() {

    /** Состояние записи — единый источник правды, переживает пересоздание Activity. */
    val state: StateFlow<RecordingState> = controller.state

    /** Старт записи для сессии. Разрешения экран запрашивает заранее (см. RecorderScreen). */
    fun start(sessionId: String) = controller.start(sessionId)

    /** Остановить запись — контроллер завершит сервис и инициирует заливку. */
    fun stop() = controller.stop()

    /** Сбросить Finished/Error в Idle после того, как UI «забрал» результат. */
    fun acknowledge() = controller.acknowledge()
}
