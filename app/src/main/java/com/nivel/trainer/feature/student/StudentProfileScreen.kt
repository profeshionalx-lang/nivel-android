package com.nivel.trainer.feature.student

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nivel.trainer.domain.Goal
import com.nivel.trainer.domain.MasterPlan
import com.nivel.trainer.domain.MasterPlanItem
import com.nivel.trainer.domain.MasterPlanSection
import com.nivel.trainer.domain.Problem
import com.nivel.trainer.domain.StudentProfile
import com.nivel.trainer.domain.StudentSession
import com.nivel.trainer.ui.theme.NivelTheme
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Цвета один-в-один из веб-Nivel (src/app/globals.css), как на экране «Ученики» (B4).
// Глобальная Material-тема доводится в G4; здесь фиксируем точные значения экрана.
private val Background = Color(0xFF0E0E0E)            // --background
private val SurfaceLow = Color(0xFF161616)           // --surface-low
private val SurfaceCard = Color(0xFF1E1E1E)          // --surface-card
private val Primary = Color(0xFFCAFD00)              // --primary (лайм)
private val OnPrimary = Color(0xFF000000)            // text на primary
private val Secondary = Color(0xFF7CC6FE)            // --secondary
private val OnSurface = Color(0xFFF5F5F5)            // --on-surface
private val OnSurfaceVariant = Color(0xFFADAAAA)     // --on-surface-variant
private val ErrorColor = Color(0xFFFF7351)           // --error

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/** Русская локаль для дат (как `ru-RU` в вебе). */
private val RuLocale = Locale("ru", "RU")

/**
 * Экран профиля ученика (B5, #8) — порт trainer-страницы веба
 * `src/app/trainer/students/[id]/page.tsx` → `DashboardView` (trainer-режим):
 * хедер «Ученик», профиль (аватар-инициалы/имя/email), превью мастер-плана,
 * цели (горизонтальная карусель), сессии (список, тап → карточка сессии B6).
 * Состояния загрузки/пусто/ошибка обязательны.
 */
@Composable
fun StudentProfileScreen(
    studentId: String,
    onBack: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: StudentProfileViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId) { viewModel.load(studentId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    StudentProfileContent(
        loading = state.loading,
        error = state.error,
        profile = state.profile,
        goalCreator = state.goalCreator,
        onBack = onBack,
        onOpenSession = onOpenSession,
        onRetry = viewModel::refresh,
        onAddGoal = viewModel::openGoalCreator,
        onDismissGoalCreator = viewModel::dismissGoalCreator,
        onCustomProblemChange = viewModel::onCustomProblemChange,
        onSelectProblem = viewModel::onSelectProblem,
        onRetryProblems = viewModel::retryLoadProblems,
        onSubmitGoal = viewModel::submitGoal,
        modifier = modifier,
    )
}

@Composable
private fun StudentProfileContent(
    loading: Boolean,
    error: String?,
    profile: StudentProfile?,
    goalCreator: GoalCreatorState,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRetry: () -> Unit,
    onAddGoal: () -> Unit,
    onDismissGoalCreator: () -> Unit,
    onCustomProblemChange: (String) -> Unit,
    onSelectProblem: (Int?) -> Unit,
    onRetryProblems: () -> Unit,
    onSubmitGoal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Header(onBack = onBack)

        when {
            loading && profile == null -> CenterBox { CircularProgressIndicator(color = Primary) }

            error != null && profile == null -> CenterBox { ErrorState(error, onRetry) }

            profile != null -> ProfileBody(
                profile = profile,
                onOpenSession = onOpenSession,
                onAddGoal = onAddGoal,
            )

            else -> CenterBox { EmptyState() }
        }
    }

    // Шит создания цели (E2) — оверлей поверх контента, как модалка в вебе.
    if (goalCreator.visible) {
        GoalCreatorSheet(
            state = goalCreator,
            onDismiss = onDismissGoalCreator,
            onCustomProblemChange = onCustomProblemChange,
            onSelectProblem = onSelectProblem,
            onRetryProblems = onRetryProblems,
            onSubmit = onSubmitGoal,
        )
    }
}

