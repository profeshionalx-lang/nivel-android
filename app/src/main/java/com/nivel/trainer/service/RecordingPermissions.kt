package com.nivel.trainer.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Разрешения, нужные записи (C1, режимы — A3/#97). Здесь — единый список и проверки;
 * сам системный запрос (launcher) делает экран записи (C2), используя [required]
 * (аудио, как раньше) или [requiredForVideo] (видео).
 *
 * - `RECORD_AUDIO` — рантайм-разрешение для аудио-режима, без него запись не стартует.
 * - `CAMERA` — рантайм-разрешение для видео-режима; аудио-режим его не спрашивает —
 *   поведение аудио-записи в A3 не меняется ни в чём.
 * - `POST_NOTIFICATIONS` — рантайм только на Android 13+ (уведомление аудио-записи;
 *   видео-режим уведомление не показывает — экран не гаснет, запись не фоновая).
 * - `FOREGROUND_SERVICE*` — install-time, спрашивать не нужно (только в манифесте).
 */
object RecordingPermissions {

    /** Разрешения для аудио-режима (как раньше, без изменений). */
    val required: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    /** Разрешения для видео-режима: только камера (звук в видео A3 не пишем). */
    val requiredForVideo: Array<String>
        get() = arrayOf(Manifest.permission.CAMERA)

    /** Есть ли разрешение на запись звука (минимум для старта аудио-записи). */
    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Есть ли разрешение на камеру (минимум для старта видео-записи). */
    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Все ли требуемые разрешения аудио-режима уже выданы. */
    fun allGranted(context: Context): Boolean =
        required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
