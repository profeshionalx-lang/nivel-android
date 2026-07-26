package com.nivel.trainer.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.domain.OverviewSession
import com.nivel.trainer.domain.TrainerOverview
import com.nivel.trainer.ui.state.OfflineBanner
import com.nivel.trainer.ui.state.RefreshOnResume
import com.nivel.trainer.ui.theme.NivelTheme
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTimeTextStyle
import java.util.Locale

private val Background = Color(0xFF0E0E0E)
private val SurfaceCard = Color(0xFF1E1E1E)
private val Primary = Color(0xFFCAFD00)
private val OnPrimary = Color(0xFF000000)
private val Secondary = Color(0xFF7CC6FE)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)
private val ErrorColor = Color(0xFFFF7351)

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/**
 * Домашний экран тренера (A6, #76) — дашборд вместо каркаса B1: счётчик учеников,
 * ближайшие тренировки и сессии, ждущие разбора (`GET /api/v1/trainer/overview`,
 * NIVEL#224). Точки входа: карточка сессии → карточка тренировки, счётчик учеников →
 * экран «Ученики». Выход из аккаунта (#72) остаётся в хедере.
 *
 * У этого экрана нет прямого веб-эталона «один-в-один» (`trainer/overview` — новый
 * агрегат специально под нативный дашборд, на вебе такой страницы нет) — блоки и их
 * порядок взяты из Acceptance issue #76.
 */
@Composable
fun HomeScreen(
    onOpenStudents: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    onLoggedOut: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    RefreshOnResume(onRefresh = viewModel::refresh)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreenContent(
        overview = state.overview,
        refreshing = state.refreshing,
        error = state.error,
        showOfflineBanner = state.showOfflineBanner,
        offline = state.offline,
        confirmLogout = state.confirmLogout,
        loggingOut = state.loggingOut,
        onOpenStudents = onOpenStudents,
        onOpenSession = onOpenSession,
        onRetry = viewModel::refresh,
        onLogoutClick = viewModel::openLogoutConfirm,
        onDismissLogoutConfirm = viewModel::dismissLogoutConfirm,
        onConfirmLogout = { viewModel.confirmLogout(onDone = onLoggedOut) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    overview: TrainerOverview?,
    refreshing: Boolean,
    error: String?,
    showOfflineBanner: Boolean,
    offline: Boolean,
    confirmLogout: Boolean,
    loggingOut: Boolean,
    onOpenStudents: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRetry: () -> Unit,
    onLogoutClick: () -> Unit,
    onDismissLogoutConfirm: () -> Unit,
    onConfirmLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "NIVEL",
                color = Primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
            )
            TextButton(onClick = onLogoutClick, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Выйти", color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }

        if (showOfflineBanner) {
            OfflineBanner(
                onRetry = onRetry,
                message = if (offline) "Нет сети — показаны сохранённые данные" else "Не удалось обновить",
            )
        }

        val pullState = rememberPullToRefreshState()
        val showPullIndicator = refreshing && overview != null
        PullToRefreshBox(
            isRefreshing = showPullIndicator,
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = showPullIndicator,
                    state = pullState,
                    containerColor = SurfaceCard,
                    color = Primary,
                )
            },
        ) {
            when {
                // Спиннер только при первой загрузке — иначе показываем последний снимок.
                refreshing && overview == null && error == null -> CenterBox {
                    CircularProgressIndicator(color = Primary)
                }

                error != null && overview == null -> CenterBox {
                    ErrorState(message = error, onRetry = onRetry)
                }

                overview != null -> DashboardBody(
                    overview = overview,
                    onOpenSession = onOpenSession,
                    onOpenStudents = onOpenStudents,
                )

                else -> CenterBox {
                    Text("Нет данных", color = OnSurfaceVariant, fontSize = 14.sp)
                }
            }
        }
    }

    if (confirmLogout) {
        LogoutConfirmSheet(
            loggingOut = loggingOut,
            onDismiss = onDismissLogoutConfirm,
            onConfirm = onConfirmLogout,
        )
    }
}

/**
 * Тело дашборда: «Ждут разбора» (первой, если непусто — самое важное), «Ближайшие
 * тренировки», счётчик учеников. Пустые состояния — инлайновой подписью в карточке
 * секции, ни один блок не прячется молча (mobile-first гайдлайн Nivel).
 */
