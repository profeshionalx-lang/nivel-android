package com.nivel.trainer.feature.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nivel.trainer.domain.SessionAudioStatus
import com.nivel.trainer.service.upload.UploadStage

/**
 * Секция аудио/транскрипта (веб, trainer) + стадии обработки (C5, #14).
 *
 * Полный пайплайн «запись → заливка(%) → расшифровка → инсайты»:
 *  - транскрипта ещё нет (`audio == null`) и идёт заливка ([uploadStage]) — показываем
 *    стадию заливки: в очереди / прогресс % / ошибка с повтором (C4/C5);
 *  - транскрипта нет и заливки нет — кнопка «Записать тренировку» (C2, нативный
 *    эквивалент веб-аплоадера);
 *  - транскрипт готов/в процессе/с ошибкой — статусы один-в-один с вебом.
 *
 * Дальше (анализ → карточки) ведёт секция «Карточки» по `analysisStatus` (B6/D2).
 */
@Composable
internal fun AudioSection(
    audio: SessionAudioStatus?,
    uploadStage: UploadStage,
    onRecord: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("Аудио тренировки")
        when {
            // Сервер ещё не завёл транскрипт — показываем стадию заливки (C5).
            audio == null -> when (uploadStage) {
                is UploadStage.Queued ->
                    StatusCard(glyph = "⏫", title = "Заливка в очереди…", subtitle = "Начнётся при подключении к сети")
                is UploadStage.Uploading -> UploadProgressCard(uploadStage.percent)
                is UploadStage.Done ->
                    StatusCard(glyph = "⏳", title = "Запускаем расшифровку…", subtitle = "Обычно занимает 15–30 сек")
                is UploadStage.Failed ->
                    UploadFailedCard(onRetry = onRetryUpload)
                UploadStage.None -> RecordButton(onRecord)
            }
            audio.transcriptStatus == "ready" ->
                StatusCard(glyph = "📄", title = "Транскрипт готов", subtitle = null, accent = Primary)
            audio.transcriptStatus == "processing" ->
                StatusCard(glyph = "⏳", title = "Транскрипция…", subtitle = "Обычно занимает 15–30 сек")
            else ->
                StatusCard(
                    glyph = "⚠",
                    title = "Ошибка транскрипции",
                    subtitle = audio.transcriptError,
                    accent = ErrorColor,
                )
        }
    }
}

/** Карточка-статус (аудио): глиф + заголовок (+ подзаголовок). */
@Composable
private fun StatusCard(glyph: String, title: String, subtitle: String?, accent: Color = OnSurfaceVariant) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = glyph, color = accent, fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, color = OnSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}
