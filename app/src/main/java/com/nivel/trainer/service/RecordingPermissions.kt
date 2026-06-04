package com.nivel.trainer.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Разрешения, нужные фоновой записи (C1). Здесь — единый список и проверки;
 * сам системный запрос (launcher) делает экран записи (C2), используя [required].
 *
 * - `RECORD_AUDIO` — рантайм-разрешение, без него запись не стартует.
 * - `POST_NOTIFICATIONS` — рантайм только на Android 13+ (нужно для уведомления записи).
 * - `FOREGROUND_SERVICE*` — install-time, спрашивать не нужно (только в манифесте).
 */
object RecordingPermissions {

    /** Разрешения, которые нужно запросить у пользователя на текущей версии ОС. */
    val required: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    /** Есть ли разрешение на запись звука (минимум для старта записи). */
    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Все ли требуемые разрешения уже выданы. */
    fun allGranted(context: Context): Boolean =
        required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