@Composable
private fun DashboardBody(
    overview: TrainerOverview,
    onOpenSession: (String) -> Unit,
    onOpenStudents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SessionsSection(
            title = "Ждут разбора",
            sessions = overview.pendingReview,
            emptyText = "Все сессии разобраны.",
            dateLabel = ::formatCompletedLabel,
            onOpenSession = onOpenSession,
        )
        SessionsSection(
            title = "Ближайшие тренировки",
            sessions = overview.upcomingSessions,
            emptyText = "Пока нет запланированных тренировок.",
            dateLabel = ::formatScheduledLabel,
            onOpenSession = onOpenSession,
            modifier = Modifier.padding(top = 8.dp),
        )
        StudentsRow(
            count = overview.studentsCount,
            onClick = onOpenStudents,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun SessionsSection(
    title: String,
    sessions: List<OverviewSession>,
    emptyText: String,
    dateLabel: (OverviewSession) -> String,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, color = OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Black)
            if (sessions.isNotEmpty()) {
                Text(text = "${sessions.size}", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (sessions.isEmpty()) {
            Text(
                text = emptyText,
                color = OnSurfaceVariant,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        dateLabel = dateLabel(session),
                        onClick = { onOpenSession(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: OverviewSession, dateLabel: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(208.dp)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = TouchTarget),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Primary, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)))
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = session.studentName?.takeIf { it.isNotBlank() } ?: "Ученик",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = session.sessionNumber?.let { "Тренировка №$it" } ?: "Тренировка",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = dateLabel, color = Secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StudentsRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = TouchTarget + 16.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Ученики", color = OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Text("$count", color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Text("›", color = OnSurfaceVariant, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Text(text = message, color = ErrorColor, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("Повторить", color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Bottom-sheet подтверждения выхода (mobile-first — не центрированный диалог). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogoutConfirmSheet(
    loggingOut: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Выйти из аккаунта?",
                color = OnSurface,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Понадобится войти заново, чтобы продолжить работу с учениками.",
                color = OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    enabled = !loggingOut,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorColor,
                        contentColor = Color.White,
                        disabledContainerColor = ErrorColor.copy(alpha = 0.4f),
                        disabledContentColor = Color.White,
                    ),
                ) {
                    Text(if (loggingOut) "Выходим…" else "Выйти", fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !loggingOut,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Отмена", color = OnSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- Форматирование дат (как в вебе: UTC, ru-RU) ---

private val RuLocale = Locale("ru", "RU")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RuLocale)
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RuLocale)

/** «Понедельник, 14:00» — предстоящая тренировка ([OverviewSession.date] = scheduled_at). */
private fun formatScheduledLabel(session: OverviewSession): String {
    val dt = parseUtc(session.date) ?: return "—"
    val weekday = dt.dayOfWeek.getDisplayName(JavaTimeTextStyle.FULL_STANDALONE, RuLocale)
        .replaceFirstChar { it.uppercase(RuLocale) }
    return "$weekday, ${dt.format(TIME_FMT)}"
}

/** «Завершено 5 июня» — сессия ждёт разбора ([OverviewSession.date] = completed_at). */
private fun formatCompletedLabel(session: OverviewSession): String {
    val dt = parseUtc(session.date) ?: return "Завершено"
    return "Завершено ${dt.format(DATE_FMT)}"
}

/** Парсит ISO-строку времени в UTC (сервер отдаёт с/без зоны) — как на других экранах. */
private fun parseUtc(raw: String?): OffsetDateTime? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC) }
        .recoverCatching { java.time.LocalDateTime.parse(value).atOffset(ZoneOffset.UTC) }
        .recoverCatching { java.time.LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC) }
        .getOrNull()
}

// --- Previews ---

private val previewOverview = TrainerOverview(
    studentsCount = 12,
    upcomingSessions = listOf(
        OverviewSession("s1", "u1", "Иван Петров", 5, "2026-08-01T10:00:00Z"),
        OverviewSession("s2", "u2", "Мария Смирнова", 2, "2026-08-02T15:30:00Z"),
    ),
    pendingReview = listOf(
        OverviewSession("s3", "u3", "Пётр Иванов", 3, "2026-07-25T09:00:00Z"),
    ),
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun HomeScreenDashboardPreview() {
    NivelTheme {
        HomeScreenContent(
            overview = previewOverview,
            refreshing = false,
            error = null,
            showOfflineBanner = false,
            offline = false,
            confirmLogout = false,
            loggingOut = false,
            onOpenStudents = {},
            onOpenSession = {},
            onRetry = {},
            onLogoutClick = {},
            onDismissLogoutConfirm = {},
            onConfirmLogout = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun HomeScreenEmptyPreview() {
    NivelTheme {
        HomeScreenContent(
            overview = TrainerOverview(0, emptyList(), emptyList()),
            refreshing = false,
            error = null,
            showOfflineBanner = false,
            offline = false,
            confirmLogout = false,
            loggingOut = false,
            onOpenStudents = {},
            onOpenSession = {},
            onRetry = {},
            onLogoutClick = {},
            onDismissLogoutConfirm = {},
            onConfirmLogout = {},
        )
    }
}
