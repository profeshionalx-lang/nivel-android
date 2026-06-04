package com.nivel.trainer.service

/*
 * Сервисный слой эпика 2 (диктофон).
 *  - RecordingService + RecordingController — foreground-запись (C1, готово).
 *  - upload/ — WorkManager-конвейер заливки: upload-url → PUT → transcribe
 *    (C3, готово). Триггерится из RecordingController при Finished.
 *  - Устойчивость заливки (резюмирование/докачка/refresh TTL) — C4 (далее).
 */
