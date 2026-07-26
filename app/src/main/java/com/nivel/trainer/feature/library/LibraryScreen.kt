package com.nivel.trainer.feature.library

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.domain.LibraryItem
import com.nivel.trainer.ui.theme.NivelTheme

private val Background = Color(0xFF0E0E0E)
private val SurfaceCard = Color(0xFF1E1E1E)
private val SurfaceElevated = Color(0xFF262626)
private val Primary = Color(0xFFCAFD00)
private val OnPrimary = Color(0xFF000000)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)
private val BorderDim = Color(0xFF2E2E2E)
private val ErrorColor = Color(0xFFFF7351)

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/**
 * Экран «Библиотека» (E6, #77) — поиск и создание навыков/упражнений
 * (`GET/POST /api/v1/skills`/`/exercises`, NIVEL#225).
 *
 * У этого экрана нет действующего веб-эталона «один-в-один»: веб-страница
 * `src/app/trainer/library/page.tsx` в NIVEL — read-only список без поиска и
 * создания, без единой ссылки на неё в навигации веба (проверено — нигде не
 * используется). Issue #77 прямо просит поиск + создание под новые эндпоинты
 * NIVEL#225 — предыдущая попытка (issue #29, PR #68) упёрлась в тот же вопрос
 * и оставила его открытым для решения человеком; #77 — более новая постановка
 * с явным acceptance на поиск/создание, поэтому реализовано по ней.
 */
@Composable
fun LibraryScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryContent(
        state = state,
        onBack = onBack,
        onTabSelect = viewModel::selectTab,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::load,
        onCreateClick = viewModel::openCreateSheet,
        modifier = modifier,
    )

    val sheet = state.createSheet
    if (sheet !is CreateLibraryItemState.Closed) {
        CreateItemSheet(
            state = sheet,
            tab = state.tab,
            onDismiss = viewModel::closeCreateSheet,
            onNameRuChange = viewModel::onCreateNameRuChange,
            onNameEnChange = viewModel::onCreateNameEnChange,
            onSubmit = viewModel::submitCreate,
        )
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    onBack: () -> Unit,
    onTabSelect: (LibraryTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .imePadding(),
    ) {
        Header(onBack = onBack, onCreateClick = onCreateClick)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabButton("Навыки", state.tab == LibraryTab.SKILLS) { onTabSelect(LibraryTab.SKILLS) }
            TabButton("Упражнения", state.tab == LibraryTab.EXERCISES) { onTabSelect(LibraryTab.EXERCISES) }
        }

        SearchField(query = state.query, onQueryChange = onQueryChange)

        when {
            state.loading && state.items.isEmpty() && state.error == null -> CenterBox {
                CircularProgressIndicator(color = Primary)
            }

            state.error != null && state.items.isEmpty() -> CenterBox {
                ErrorState(message = state.error, onRetry = onRetry)
            }

            state.isEmpty -> CenterBox {
                val text = if (state.query.isBlank()) "Пока пусто." else "Ничего не найдено по «${state.query}»."
                Text(text = text, color = OnSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { item -> LibraryItemRow(item) }
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit, onCreateClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("‹", color = OnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.padding(start = 4.dp))
            Text(
                text = "Библиотека",
                color = Primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
            )
        }
        TextButton(onClick = onCreateClick, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("+ Добавить", color = Primary, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Primary else SurfaceCard
    val fg = if (selected) OnPrimary else OnSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Поиск…", color = OnSurfaceVariant) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
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

@Composable
private fun LibraryItemRow(item: LibraryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = item.nameRu, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (!item.nameEn.isNullOrBlank()) {
                Text(text = item.nameEn, color = OnSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Text(text = message, color = ErrorColor, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.padding(top = 16.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("Повторить", color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Bottom-sheet создания навыка/упражнения (mobile-first — не центрированный диалог). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateItemSheet(
    state: CreateLibraryItemState,
    tab: LibraryTab,
    onDismiss: () -> Unit,
    onNameRuChange: (String) -> Unit,
    onNameEnChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
    ) {
        if (state !is CreateLibraryItemState.Form) return@ModalBottomSheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val title = if (tab == LibraryTab.SKILLS) "Новый навык" else "Новое упражнение"
            Text(text = title, color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Black)

            OutlinedTextField(
                value = state.nameRu,
                onValueChange = onNameRuChange,
                singleLine = true,
                placeholder = { Text("Название", color = OnSurfaceVariant) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
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
            OutlinedTextField(
                value = state.nameEn,
                onValueChange = onNameEnChange,
                singleLine = true,
                placeholder = { Text("Название на английском (необязательно)", color = OnSurfaceVariant) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
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
                    enabled = !state.submitting && state.nameRu.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
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
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Отмена", color = OnSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- Previews ---

private val previewItems = listOf(
    LibraryItem(1, "Приём слева", "Backhand"),
    LibraryItem(2, "Смэш", "Smash"),
    LibraryItem(3, "Бандеха", null),
)

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun LibraryScreenPreview() {
    NivelTheme {
        LibraryContent(
            state = LibraryUiState(skills = previewItems, exercises = previewItems),
            onBack = {},
            onTabSelect = {},
            onQueryChange = {},
            onRetry = {},
            onCreateClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun LibraryScreenEmptyPreview() {
    NivelTheme {
        LibraryContent(
            state = LibraryUiState(query = "бэкхенд"),
            onBack = {},
            onTabSelect = {},
            onQueryChange = {},
            onRetry = {},
            onCreateClick = {},
        )
    }
}
