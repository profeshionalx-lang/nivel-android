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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.nivel.trainer.domain.InviteStatus
import com.nivel.trainer.domain.MasterPlan
import com.nivel.trainer.domain.MasterPlanItem
import com.nivel.trainer.domain.MasterPlanSection
import com.nivel.trainer.domain.StudentInvite
import com.nivel.trainer.domain.StudentProfile
import com.nivel.trainer.domain.StudentSession
import com.nivel.trainer.ui.theme.NivelTheme
import kotlinx.coroutines.delay
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
private val SurfaceElevated = Color(0xFF262626)      // --surface-elevated (поле ввода / ссылка)
private val BorderDim = Color(0xFF2E2E2E)            // --border-dim
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
        editing = state.editing,
        inviteBusy = state.inviteBusy,
        actionError = state.actionError,
        onBack = onBack,
        onOpenSession = onOpenSession,
        onRetry = viewModel::refresh,
        onStartEdit = viewModel::startEdit,
        onEditName = viewModel::onEditNameChange,
        onEditAvatar = viewModel::onEditAvatarChange,
        onSaveEdit = viewModel::saveEdit,
        onCancelEdit = viewModel::cancelEdit,
        onRegenerate = viewModel::regenerateInvite,
        onRevoke = viewModel::revokeInvite,
        onDismissActionError = viewModel::dismissActionError,
        modifier = modifier,
    )
}

@Composable
private fun StudentProfileContent(
    loading: Boolean,
    error: String?,
    profile: StudentProfile?,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    editing: ProfileEditState? = null,
    inviteBusy: Boolean = false,
    actionError: String? = null,
    onStartEdit: () -> Unit = {},
    onEditName: (String) -> Unit = {},
    onEditAvatar: (String) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onRevoke: () -> Unit = {},
    onDismissActionError: () -> Unit = {},
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
                editing = editing,
                inviteBusy = inviteBusy,
                actionError = actionError,
                onOpenSession = onOpenSession,
                onStartEdit = onStartEdit,
                onEditName = onEditName,
                onEditAvatar = onEditAvatar,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                onRegenerate = onRegenerate,
                onRevoke = onRevoke,
                onDismissActionError = onDismissActionError,
            )

            else -> CenterBox { EmptyState() }
        }
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
    editing: ProfileEditState?,
    inviteBusy: Boolean,
    actionError: String?,
    onOpenSession: (String) -> Unit,
    onStartEdit: () -> Unit,
    onEditName: (String) -> Unit,
    onEditAvatar: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onRevoke: () -> Unit,
    onDismissActionError: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        actionError?.let { err ->
            item { ActionErrorBanner(message = err, onDismiss = onDismissActionError) }
        }
        // Приглашение ученика — выше профиля (как InviteBlock над DashboardView в вебе).
        profile.invite?.let { invite ->
            item { InviteSection(invite = invite, busy = inviteBusy, onRegenerate = onRegenerate, onRevoke = onRevoke) }
        }
        item {
            ProfileHeaderBlock(
                profile = profile,
                editing = editing,
                onStartEdit = onStartEdit,
                onEditName = onEditName,
                onEditAvatar = onEditAvatar,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
            )
        }
        profile.masterPlan?.let { plan ->
            if (plan.sections.isNotEmpty()) item { MasterPlanBlock(plan) }
        }
        item { GoalsSection(profile.goals) }
        item { SessionsSection(profile.sessions, onOpenSession) }
    }
}

/**
 * Шапка профиля (E3): просмотр (тап → правка) или инлайн-форма правки имени/аватара,
 * как `InlineProfileHeader` в вебе.
 */
@Composable
private fun ProfileHeaderBlock(
    profile: StudentProfile,
    editing: ProfileEditState?,
    onStartEdit: () -> Unit,
    onEditName: (String) -> Unit,
    onEditAvatar: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    if (editing != null) {
        ProfileEditForm(editing, onEditName, onEditAvatar, onSave, onCancel)
        return
    }
    // Просмотр: tap по строке → правка (веб: «Trainer admin · tap to edit»).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStartEdit)
            .padding(vertical = 4.dp),
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
                text = "Профиль · нажмите, чтобы изменить",
                color = OnSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
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
        // Глиф «карандаш» — намёк на правку (веб: material-icon edit).
        Text("✎", color = OnSurfaceVariant.copy(alpha = 0.4f), fontSize = 18.sp)
    }
}

