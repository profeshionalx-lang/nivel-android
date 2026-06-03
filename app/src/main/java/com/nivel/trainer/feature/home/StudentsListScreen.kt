package com.nivel.trainer.feature.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.domain.ShadowStudent
import com.nivel.trainer.domain.Student
import com.nivel.trainer.ui.theme.NivelTheme

// Цвета взяты один-в-один из веб-Nivel (src/app/globals.css), как и на экране входа (B2).
// Глобальная Material-тема доводится в G4; здесь фиксируем точные значения экрана,
// чтобы он совпадал с вебом (`src/app/trainer/students/page.tsx`).
private val Background = Color(0xFF0E0E0E)            // --background
private val SurfaceLow = Color(0xFF161616)           // --surface-low (карточка ученика)
private val SurfaceCard = Color(0xFF1E1E1E)          // --surface-card (sheet / аватар-бэкграунд)
private val SurfaceElevated = Color(0xFF262626)      // --surface-elevated (поле ввода / ссылка)
private val Primary = Color(0xFFCAFD00)              // --primary (лайм)
private val OnPrimary = Color(0xFF000000)            // text на primary
private val Secondary = Color(0xFF7CC6FE)            // --secondary (счётчик сессий)
private val OnSurface = Color(0xFFF5F5F5)            // --on-surface
private val OnSurfaceVariant = Color(0xFFADAAAA)     // --on-surface-variant
private val BorderDim = Color(0xFF2E2E2E)            // --border-dim
private val ErrorColor = Color(0xFFFF7351)           // --error

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/**
 * Экран «Ученики» (B4) — порт веб-страницы `src/app/trainer/students/page.tsx`
 * один-в-один: header «Ученики» + кнопка создания, список карточек ученика
 * (аватар-инициалы, имя, «N целей / M сессий», шеврон), состояния
 * загрузки/пусто/ошибка. Создание теневого ученика и показ claim-ссылки —
 * в bottom-sheet (mobile-first), шаринг через системный Android share Intent.
 */
@Composable
fun StudentsListScreen(
    onOpenStudent: (String) -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: StudentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    StudentsListContent(
        students = state.students,
        refreshing = state.refreshing,
        error = state.error,
        isEmpty = state.isEmpty,
        onOpenStudent = onOpenStudent,
        onClose = onClose,
        onCreateClick = viewModel::openCreateSheet,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )

    val sheet = state.createSheet
    if (sheet !is CreateSheetState.Closed) {
        CreateStudentSheet(
            state = sheet,
            onDismiss = viewModel::closeCreateSheet,
            onNameChange = viewModel::onCreateNameChange,
            onSubmit = viewModel::submitCreate,
        )
    }
}

@Composable
private fun StudentsListContent(
    students: List<Student>,
    refreshing: Boolean,
    error: String?,
    isEmpty: Boolean,
    onOpenStudent: (String) -> Unit,
    onClose: () -> Unit,
    onCreateClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        // Header — «Ученики», кнопка создания, закрытие (как glass-nav в вебе).
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
                text = "Ученики",
                color = Primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCreateClick,
                    modifier = Modifier.heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Ученик", fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(TouchTarget)) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = OnSurfaceVariant)
                }
            }
        }

        when {
            // Спиннер только при пустом кэше — иначе показываем кэш мгновенно.
            refreshing && students.isEmpty() && error == null -> CenterBox {
                CircularProgressIndicator(color = Primary)
            }

            error != null && students.isEmpty() -> CenterBox {
                ErrorState(message = error, onRetry = onRetry)
            }

            isEmpty -> CenterBox { EmptyState() }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(students, key = { it.id }) { student ->
                    StudentRow(student = student, onClick = { onOpenStudent(student.id) })
                }
            }
        }
    }
}

