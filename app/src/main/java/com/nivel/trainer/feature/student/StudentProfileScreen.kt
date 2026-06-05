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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
        masterPlanState = state.masterPlan,
        masterPlanActions = remember(viewModel) {
            MasterPlanActions(
                onCreatePlan = viewModel::createMasterPlan,
                onStartAddSection = viewModel::startAddSection,
                onCancelAddSection = viewModel::cancelAddSection,
                onSectionTitleChange = viewModel::onNewSectionTitleChange,
                onSectionCategoryChange = viewModel::onNewSectionCategoryChange,
                onSubmitSection = viewModel::submitAddSection,
                onStartAddItem = viewModel::startAddItem,
                onCancelAddItem = viewModel::cancelAddItem,
                onItemTitleChange = viewModel::onNewItemTitleChange,
                onItemDescChange = viewModel::onNewItemDescChange,
                onItemImageChange = viewModel::onNewItemImageChange,
                onSubmitItem = viewModel::submitAddItem,
                onDeleteSection = viewModel::deleteSection,
                onDeleteItem = viewModel::deleteItem,
            )
        },
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
    masterPlanState: MasterPlanEditorState,
    masterPlanActions: MasterPlanActions,
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
                masterPlanState = masterPlanState,
                masterPlanActions = masterPlanActions,
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
    masterPlanState: MasterPlanEditorState,
    masterPlanActions: MasterPlanActions,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { ProfileHeaderBlock(profile) }
        // E5: редактор мастер-плана — показываем всегда (даже когда плана ещё нет),
        // как тренерский `MasterPlanEditor` в вебе (порядок: до целей).
        item { MasterPlanEditor(profile.masterPlan, masterPlanState, masterPlanActions) }
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

// --- E5 (#28): редактор мастер-плана (порт веб-`MasterPlanEditor`) ---

/** Категория секции мастер-плана: значение для API, локализованная метка, цвет-акцент. */
private data class PlanCategory(val value: String, val label: String, val color: Color)

// Один-в-один с вебом (`CATEGORY_LABELS`/`CATEGORY_*_CLASSES`), метки локализованы:
// strength→primary, technique→secondary, tactics→error, custom→on-surface-variant.
private val PlanCategories = listOf(
    PlanCategory("strength", "Сильные стороны", Primary),
    PlanCategory("technique", "Техника", Secondary),
    PlanCategory("tactics", "Тактика", ErrorColor),
    PlanCategory("custom", "Другое", OnSurfaceVariant),
)

private fun categoryOf(value: String?): PlanCategory =
    PlanCategories.firstOrNull { it.value == value } ?: PlanCategories.last() // неизвестная → «Другое»

/** Колбэки редактора мастер-плана (E5) — сгруппированы, чтобы не плодить параметры. */
data class MasterPlanActions(
    val onCreatePlan: () -> Unit,
    val onStartAddSection: () -> Unit,
    val onCancelAddSection: () -> Unit,
    val onSectionTitleChange: (String) -> Unit,
    val onSectionCategoryChange: (String) -> Unit,
    val onSubmitSection: (planId: String) -> Unit,
    val onStartAddItem: (sectionId: String) -> Unit,
    val onCancelAddItem: () -> Unit,
    val onItemTitleChange: (String) -> Unit,
    val onItemDescChange: (String) -> Unit,
    val onItemImageChange: (String) -> Unit,
    val onSubmitItem: (sectionId: String) -> Unit,
    val onDeleteSection: (sectionId: String) -> Unit,
    val onDeleteItem: (itemId: String) -> Unit,
)

/**
 * Редактор мастер-плана (E5, #28) — порт тренерского веб-`MasterPlanEditor`:
 * нет плана → «Создать мастер-план»; есть план → секции (с акцент-бордером по
 * категории, удалением, пунктами и добавлением пунктов) + «+ Секция».
 * Источник правды — сервер: каждая мутация перечитывает профиль.
 */
@Composable
private fun MasterPlanEditor(
    plan: MasterPlan?,
    state: MasterPlanEditorState,
    actions: MasterPlanActions,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionTitle("Мастер-план")
            if (plan != null) {
                Text(
                    text = "+ Секция",
                    color = Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !state.busy, onClick = actions.onStartAddSection)
                        .heightIn(min = TouchTarget)
                        .padding(horizontal = 8.dp)
                        .wrapContentHeight(),
                )
            }
        }
        Spacer(Modifier.size(12.dp))

        if (plan == null) {
            // Нет плана — карточка с кнопкой создания (как пустое состояние веба).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Мастер-плана пока нет",
                    color = OnSurfaceVariant,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                PrimaryButton(
                    text = "Создать мастер-план",
                    enabled = !state.busy,
                    onClick = actions.onCreatePlan,
                )
                state.error?.let { Text(it, color = ErrorColor, fontSize = 13.sp, textAlign = TextAlign.Center) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                plan.sections.forEach { section ->
                    MasterPlanSectionCard(section = section, state = state, actions = actions)
                }
            }
            state.error?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, color = ErrorColor, fontSize = 13.sp)
            }
            if (state.addingSection) {
                Spacer(Modifier.size(16.dp))
                AddSectionForm(planId = plan.id, state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun MasterPlanSectionCard(
    section: MasterPlanSection,
    state: MasterPlanEditorState,
    actions: MasterPlanActions,
) {
    val cat = categoryOf(section.category)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard),
    ) {
        // Верхний акцент-бордер по категории (как `border-t-[2px]` в вебе).
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(cat.color))
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cat.label.uppercase(RuLocale),
                        color = cat.color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = section.title,
                        color = OnSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Удалить секцию (с пунктами каскадом) — как корзина в вебе.
                Box(
                    modifier = Modifier
                        .size(TouchTarget)
                        .clip(CircleShape)
                        .alpha(if (state.busy) 0.4f else 1f)
                        .clickable(enabled = !state.busy) { actions.onDeleteSection(section.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🗑", fontSize = 16.sp)
                }
            }

            if (section.items.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.items.forEach { item ->
                        MasterPlanItemRow(item = item, state = state, actions = actions)
                    }
                }
            }

            Spacer(Modifier.size(12.dp))
            if (state.addingItemToSectionId == section.id) {
                AddItemForm(sectionId = section.id, state = state, actions = actions)
            } else {
                Text(
                    text = "+ Добавить пункт",
                    color = OnSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !state.busy) { actions.onStartAddItem(section.id) }
                        .heightIn(min = TouchTarget)
                        .padding(horizontal = 4.dp)
                        .wrapContentHeight(),
                )
            }
        }
    }
}