/** Инлайн-форма правки: имя + URL аватара + Сохранить/Отмена (как в вебе). */
@Composable
private fun ProfileEditForm(
    editing: ProfileEditState,
    onEditName: (String) -> Unit,
    onEditAvatar: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileTextField(value = editing.fullName, onChange = onEditName, placeholder = "Имя и фамилия")
        ProfileTextField(value = editing.avatarUrl, onChange = onEditAvatar, placeholder = "URL аватара (необязательно)")

        editing.error?.let { err ->
            Text(text = err, color = ErrorColor, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSave,
                enabled = !editing.submitting,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TouchTarget),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = Primary.copy(alpha = 0.4f),
                    disabledContentColor = OnPrimary,
                ),
            ) {
                Text(if (editing.submitting) "Сохраняем…" else "Сохранить", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            TextButton(
                onClick = onCancel,
                enabled = !editing.submitting,
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

@Composable
private fun ProfileTextField(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = OnSurfaceVariant) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OnSurface,
            unfocusedTextColor = OnSurface,
            focusedContainerColor = SurfaceElevated,
            unfocusedContainerColor = SurfaceElevated,
            focusedBorderColor = Primary,
            unfocusedBorderColor = BorderDim,
            cursorColor = Primary,
        ),
    )
}

/** Баннер ошибки действия (перевыпуск/отзыв приглашения) с кнопкой закрытия. */
@Composable
private fun ActionErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ErrorColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("✕", color = ErrorColor, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Секция приглашения (порт `InviteBlock`/`InviteBlockClient`): бейдж статуса +
 * действия по статусу. none → создать ссылку; pending → ссылка + копировать +
 * перевыпустить/отозвать; claimed → дата принятия; revoked → только бейдж.
 */
@Composable
private fun InviteSection(
    invite: StudentInvite,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onRevoke: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .border(1.dp, BorderDim, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Приглашение ученика",
                color = OnSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            InviteBadge(invite.status)
        }

        when (invite.status) {
            InviteStatus.CLAIMED -> invite.claimedAt?.let { at ->
                Text(text = "Принято ${formatClaimedAt(at)}", color = OnSurfaceVariant, fontSize = 12.sp)
            }

            InviteStatus.PENDING -> {
                invite.claimUrl?.let { url -> InviteLinkRow(url) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineActionButton(text = "Перевыпустить", enabled = !busy, color = OnSurface, onClick = onRegenerate, modifier = Modifier.weight(1f))
                    OutlineActionButton(text = "Отозвать", enabled = !busy, color = ErrorColor, onClick = onRevoke, modifier = Modifier.weight(1f))
                }
            }

            // none / unknown — даём создать (перевыпустить) ссылку.
            InviteStatus.NONE, InviteStatus.UNKNOWN -> {
                Button(
                    onClick = onRegenerate,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary,
                        disabledContainerColor = Primary.copy(alpha = 0.4f),
                        disabledContentColor = OnPrimary,
                    ),
                ) {
                    Text(if (busy) "Создаём…" else "Создать ссылку-приглашение", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // revoked — как в вебе: только бейдж, без действий.
            InviteStatus.REVOKED -> Unit
        }
    }
}

@Composable
private fun InviteBadge(status: InviteStatus) {
    val (text, color) = when (status) {
        InviteStatus.NONE -> "Не отправлено" to OnSurfaceVariant
        InviteStatus.PENDING -> "Ожидает" to Secondary
        InviteStatus.CLAIMED -> "Принято" to Primary
        InviteStatus.REVOKED -> "Отозвано" to ErrorColor
        InviteStatus.UNKNOWN -> "Статус неизвестен" to OnSurfaceVariant
    }
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Строка claim-ссылки с кнопкой «Копировать» (как в вебе bg-surface-elevated + Copy). */
@Composable
private fun InviteLinkRow(url: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = url,
            color = OnSurface,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(url))
                copied = true
            },
            modifier = Modifier.heightIn(min = TouchTarget),
        ) {
            Text(if (copied) "✓" else "Копировать", color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/** Bordered-кнопка действия (перевыпустить/отозвать), цвет текста по семантике. */
@Composable
private fun OutlineActionButton(
    text: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(text, color = if (enabled) color else color.copy(alpha = 0.4f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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

/** Секция «Активные цели» — горизонтальная карусель карточек целей. */
@Composable
private fun GoalsSection(goals: List<Goal>) {
    Column {
        SectionTitle("Активные цели")
        Spacer(Modifier.size(12.dp))
        if (goals.isEmpty()) {
            EmptySectionHint()
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
private val CLAIMED_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", RuLocale)

/** Дата принятия приглашения (E3): «5 июня 2026, 14:30» в UTC; невалид → исходная строка. */
private fun formatClaimedAt(raw: String): String = parseUtc(raw)?.format(CLAIMED_FMT) ?: raw

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
    invite = StudentInvite(InviteStatus.PENDING, "https://nivel-five.vercel.app/invite/abc123", null),
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun StudentProfilePreview() {
    NivelTheme {
        StudentProfileContent(
            loading = false,
            error = null,
            profile = previewProfile,
            onBack = {},
            onOpenSession = {},
            onRetry = {},
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
            onBack = {},
            onOpenSession = {},
            onRetry = {},
        )
    }
}