@Composable
private fun StudentRow(student: Student, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget + 16.dp)
            .clickable(onClick = onClick)
            .background(SurfaceLow, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Аватар-инициалы (веб: круг с border primary/30, инициалы из full_name).
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(SurfaceCard, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials(student.fullName),
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = student.fullName?.takeIf { it.isNotBlank() }
                    ?: student.email?.takeIf { it.isNotBlank() }
                    ?: "Без имени",
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CountLabel(value = student.activeGoals, suffix = "целей", valueColor = Primary)
                CountLabel(value = student.totalSessions, suffix = "сессий", valueColor = Secondary)
            }
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = OnSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun CountLabel(value: Int, suffix: String, valueColor: Color) {
    Row {
        Text(
            text = "$value ",
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = suffix, color = OnSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun EmptyState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = OnSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(text = "Учеников пока нет", color = OnSurfaceVariant, fontSize = 14.sp)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Text(
            text = message,
            color = ErrorColor,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Повторить", color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateStudentSheet(
    state: CreateSheetState,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
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
            when (state) {
                is CreateSheetState.Form -> CreateForm(state, onNameChange, onSubmit, onDismiss)
                is CreateSheetState.Created -> CreatedShare(state.shadow, onDismiss)
                CreateSheetState.Closed -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateForm(
    state: CreateSheetState.Form,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text(text = "Новый ученик", color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Black)

    OutlinedTextField(
        value = state.fullName,
        onValueChange = onNameChange,
        singleLine = true,
        placeholder = { Text("Имя и фамилия", color = OnSurfaceVariant) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
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

    if (state.error != null) {
        Text(text = state.error, color = ErrorColor, fontSize = 13.sp)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onSubmit,
            enabled = !state.submitting && state.fullName.isNotBlank(),
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
            Text(if (state.submitting) "Создаём…" else "Создать", fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = onDismiss,
            enabled = !state.submitting,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TouchTarget),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Отмена", color = OnSurface, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CreatedShare(shadow: ShadowStudent, onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Text(text = "Ученик создан", color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Black)
    Text(
        text = "Отправьте ученику ссылку — по ней он привяжет свой профиль:",
        color = OnSurfaceVariant,
        fontSize = 13.sp,
    )

    // Блок со ссылкой + «Копировать» (как в вебе: bg-surface-elevated, code, copy).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = shadow.claimUrl,
            color = OnSurface,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(shadow.claimUrl)) },
            modifier = Modifier.heightIn(min = TouchTarget),
        ) {
            Text("Копировать", color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }

    // Поделиться — системный Android share Intent (неизбежно-нативное).
    Button(
        onClick = { shareLink(context, shadow.claimUrl) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
    ) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Поделиться ссылкой", fontWeight = FontWeight.Bold)
    }

    TextButton(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget),
    ) {
        Text("Готово", color = OnSurface, fontWeight = FontWeight.Bold)
    }
}

/** Android share Intent — отдаём ссылку в системный шаринг (мессенджеры/почта). */
private fun shareLink(context: android.content.Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, "Поделиться ссылкой"))
}

/** Инициалы из полного имени (первые буквы первых двух слов), как в вебе. */
private fun initials(fullName: String?): String {
    val parts = fullName?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    if (parts.isEmpty()) return "??"
    return parts.take(2).joinToString("") { it.first().uppercase() }
}

// --- Previews ---

private val previewStudents = listOf(
    Student("1", "Иван Петров", "ivan@x.io", null, activeGoals = 2, totalSessions = 5),
    Student("2", "Анна", null, null, activeGoals = 0, totalSessions = 1),
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun StudentsListPreview() {
    NivelTheme {
        StudentsListContent(
            students = previewStudents,
            refreshing = false,
            error = null,
            isEmpty = false,
            onOpenStudent = {},
            onClose = {},
            onCreateClick = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun StudentsEmptyPreview() {
    NivelTheme {
        StudentsListContent(
            students = emptyList(),
            refreshing = false,
            error = null,
            isEmpty = true,
            onOpenStudent = {},
            onClose = {},
            onCreateClick = {},
            onRetry = {},
        )
    }
}