@Composable
private fun MasterPlanItemRow(
    item: MasterPlanItem,
    state: MasterPlanEditorState,
    actions: MasterPlanActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLow, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            item.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(2.dp))
                Text(it, color = OnSurfaceVariant, fontSize = 12.sp)
            }
            item.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Spacer(Modifier.size(8.dp))
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 192.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(TouchTarget)
                .clip(CircleShape)
                .alpha(if (state.busy) 0.4f else 1f)
                .clickable(enabled = !state.busy) { actions.onDeleteItem(item.id) },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = ErrorColor.copy(alpha = 0.7f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun AddSectionForm(
    planId: String,
    state: MasterPlanEditorState,
    actions: MasterPlanActions,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EditorField(
            value = state.newSectionTitle,
            onValueChange = actions.onSectionTitleChange,
            placeholder = "Название секции (напр. Техника воллея)",
            enabled = !state.busy,
        )
        CategoryPicker(
            value = state.newSectionCategory,
            enabled = !state.busy,
            onChange = actions.onSectionCategoryChange,
        )
        FormActions(
            submitLabel = "Добавить секцию",
            submitEnabled = !state.busy && state.newSectionTitle.isNotBlank(),
            onSubmit = { actions.onSubmitSection(planId) },
            onCancel = actions.onCancelAddSection,
            cancelEnabled = !state.busy,
        )
    }
}

@Composable
private fun AddItemForm(
    sectionId: String,
    state: MasterPlanEditorState,
    actions: MasterPlanActions,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLow, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EditorField(
            value = state.newItemTitle,
            onValueChange = actions.onItemTitleChange,
            placeholder = "Название пункта",
            enabled = !state.busy,
        )
        EditorField(
            value = state.newItemDesc,
            onValueChange = actions.onItemDescChange,
            placeholder = "Описание (необязательно)",
            enabled = !state.busy,
            minLines = 2,
        )
        EditorField(
            value = state.newItemImage,
            onValueChange = actions.onItemImageChange,
            placeholder = "URL картинки (необязательно)",
            enabled = !state.busy,
        )
        FormActions(
            submitLabel = "Добавить",
            submitEnabled = !state.busy && state.newItemTitle.isNotBlank(),
            onSubmit = { actions.onSubmitItem(sectionId) },
            onCancel = actions.onCancelAddItem,
            cancelEnabled = !state.busy,
        )
    }
}

/** Строка действий формы: «Добавить…» (primary, гейтится) + «Отмена». */
@Composable
private fun FormActions(
    submitLabel: String,
    submitEnabled: Boolean,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    cancelEnabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = submitLabel,
            color = if (submitEnabled) Primary else Primary.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = submitEnabled, onClick = onSubmit)
                .heightIn(min = TouchTarget)
                .padding(horizontal = 4.dp)
                .wrapContentHeight(),
        )
        Text(
            text = "Отмена",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = cancelEnabled, onClick = onCancel)
                .heightIn(min = TouchTarget)
                .padding(horizontal = 4.dp)
                .wrapContentHeight(),
        )
    }
}

/** Пикер категории секции — выпадающее меню (нативный аналог `<select>` веба). */
@Composable
private fun CategoryPicker(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val cat = categoryOf(value)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceLow)
                .border(1.dp, OnSurfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(8.dp).background(cat.color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(cat.label, color = OnSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("▾", color = OnSurfaceVariant, fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlanCategories.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(c.color, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(c.label, color = OnSurface)
                        }
                    },
                    onClick = {
                        onChange(c.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Поле ввода редактора (на тёмном контейнере, лаконичный бордер). */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = OnSurfaceVariant.copy(alpha = 0.5f), fontSize = 13.sp) },
        enabled = enabled,
        minLines = minLines,
        singleLine = minLines == 1,
        textStyle = androidx.compose.ui.text.TextStyle(color = OnSurface, fontSize = 14.sp),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = OnSurfaceVariant.copy(alpha = 0.25f),
            cursorColor = Primary,
            focusedContainerColor = Background,
            unfocusedContainerColor = Background,
        ),
    )
}

/** Лаймовая кнопка-действие (как `kinetic-gradient` веба, без градиента). */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Primary else Primary.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Black)
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

private val previewMasterPlanActions = MasterPlanActions(
    onCreatePlan = {},
    onStartAddSection = {},
    onCancelAddSection = {},
    onSectionTitleChange = {},
    onSectionCategoryChange = {},
    onSubmitSection = {},
    onStartAddItem = {},
    onCancelAddItem = {},
    onItemTitleChange = {},
    onItemDescChange = {},
    onItemImageChange = {},
    onSubmitItem = {},
    onDeleteSection = {},
    onDeleteItem = {},
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
            masterPlanState = MasterPlanEditorState(),
            masterPlanActions = previewMasterPlanActions,
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
            masterPlanState = MasterPlanEditorState(),
            masterPlanActions = previewMasterPlanActions,
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
