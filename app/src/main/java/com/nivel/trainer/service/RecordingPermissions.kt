package com.nivel.trainer.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Разрешения, нужные записи (C1, режимы — A3/#97, звук видео-режима — A4/#98). Здесь —
 * единый список и проверки; сам системный запрос (launcher) делает экран записи (C2),
 * используя [required] (аудио) или [requiredForVideo] (видео).
 *
 * - `RECORD_AUDIO` — рантайм-разрешение нужно ОБОИМ режимам: аудио-режиму — как
 *   раньше, видео-режиму — с A4 тоже, для параллельного `MediaRecorder` (звук).
 * - `CAMERA` — рантайм-разрешение для видео-режима; аудио-режим его не спрашивает.
 * - `POST_NOTIFICATIONS` — рантайм только на Android 13+; нужен обоим режимам — видео
 *   тоже поднимает foreground-сервис для звука (A4) со своим уведомлением.
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

    /**
     * Разрешения для видео-режима: камера (кадр) + микрофон (A4, #98 — параллельный
     * `MediaRecorder` звука) + уведомления 13+ (тот же foreground-сервис звука).
     */
    val requiredForVideo: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

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
