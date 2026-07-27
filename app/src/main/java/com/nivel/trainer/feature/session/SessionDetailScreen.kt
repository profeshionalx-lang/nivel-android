package com.nivel.trainer.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.domain.SessionOverview
import com.nivel.trainer.feature.session.components.Background
import com.nivel.trainer.feature.session.components.CenterBox
import com.nivel.trainer.feature.session.components.CompleteReviewErrorBanner
import com.nivel.trainer.feature.session.components.EmptyState
import com.nivel.trainer.feature.session.components.ErrorState
import com.nivel.trainer.feature.session.components.Header
import com.nivel.trainer.feature.session.components.LibrarySheet
import com.nivel.trainer.feature.session.components.PasteInsightsSheet
import com.nivel.trainer.feature.session.components.Primary
import com.nivel.trainer.feature.session.components.SessionBody
import com.nivel.trainer.feature.session.components.SurfaceCard
import com.nivel.trainer.feature.session.components.headerTitle
import com.nivel.trainer.service.upload.UploadStage
import com.nivel.trainer.ui.state.RefreshOnResume
import kotlinx.coroutines.delay

/**
 * Экран карточки тренировки (B6, #9) — порт веб-страницы
 * `src/app/sessions/[id]/page.tsx` (trainer-режим): хедер «Сессия N», блок
 * статус/дата, секция аудио/транскрипта (готов/в процессе/ошибка) и секция
 * «Карточки» (черновики + approved, read-only). Упражнения не показываем —
 * как и веб (решение по #9). Состояния загрузки/пусто/ошибка обязательны.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit = {},
    onRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    RefreshOnResume(onRefresh = viewModel::refresh)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Авто-анализ запускает серверный аналайзер по готовому транскрипту (как pm2 в
    // вебе). Пока статус idle/processing — поллим результат каждые 3с (как setInterval
    // в InsightsAnalysisStatus), чтобы карточки появились без ручного обновления.
    val audio = state.overview?.audio
    LaunchedEffect(audio?.transcriptStatus, audio?.analysisStatus, state.generating) {
        if (!state.generating &&
            audio?.transcriptStatus == "ready" &&
            (audio.analysisStatus == "idle" || audio.analysisStatus == "processing")
        ) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                viewModel.pollRefresh()
            }
        }
    }

    // C5: после успешной заливки строка транскрипта на сервере появляется не мгновенно
    // (запускается STT). Пока заливка идёт/только что доехала, а транскрипта ещё нет —
    // поллим, чтобы экран сам перешёл «заливка → расшифровка» без ручного обновления.
    LaunchedEffect(state.uploadStage, audio == null) {
        val uploadActive = state.uploadStage is UploadStage.Queued ||
            state.uploadStage is UploadStage.Uploading ||
            state.uploadStage is UploadStage.Done
        if (uploadActive && audio == null) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                viewModel.pollRefresh()
            }
        }
    }

    SessionDetailContent(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        overview = state.overview,
        generating = state.generating,
        generateError = state.generateError,
        uploadStage = state.uploadStage,
        completingReview = state.completingReview,
        completeReviewError = state.completeReviewError,
        reorderedCards = state.reorderedCards,
        isOffline = state.isOffline,
        onGenerate = viewModel::generateInsights,
        onOpenPaste = viewModel::openPasteSheet,
        onOpenLibrary = viewModel::openLibrarySheet,
        onCompleteReview = viewModel::completeReview,
        onDismissCompleteReviewError = viewModel::dismissCompleteReviewError,
        onMoveCard = viewModel::moveCard,
        onCardDragEnd = viewModel::commitCardReorder,
        onBack = onBack,
        onRecord = onRecord,
        onRetry = viewModel::refresh,
        onRetryUpload = viewModel::retryUpload,
        modifier = modifier,
    )

    val sheet = state.pasteSheet
    if (sheet is PasteSheetState.Open) {
        PasteInsightsSheet(
            state = sheet,
            onMarkdownChange = viewModel::onPasteMarkdownChange,
            onSubmit = viewModel::submitPaste,
            onDismiss = viewModel::closePasteSheet,
        )
    }

    // #78 — применение коллекции карточек к сессии.
    if (state.librarySheet != LibrarySheetState.Closed) {
        LibrarySheet(
            state = state.librarySheet,
            onSelectCollection = viewModel::selectCollection,
            onBackToList = viewModel::backToCollectionsList,
            onApply = viewModel::applySelectedCollection,
            onDismiss = viewModel::dismissLibrarySheet,
        )
    }
}

/** Период поллинга статуса авто-анализа (как `setInterval(3000)` в вебе). */
private const val POLL_INTERVAL_MS = 3_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDetailContent(
    loading: Boolean,
    error: String?,
    overview: SessionOverview?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    generating: Boolean = false,
    generateError: String? = null,
    uploadStage: UploadStage = UploadStage.None,
    completingReview: Boolean = false,
    completeReviewError: String? = null,
    reorderedCards: List<com.nivel.trainer.domain.InsightCard>? = null,
    isOffline: Boolean = false,
    onGenerate: () -> Unit = {},
    onOpenPaste: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onRecord: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    onCompleteReview: () -> Unit = {},
    onDismissCompleteReviewError: () -> Unit = {},
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onCardDragEnd: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Header(title = headerTitle(overview?.detail), onBack = onBack)

        // G3 (#32): баннер «оффлайн» — показываем когда данные из кэша (нет сети).
        if (isOffline) {
            com.nivel.trainer.ui.state.OfflineBanner(onRetry = onRetry)
        }

        // #71: pull-to-refresh — рефреш поверх уже загруженного обзора, без спиннера.
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = refreshing,
                    state = pullState,
                    containerColor = SurfaceCard,
                    color = Primary,
                )
            },
        ) {
            when {
                loading && overview == null -> CenterBox { CircularProgressIndicator(color = Primary) }

                error != null && overview == null -> CenterBox { ErrorState(error, onRetry) }

                overview != null -> SessionBody(
                    overview = overview,
                    generating = generating,
                    generateError = generateError,
                    uploadStage = uploadStage,
                    completingReview = completingReview,
                    cards = reorderedCards ?: overview.cards,
                    onGenerate = onGenerate,
                    onOpenPaste = onOpenPaste,
                    onOpenLibrary = onOpenLibrary,
                    onRecord = onRecord,
                    onRetryUpload = onRetryUpload,
                    onCompleteReview = onCompleteReview,
                    onMoveCard = onMoveCard,
                    onCardDragEnd = onCardDragEnd,
                )

                else -> CenterBox { EmptyState() }
            }
        }
    }

    // Ошибка завершения разбора — показываем поверх контента (D5).
    if (completeReviewError != null) {
        CompleteReviewErrorBanner(
            message = completeReviewError,
            onDismiss = onDismissCompleteReviewError,
        )
    }
}
