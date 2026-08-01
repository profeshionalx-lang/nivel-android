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
import com.nivel.trainer.feature.frames.FrameSlot
import com.nivel.trainer.feature.session.components.Background
import com.nivel.trainer.feature.session.components.CenterBox
import com.nivel.trainer.feature.session.components.CompleteReviewErrorBanner
import com.nivel.trainer.feature.session.components.EditCardSheet
import com.nivel.trainer.feature.session.components.EmptyState
import com.nivel.trainer.feature.session.components.ErrorState
import com.nivel.trainer.feature.session.components.FrameSlotActions
import com.nivel.trainer.feature.session.components.Header
import com.nivel.trainer.feature.session.components.LibrarySheet
import com.nivel.trainer.feature.session.components.PasteInsightsSheet
import com.nivel.trainer.feature.session.components.Primary
import com.nivel.trainer.feature.session.components.SessionBody
import com.nivel.trainer.feature.session.components.SurfaceCard
import com.nivel.trainer.feature.session.components.VideoDeleteConfirmSheet
import com.nivel.trainer.feature.session.components.headerTitle
import com.nivel.trainer.service.upload.UploadStage
import com.nivel.trainer.ui.state.RefreshOnResume
import kotlinx.coroutines.delay

/**
 * Экран карточки тренировки (B6, #9) — порт веб-страницы
 * `src/app/sessions/[id]/page.tsx` (trainer-режим): хедер «Сессия N», блок
 * статус/дата, секция аудио/транскрипта (готов/в процессе/ошибка) и секция
 * «Карточки»: черновики — ревью approve/reject/edit (A2, #96), approved —
 * drag-and-drop (D4, #22). Упражнения не показываем — как и веб (решение по
 * #9). Состояния загрузки/пусто/ошибка обязательны.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit = {},
    onRecord: () -> Unit = {},
    // A8 (#102): вход в скрабер кадра (A6, #100) — навигация решается вызывающей
    // стороной (NivelNavHost), ViewModel про NavController не знает.
    onOpenScrubber: (cardId: String, slot: FrameSlot) -> Unit = { _, _ -> },
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

    // A8 (#102): пучок данных/колбэков FrameSlotRow — hasLocalVideo/стадии заливки читаются
    // из ViewModel, onPick вызывает навигацию, которую знает только этот экран.
    val frameActions = FrameSlotActions(
        hasLocalVideo = state.localVideo is LocalVideoUiState.Present,
        stageFor = viewModel::frameStageFor,
        onPick = onOpenScrubber,
        onRemove = viewModel::removeFrame,
        onRetry = viewModel::retryFrameUpload,
    )

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
        cardActionError = state.cardActionError,
        localVideo = state.localVideo,
        frameActions = frameActions,
        onGenerate = viewModel::generateInsights,
        onOpenPaste = viewModel::openPasteSheet,
        onOpenLibrary = viewModel::openLibrarySheet,
        onCompleteReview = viewModel::completeReview,
        onDismissCompleteReviewError = viewModel::dismissCompleteReviewError,
        onMoveCard = viewModel::moveCard,
        onCardDragEnd = viewModel::commitCardReorder,
        onApproveCard = viewModel::approveCard,
        onRejectCard = viewModel::rejectCard,
        onEditCard = viewModel::openEditSheet,
        onDismissCardActionError = viewModel::dismissCardActionError,
        onBack = onBack,
        onRecord = onRecord,
        onRetry = viewModel::refresh,
        onRetryUpload = viewModel::retryUpload,
        onDeleteVideo = viewModel::requestDeleteVideo,
        onDismissVideoError = viewModel::dismissVideoError,
        modifier = modifier,
    )

    // A9 (#103): подтверждение удаления видео — общее для «Завершить разбор» и ручного удаления.
    val videoDeleteConfirm = state.videoDeleteConfirm
    if (videoDeleteConfirm is VideoDeleteConfirmState.Open) {
        VideoDeleteConfirmSheet(
            sizeBytes = videoDeleteConfirm.sizeBytes,
            intent = videoDeleteConfirm.intent,
            onConfirm = viewModel::confirmVideoDelete,
            onDismiss = viewModel::dismissVideoDeleteConfirm,
        )
    }

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

    // A2 (#96) — bottom-sheet правки draft/approved-карточки.
    val editSheet = state.editSheet
    if (editSheet is EditSheetState.Open) {
        EditCardSheet(
            state = editSheet,
            onTitleChange = viewModel::onEditTitleChange,
            onBodyChange = viewModel::onEditBodyChange,
            onTagChange = viewModel::onEditTagChange,
            onSideChange = viewModel::onEditSideChange,
            onSave = viewModel::submitEdit,
            onDismiss = viewModel::closeEditSheet,
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
    cardActionError: String? = null,
    localVideo: LocalVideoUiState = LocalVideoUiState.None,
    frameActions: FrameSlotActions = FrameSlotActions(
        hasLocalVideo = false,
        stageFor = { _, _ -> UploadStage.None },
        onPick = { _, _ -> },
        onRemove = { _, _ -> },
        onRetry = { _, _ -> },
    ),
    onGenerate: () -> Unit = {},
    onOpenPaste: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onRecord: () -> Unit = {},
    onRetryUpload: () -> Unit = {},
    onCompleteReview: () -> Unit = {},
    onDismissCompleteReviewError: () -> Unit = {},
    onMoveCard: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onCardDragEnd: () -> Unit = {},
    onApproveCard: (String) -> Unit = {},
    onRejectCard: (String) -> Unit = {},
    onEditCard: (com.nivel.trainer.domain.InsightCard) -> Unit = {},
    onDismissCardActionError: () -> Unit = {},
    onDeleteVideo: () -> Unit = {},
    onDismissVideoError: () -> Unit = {},
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
                    localVideo = localVideo,
                    frameActions = frameActions,
                    onGenerate = onGenerate,
                    onOpenPaste = onOpenPaste,
                    onOpenLibrary = onOpenLibrary,
                    onRecord = onRecord,
                    onRetryUpload = onRetryUpload,
                    onCompleteReview = onCompleteReview,
                    onMoveCard = onMoveCard,
                    onCardDragEnd = onCardDragEnd,
                    onApproveCard = onApproveCard,
                    onRejectCard = onRejectCard,
                    onEditCard = onEditCard,
                    onDeleteVideo = onDeleteVideo,
                    onDismissVideoError = onDismissVideoError,
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

    // A2 (#96) — ошибка approve/reject/edit черновика (баннер, тот же паттерн что D5).
    if (cardActionError != null) {
        CompleteReviewErrorBanner(
            message = cardActionError,
            onDismiss = onDismissCardActionError,
        )
    }
}