/** Хедер как glass-nav веба: назад «‹» + заголовок «Ученик». */
@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(TouchTarget)) {
            Text("‹", color = OnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Ученик",
            color = Primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun ProfileBody(
    profile: StudentProfile,
    onOpenSession: (String) -> Unit,
    onAddGoal: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { ProfileHeaderBlock(profile) }
        profile.masterPlan?.let { plan ->
            if (plan.sections.isNotEmpty()) item { MasterPlanBlock(plan) }
        }
        item { GoalsSection(profile.goals, onAddGoal) }
        item { SessionsSection(profile.sessions, onOpenSession) }
    }
}

/** Шапка профиля: аватар-инициалы + имя + email (read-only, как в вебе). */
@Composable
private fun ProfileHeaderBlock(profile: StudentProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(SurfaceCard, CircleShape)
                .border(2.dp, Primary.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials(profile.fullName),
                color = OnSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.fullName?.takeIf { it.isNotBlank() }
                    ?: profile.email?.takeIf { it.isNotBlank() }
                    ?: "Без имени",
                color = OnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            profile.email?.takeIf { it.isNotBlank() }?.let { email ->
                Text(
                    text = email,
                    color = OnSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Превью мастер-плана: метка + список секций (буллет + название + N элементов). */
@Composable
private fun MasterPlanBlock(plan: MasterPlan) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLow, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "Мастер-план",
            color = Secondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Веб показывает превью первых 2 секций — повторяем один-в-один.
            plan.sections.take(2).forEach { section ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Secondary.copy(alpha = 0.6f), CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = section.title,
                        color = OnSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${section.items.size} элементов",
                        color = OnSurfaceVariant.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/**
 * Секция «Активные цели» — горизонтальная карусель карточек целей.
 * В шапке справа — кнопка «+ Новая» (E2): порт тренерского `InlineGoalCreator`
 * из веб-`DashboardView` (рядом с заголовком «Активные цели»).
 */
@Composable
private fun GoalsSection(goals: List<Goal>, onAddGoal: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionTitle("Активные цели")
            // «+ Новая» — primary, как в вебе (`text-primary text-xs font-bold uppercase`).
            Text(
                text = "+ Новая",
                color = Primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAddGoal)
                    .heightIn(min = TouchTarget)
                    .padding(horizontal = 8.dp)
                    .wrapContentHeight(),
            )
        }
        Spacer(Modifier.size(12.dp))
        if (goals.isEmpty()) {
            // Тренерский empty-state веба: «No goals yet. Click + new to add one.»
            Text(
                text = "Целей пока нет. Нажмите «+ Новая», чтобы добавить.",
                color = OnSurfaceVariant,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                items(goals, key = { it.id }) { goal -> GoalCard(goal) }
            }
        }
    }
}

@Composable
private fun GoalCard(goal: Goal) {
    // Веб: карточка с верхним акцентом `borderTop: 2px solid primary`.
    // Compose не даёт per-side border — клипуем и кладём верхнюю полосу primary.
    Column(
        modifier = Modifier
            .width(208.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Primary))
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Primary, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Цель",
                    color = Primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = goal.customProblem?.takeIf { it.isNotBlank() } ?: "—",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- E2 (#25): bottom-sheet создания цели (порт веб-`InlineGoalCreator`) ---

/**
 * Нижний шит создания цели. Один-в-один с веб-модалкой `InlineGoalCreator`:
 * заголовок «Новая цель», поле свободного текста проблемы и пикер проблемы из
 * справочника (необязательный), кнопки «Сохранить»/«Отмена». «Сохранить»
 * активна только если выбрана проблема или введён текст. На мобиле — нативный
 * `ModalBottomSheet` вместо центрированной модалки (mobile-first гайдлайн).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalCreatorSheet(
    state: GoalCreatorState,
    onDismiss: () -> Unit,
    onCustomProblemChange: (String) -> Unit,
    onSelectProblem: (Int?) -> Unit,
    onRetryProblems: () -> Unit,
    onSubmit: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Новая цель",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )

            // Свободное описание проблемы (textarea в вебе).
            OutlinedTextField(
                value = state.customProblem,
                onValueChange = onCustomProblemChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Опишите проблему (или оставьте пустым и выберите из списка)",
                        color = OnSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                    )
                },
                minLines = 2,
                enabled = !state.submitting,
                textStyle = androidx.compose.ui.text.TextStyle(color = OnSurface, fontSize = 14.sp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = OnSurfaceVariant.copy(alpha = 0.3f),
                    cursorColor = Primary,
                    focusedContainerColor = SurfaceLow,
                    unfocusedContainerColor = SurfaceLow,
                ),
            )

            // Пикер проблемы из справочника (нативный эквивалент <select>).
            ProblemPicker(state = state, onSelectProblem = onSelectProblem, onRetry = onRetryProblems)

            state.error?.let { msg ->
                Text(text = msg, color = ErrorColor, fontSize = 13.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // «Сохранить» — primary, активна только при валидном вводе.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (state.canSave) Primary else Primary.copy(alpha = 0.4f))
                        .clickable(enabled = state.canSave, onClick = onSubmit)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.submitting) "Сохранение…" else "Сохранить",
                        color = OnPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // «Отмена» — bordered.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, OnSurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !state.submitting, onClick = onDismiss)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Отмена",
                        color = OnSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Пикер проблемы — поле с выпадающим меню (нативный аналог `<select>` веба).
 * Первый пункт снимает привязку («— Без привязки —»). Пока справочник грузится —
 * поле неактивно со спиннером; если загрузка сорвалась — тап повторяет её.
 * Свободный текст доступен в любом случае.
 */
@Composable
private fun ProblemPicker(
    state: GoalCreatorState,
    onSelectProblem: (Int?) -> Unit,
    onRetry: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.problems.firstOrNull { it.id == state.selectedProblemId }
    val hasProblems = state.problems.isNotEmpty()
    val label = when {
        state.problemsLoading -> "Загрузка справочника…"
        state.problemsFailed && !hasProblems -> "Справочник недоступен — нажмите, чтобы повторить"
        selected != null -> selected.name
        else -> "— Привязать проблему (необязательно) —"
    }
    // Поле кликабельно, если идёт выбор из списка ИЛИ нужно повторить загрузку.
    val clickEnabled = !state.submitting && !state.problemsLoading &&
        (hasProblems || state.problemsFailed)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceLow)
                .border(1.dp, OnSurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .clickable(enabled = clickEnabled) {
                    if (hasProblems) expanded = true else onRetry()
                }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (selected != null) OnSurface else OnSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.problemsLoading) {
                CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else if (state.problemsFailed && !hasProblems) {
                Text("↻", color = OnSurfaceVariant, fontSize = 16.sp)
            } else {
                Text("▾", color = OnSurfaceVariant, fontSize = 14.sp)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("— Без привязки —", color = OnSurfaceVariant) },
                onClick = {
                    onSelectProblem(null)
                    expanded = false
                },
            )
            state.problems.forEach { problem ->
                DropdownMenuItem(
                    text = { Text(problem.name, color = OnSurface) },
                    onClick = {
                        onSelectProblem(problem.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Секция «Тренировки» — вертикальный список сессий (тап → карточка сессии B6). */
@Composable
private fun SessionsSection(sessions: List<StudentSession>, onOpenSession: (String) -> Unit) {
    Column {
        SectionTitle("Тренировки")
        Spacer(Modifier.size(12.dp))
        if (sessions.isEmpty()) {
            EmptySectionHint()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sessions.forEach { session ->
                    SessionRow(session = session, onClick = { onOpenSession(session.id) })
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: StudentSession, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget + 16.dp)
            .clickable(onClick = onClick)
            .background(SurfaceLow, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Бейдж номера сессии (веб: квадрат bg-surface-card, primary, font-black).
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(SurfaceCard, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = session.sessionNumber?.toString() ?: "—",
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionWeekdayTime(session) ?: "Сессия ${session.sessionNumber ?: ""}".trim(),
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sessionDateLine(session),
                color = OnSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Шеврон вправо — глифом, без icon-пакета (как в B4).
        Text("›", color = OnSurfaceVariant.copy(alpha = 0.4f), fontSize = 22.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = OnSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Пустая секция: веб показывает «—» в карточке. */
@Composable
private fun EmptySectionHint() {
    Text(
        text = "—",
        color = OnSurfaceVariant,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
    )
}

@Composable
private fun EmptyState() {
    Text(text = "Профиль недоступен", color = OnSurfaceVariant, fontSize = 14.sp)
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Text(text = message, color = ErrorColor, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onRetry) {
            Text("Повторить", color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// --- Форматирование (как в вебе: UTC, ru-RU) ---

/** Первая строка строки сессии: «Понедельник 14:00» (веб: weekday + time, UTC). */
private fun sessionWeekdayTime(session: StudentSession): String? {
    val dt = parseUtc(session.scheduledAt ?: session.createdAt) ?: return null
    val weekday = dt.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, RuLocale)
        .replaceFirstChar { it.uppercase(RuLocale) }
    val time = dt.format(TIME_FMT)
    return "$weekday $time"
}

/** Вторая строка: «5 июня» + «· Завершено» для completed (веб: day month + completed). */
private fun sessionDateLine(session: StudentSession): String {
    val dt = parseUtc(session.scheduledAt ?: session.createdAt)
    val date = dt?.format(DATE_FMT).orEmpty()
    return if (session.status == "completed") {
        if (date.isBlank()) "Завершено" else "$date · Завершено"
    } else {
        date.ifBlank { "—" }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RuLocale)
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RuLocale)

/**
 * Парсит ISO-строку времени в UTC. Сервер отдаёт timestamp/дату в разных формах
 * (с зоной, с Z, без зоны или просто дата) — приводим всё к UTC, как делает веб
 * (`timeZone: "UTC"`). Невалидное значение → null (строку не покажем).
 */
private fun parseUtc(raw: String?): OffsetDateTime? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC) }
        .recoverCatching {
            java.time.LocalDateTime.parse(value).atOffset(ZoneOffset.UTC)
        }
        .recoverCatching {
            java.time.LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC)
        }
        .getOrNull()
}

/** Инициалы из полного имени (первые буквы первых двух слов), как в вебе/B4. */
private fun initials(fullName: String?): String {
    val parts = fullName?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    if (parts.isEmpty()) return "??"
    return parts.take(2).joinToString("") { it.first().uppercase() }
}

// --- Previews ---

private val previewProfile = StudentProfile(
    id = "1",
    fullName = "Иван Петров",
    email = "ivan@x.io",
    avatarUrl = null,
    goals = listOf(
        Goal("g1", "Стабильный приём слева под давлением", "active", null),
        Goal("g2", "Контроль глубины на форхенде", "active", null),
    ),
    sessions = listOf(
        StudentSession("s1", "g1", 3, "completed", "2026-06-02T14:00:00Z", "2026-06-02T15:00:00Z", null),
        StudentSession("s2", "g1", 4, "planned", "2026-06-09T18:30:00Z", null, null),
    ),
    masterPlan = MasterPlan(
        id = "p1",
        sections = listOf(
            MasterPlanSection("sec1", "Техника удара", "technique", listOf(
                MasterPlanItem("i1", "Хват", null, null),
                MasterPlanItem("i2", "Замах", null, null),
            )),
            MasterPlanSection("sec2", "Тактика", "tactics", listOf(
                MasterPlanItem("i3", "Позиционирование", null, null),
            )),
        ),
    ),
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun StudentProfilePreview() {
    NivelTheme {
        StudentProfileContent(
            loading = false,
            error = null,
            profile = previewProfile,
            goalCreator = GoalCreatorState(),
            onBack = {},
            onOpenSession = {},
            onRetry = {},
            onAddGoal = {},
            onDismissGoalCreator = {},
            onCustomProblemChange = {},
            onSelectProblem = {},
            onRetryProblems = {},
            onSubmitGoal = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun StudentProfileEmptyPreview() {
    NivelTheme {
        StudentProfileContent(
            loading = false,
            error = null,
            profile = previewProfile.copy(goals = emptyList(), sessions = emptyList(), masterPlan = null),
            goalCreator = GoalCreatorState(),
            onBack = {},
            onOpenSession = {},
            onRetry = {},
            onAddGoal = {},
            onDismissGoalCreator = {},
            onCustomProblemChange = {},
            onSelectProblem = {},
            onRetryProblems = {},
            onSubmitGoal = {},
        )
    }
}
