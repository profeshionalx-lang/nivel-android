package com.nivel.trainer.feature.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.nivel.trainer.domain.InsightCard
import com.nivel.trainer.domain.SessionAudioStatus
import com.nivel.trainer.domain.SessionDetail
import com.nivel.trainer.domain.SessionOverview
import com.nivel.trainer.service.upload.UploadStage
import com.nivel.trainer.ui.theme.NivelTheme

// --- Previews ---

private val previewDetail = SessionDetail(
    id = "s1",
    goalId = "g1",
    sessionNumber = 3,
    status = "completed",
    trainerNotes = null,
    scheduledAt = "2026-06-02T14:00:00Z",
    completedAt = "2026-06-02T15:00:00Z",
)

private fun previewCard(id: String, title: String, status: String, tag: String) = InsightCard(
    id = id,
    sessionId = "s1",
    studentId = null,
    trainerId = null,
    title = title,
    body = "Короткий разбор момента и рекомендация для отработки.",
    quote = null,
    frontText = null,
    contextText = null,
    tags = listOf(tag),
    source = null,
    trainerStatus = status,
    studentDecision = null,
    position = 0,
    createdAt = null,
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionReadyPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail,
                audio = SessionAudioStatus("ready", null, "ready", null),
                cards = listOf(
                    previewCard("c1", "Приём слева под давлением", "draft", "техника"),
                    previewCard("c2", "Выход к сетке", "approved", "тактика"),
                ),
            ),
            onBack = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionProcessingEmptyPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail.copy(status = "planned", completedAt = null),
                audio = SessionAudioStatus("processing", null, "idle", null),
                cards = emptyList(),
            ),
            onBack = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionUploadingPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail.copy(status = "completed"),
                audio = null,
                cards = emptyList(),
            ),
            uploadStage = UploadStage.Uploading(percent = 42),
            onBack = {},
            onRetry = {},
        )
    }
}

// --- A9 (#103): индикатор занятого места видео ---

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionWithLocalVideoPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail,
                audio = SessionAudioStatus("ready", null, "ready", null),
                cards = listOf(previewCard("c1", "Приём слева под давлением", "approved", "техника")),
            ),
            localVideo = LocalVideoUiState.Present(sizeBytes = 2_400_000_000L),
            onBack = {},
            onRetry = {},
        )
    }
}

/** Сирота (п.5 issue): разбор уже завершён (в т.ч. из веба), а видео на телефоне ещё есть. */
@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun SessionOrphanVideoPreview() {
    NivelTheme {
        SessionDetailContent(
            loading = false,
            error = null,
            overview = SessionOverview(
                detail = previewDetail.copy(trainerReviewCompleted = true),
                audio = SessionAudioStatus("ready", null, "ready", null),
                cards = listOf(previewCard("c1", "Приём слева под давлением", "approved", "техника")),
            ),
            localVideo = LocalVideoUiState.Present(sizeBytes = 480_000_000L),
            onBack = {},
            onRetry = {},
        )
    }
}
