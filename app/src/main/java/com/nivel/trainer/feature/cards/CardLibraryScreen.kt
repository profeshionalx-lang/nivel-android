package com.nivel.trainer.feature.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.domain.CardCollection
import com.nivel.trainer.domain.CardLibraryStudent
import com.nivel.trainer.domain.CardTemplate
import com.nivel.trainer.domain.StudentSession

// Цвета один-в-один из веб-Nivel (globals.css), как на других экранах (B4/B5).
private val Background = Color(0xFF0E0E0E)
private val SurfaceLow = Color(0xFF161616)
private val SurfaceCard = Color(0xFF1E1E1E)
private val SurfaceElevated = Color(0xFF262626)
private val Primary = Color(0xFFCAFD00)
private val OnPrimary = Color(0xFF000000)
private val Secondary = Color(0xFF7CC6FE)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)
private val BorderDim = Color(0xFF2E2E2E)
private val ErrorColor = Color(0xFFFF7351)
private val Approved = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)

private val TouchTarget = 48.dp

// Темы карточек (как в вебе): порядок и набор фиксированы.
private val TAGS = listOf("техника", "тактика", "физика", "менталка")

// Цвета акцента тегов — приближённо к web TAG_COLORS.
private val TAG_ACCENT = mapOf(
    "техника" to Secondary,
    "тактика" to Amber,
    "физика" to Approved,
    "менталка" to Color(0xFFC084FC),
)

private data class StatusOption(val value: String, val label: String)

private val STATUS_OPTIONS = listOf(
    StatusOption("all", "Все статусы"),
    StatusOption("approved", "Approved"),
    StatusOption("draft", "Draft"),
    StatusOption("rejected", "Rejected"),
)

/**
 * Экран библиотеки карточек-шаблонов (E4, #27) — порт веб-страницы `trainer/cards`
 * (`CardsLibrary`, мобильная раскладка): вкладки Cards/Collections, поиск, фильтры
 * тем/статусов, флип-карточки (лицо: теги+статус+заголовок, оборот: разбор+цитата),
 * ведение коллекций и применение шаблона/коллекции к сессии ученика.
 */
@Composable
fun CardLibraryScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CardLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CardLibraryContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onSelectTab = viewModel::selectTab,
        onSearchChange = viewModel::onSearchChange,
        onTagFilter = viewModel::onTagFilter,
        onStatusFilter = viewModel::onStatusFilter,
        onResetFilters = viewModel::resetFilters,
        onDismissActionError = viewModel::dismissActionError,
        onNewCollectionNameChange = viewModel::onNewCollectionNameChange,
        onCreateCollection = viewModel::createCollection,
        onOpenCollectionPicker = viewModel::openCollectionPicker,
        onApplyTemplate = viewModel::startApplyTemplate,
        onApplyCollection = viewModel::startApplyCollection,
        onRemoveCardFromCollection = viewModel::removeCardFromCollection,
        modifier = modifier,
    )

    if (state.applySheet.visible) {
        ApplyBottomSheet(
            state = state.applySheet,
            students = state.students,
            onDismiss = viewModel::dismissApplySheet,
            onSelectStudent = viewModel::selectStudentForApply,
            onResetStudent = viewModel::resetApplyStudent,
            onApplyToSession = viewModel::applyToSession,
        )
    }

    if (state.collectionPicker.visible) {
        CollectionPickerSheet(
            picker = state.collectionPicker,
            collections = state.collections,
            onToggle = viewModel::toggleCardInCollection,
            onDismiss = viewModel::dismissCollectionPicker,
        )
    }
}

@Composable
private fun CardLibraryContent(
    state: CardLibraryUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectTab: (CardTab) -> Unit,
    onSearchChange: (String) -> Unit,
    onTagFilter: (String?) -> Unit,
    onStatusFilter: (String) -> Unit,
    onResetFilters: () -> Unit,
    onDismissActionError: () -> Unit,
    onNewCollectionNameChange: (String) -> Unit,
    onCreateCollection: () -> Unit,
    onOpenCollectionPicker: (CardTemplate) -> Unit,
    onApplyTemplate: (CardTemplate) -> Unit,
    onApplyCollection: (CardCollection) -> Unit,
    onRemoveCardFromCollection: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Header(
            templatesCount = state.templates.size,
            collectionsCount = state.collections.size,
            tab = state.tab,
            onBack = onBack,
            onSelectTab = onSelectTab,
        )

        if (state.actionError != null) {
            ActionErrorBanner(message = state.actionError, onDismiss = onDismissActionError)
        }

        when {
            state.loading && state.templates.isEmpty() && state.collections.isEmpty() && state.error == null ->
                CenterBox { CircularProgressIndicator(color = Primary) }

            state.error != null && state.templates.isEmpty() && state.collections.isEmpty() ->
                CenterBox { ErrorState(message = state.error, onRetry = onRetry) }

            state.tab == CardTab.CARDS -> CardsTab(
                state = state,
                onSearchChange = onSearchChange,
                onTagFilter = onTagFilter,
                onStatusFilter = onStatusFilter,
                onResetFilters = onResetFilters,
                onOpenCollectionPicker = onOpenCollectionPicker,
                onApplyTemplate = onApplyTemplate,
                hasCollections = state.collections.isNotEmpty(),
            )

            else -> CollectionsTab(
                state = state,
                onNewCollectionNameChange = onNewCollectionNameChange,
                onCreateCollection = onCreateCollection,
                onApplyCollection = onApplyCollection,
                onRemoveCardFromCollection = onRemoveCardFromCollection,
            )
        }
    }
}

@Composable
private fun Header(
    templatesCount: Int,
    collectionsCount: Int,
    tab: CardTab,
    onBack: () -> Unit,
    onSelectTab: (CardTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(TouchTarget)) {
                Text("‹", color = OnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Card Library",
                color = Primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // Сегмент-табы Cards / Collections (как мобильные табы в вебе).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceLow, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SegmentTab(
                label = "Cards · $templatesCount",
                selected = tab == CardTab.CARDS,
                onClick = { onSelectTab(CardTab.CARDS) },
                modifier = Modifier.weight(1f),
            )
            SegmentTab(
                label = "Collections · $collectionsCount",
                selected = tab == CardTab.COLLECTIONS,
                onClick = { onSelectTab(CardTab.COLLECTIONS) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SegmentTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .background(if (selected) SurfaceCard else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) OnSurface else OnSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ───────────────────────── Cards tab ─────────────────────────

@Composable
private fun CardsTab(
    state: CardLibraryUiState,
    onSearchChange: (String) -> Unit,
    onTagFilter: (String?) -> Unit,
    onStatusFilter: (String) -> Unit,
    onResetFilters: () -> Unit,
    onOpenCollectionPicker: (CardTemplate) -> Unit,
    onApplyTemplate: (CardTemplate) -> Unit,
    hasCollections: Boolean,
) {
    val filtered = state.filteredTemplates

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "search") {
            SearchField(value = state.search, onChange = onSearchChange)
        }
        item(key = "tag-chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(label = "Все", selected = state.tagFilter == null, onClick = { onTagFilter(null) })
                TAGS.forEach { tag ->
                    FilterChip(
                        label = tag,
                        selected = state.tagFilter == tag,
                        onClick = { onTagFilter(if (state.tagFilter == tag) null else tag) },
                    )
                }
            }
        }
        item(key = "status-chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                STATUS_OPTIONS.forEach { opt ->
                    FilterChip(
                        label = opt.label,
                        selected = state.statusFilter == opt.value,
                        onClick = { onStatusFilter(opt.value) },
                    )
                }
            }
        }
        item(key = "summary") {
            SummaryBar(
                shown = filtered.size,
                total = state.templates.size,
                hasFilters = state.hasFilters,
                onReset = onResetFilters,
            )
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                CardsEmptyState(hasFilters = state.hasFilters, onReset = onResetFilters)
            }
        } else {
            items(filtered, key = { it.key }) { template ->
                LibraryCard(
                    template = template,
                    hasCollections = hasCollections,
                    onOpenCollections = { onOpenCollectionPicker(template) },
                    onAssign = { onApplyTemplate(template) },
                )
            }
        }
    }
}

@Composable
private fun SummaryBar(shown: Int, total: Int, hasFilters: Boolean, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row {
            Text("$shown ", color = OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (shown == total) "карточек" else "из $total карточек",
                color = OnSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        if (hasFilters) {
            TextButton(onClick = onReset, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Сбросить", color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Карточка библиотеки — порт web `LibraryCardItem`: флип-карточка (тап по контенту
 * переворачивает между лицом и оборотом), футер со счётчиком учеников + bookmark
 * (коллекции) + Assign — футер не переворачивается (как в вебе).
 */
@Composable
private fun LibraryCard(
    template: CardTemplate,
    hasCollections: Boolean,
    onOpenCollections: () -> Unit,
    onAssign: () -> Unit,
) {
    var flipped by remember(template.key) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "card-flip",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLow, RoundedCornerShape(16.dp)),
    ) {
        // Переворачивающаяся область (контент). Тап = флип.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density
                }
                .clickable { flipped = !flipped }
                .padding(16.dp),
        ) {
            if (rotation <= 90f) {
                CardFront(template)
            } else {
                Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                    CardBack(template)
                }
            }
        }

        // Футер — действия, не переворачивается.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("👤", fontSize = 13.sp)
                Text(
                    text = template.studentCount.toString(),
                    color = OnSurfaceVariant,
                    fontSize = 12.sp,
                )
            }

            if (hasCollections) {
                IconButton(onClick = onOpenCollections, modifier = Modifier.size(40.dp)) {
                    Text("🔖", fontSize = 16.sp)
                }
            }

            Button(
                onClick = onAssign,
                modifier = Modifier.heightIn(min = 40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            ) {
                Text("Assign", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CardFront(template: CardTemplate) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            template.tags.firstOrNull()?.let { TagBadge(it) }
            Spacer(Modifier.weight(1f))
            StatusLabel(template.trainerStatus)
        }
        Text(
            text = template.title ?: "—",
            color = OnSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun CardBack(template: CardTemplate) {
    val body = template.body.orEmpty()
    val quote = template.quote.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "РАЗБОР",
            color = OnSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        if (body.isNotBlank()) {
            Text(text = body, color = OnSurface, fontSize = 13.sp)
        }
        if (quote.isNotBlank()) {
            Text(
                text = "«$quote»",
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
            )
        }
        if (body.isBlank() && quote.isBlank()) {
            Text(text = "Описание не добавлено.", color = OnSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TagBadge(tag: String) {
    val accent = TAG_ACCENT[tag] ?: OnSurfaceVariant
    Text(
        text = tag,
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusLabel(status: String) {
    val (label, color) = when (status) {
        "approved" -> "Approved" to Approved
        "draft" -> "Draft" to Amber
        "rejected" -> "Rejected" to ErrorColor
        else -> return
    }
    Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CardsEmptyState(hasFilters: Boolean, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (hasFilters) "🔍" else "🗂", fontSize = 40.sp)
        Spacer(Modifier.size(12.dp))
        Text(
            text = if (hasFilters) "Ничего не найдено" else "Библиотека пуста",
            color = OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (hasFilters) "Сбросьте фильтры или измените запрос." else "Созданные карточки появятся здесь.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        if (hasFilters) {
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = onReset, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Сбросить фильтры", color = Primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ───────────────────────── Collections tab ─────────────────────────

@Composable
private fun CollectionsTab(
    state: CardLibraryUiState,
    onNewCollectionNameChange: (String) -> Unit,
    onCreateCollection: () -> Unit,
    onApplyCollection: (CardCollection) -> Unit,
    onRemoveCardFromCollection: (String, String) -> Unit,
) {
    // Карта ключ→заголовок для показа имён карточек внутри коллекции.
    val titleByKey = remember(state.templates) {
        state.templates.associate { it.key to (it.title ?: "—") }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "create") {
            CreateCollectionRow(
                name = state.newCollectionName,
                onNameChange = onNewCollectionNameChange,
                onCreate = onCreateCollection,
            )
        }

        if (state.collections.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("📑", fontSize = 40.sp)
                    Spacer(Modifier.size(12.dp))
                    Text("Коллекций пока нет", color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "Группируйте карточки в коллекции, чтобы применять их пачкой.",
                        color = OnSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(state.collections, key = { it.id }) { collection ->
                CollectionItem(
                    collection = collection,
                    titleByKey = titleByKey,
                    onAssign = { onApplyCollection(collection) },
                    onRemoveCard = { key -> onRemoveCardFromCollection(collection.id, key) },
                )
            }
        }
    }
}

@Composable
private fun CreateCollectionRow(
    name: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "НОВАЯ КОЛЛЕКЦИЯ",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                placeholder = { Text("Напр. Основы для новичков", color = OnSurfaceVariant) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreate() }),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TouchTarget),
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors(),
            )
            Button(
                onClick = onCreate,
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = TouchTarget),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = Primary.copy(alpha = 0.4f),
                    disabledContentColor = OnPrimary,
                ),
            ) {
                Text("Создать", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun CollectionItem(
    collection: CardCollection,
    titleByKey: Map<String, String>,
    onAssign: () -> Unit,
    onRemoveCard: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLow, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.name,
                    color = OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${collection.cardCount} карточек",
                    color = OnSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Button(
                onClick = onAssign,
                enabled = collection.templateIds.isNotEmpty(),
                modifier = Modifier.heightIn(min = 40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = Primary.copy(alpha = 0.4f),
                    disabledContentColor = OnPrimary,
                ),
            ) {
                Text("Assign", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        collection.templateIds.forEach { key ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = titleByKey[key] ?: "Карточка",
                    color = OnSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemoveCard(key) }, modifier = Modifier.size(40.dp)) {
                    Text("✕", color = OnSurfaceVariant, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

// ───────────────────────── Apply sheet ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplyBottomSheet(
    state: ApplySheetState,
    students: List<CardLibraryStudent>,
    onDismiss: () -> Unit,
    onSelectStudent: (CardLibraryStudent) -> Unit,
    onResetStudent: () -> Unit,
    onApplyToSession: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SurfaceCard) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val heading = when {
                state.successMessage != null -> "Готово"
                state.selectedStudent == null -> "Выбери ученика"
                else -> "Выбери сессию — ${state.selectedStudent.fullName ?: "—"}"
            }
            Text(text = heading, color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Black)

            state.title?.let {
                Text(
                    text = it,
                    color = OnSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceElevated, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            when {
                state.successMessage != null -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("✓", color = Primary, fontSize = 40.sp)
                        Text(state.successMessage, color = OnSurface, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = TouchTarget),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                        ) {
                            Text("Готово", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                state.selectedStudent == null -> {
                    if (students.isEmpty()) {
                        Text("Учеников пока нет", color = OnSurfaceVariant, fontSize = 13.sp)
                    }
                    students.forEach { student ->
                        PickRow(
                            title = student.fullName ?: "—",
                            onClick = { onSelectStudent(student) },
                        )
                    }
                }

                state.loadingSessions -> CenterBox(minHeight = 120.dp) {
                    CircularProgressIndicator(color = Primary)
                }

                else -> {
                    TextButton(onClick = onResetStudent, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text("‹ Другой ученик", color = Secondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    if (state.sessions.isEmpty()) {
                        Text("У ученика нет сессий", color = OnSurfaceVariant, fontSize = 13.sp)
                    }
                    state.sessions.forEach { session ->
                        PickRow(
                            title = sessionLabel(session),
                            enabled = !state.submitting,
                            onClick = { onApplyToSession(session.id) },
                        )
                    }
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    color = ErrorColor,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ErrorColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun PickRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(SurfaceElevated, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("›", color = OnSurfaceVariant, fontSize = 20.sp)
    }
}

// ───────────────────────── Collection picker sheet ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionPickerSheet(
    picker: CollectionPickerState,
    collections: List<CardCollection>,
    onToggle: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SurfaceCard) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Коллекции", color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Black)
            picker.templateTitle?.let {
                Text(
                    text = it,
                    color = OnSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(4.dp))
            collections.forEach { collection ->
                val inCollection = collection.templateIds.contains(picker.templateKey)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget)
                        .clickable { onToggle(collection.id, picker.templateKey) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (inCollection) "☑" else "☐", color = if (inCollection) Primary else OnSurfaceVariant, fontSize = 18.sp)
                    Text(
                        text = collection.name,
                        color = OnSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ───────────────────────── Shared bits ─────────────────────────

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Поиск карточек…", color = OnSurfaceVariant) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
        colors = textFieldColors(),
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .background(
                if (selected) Primary else SurfaceElevated,
                RoundedCornerShape(8.dp),
            )
            .then(
                if (selected) Modifier else Modifier.border(1.dp, BorderDim, RoundedCornerShape(8.dp)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) OnPrimary else OnSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActionErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(ErrorColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("Скрыть", color = OnSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text("Повторить", color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CenterBox(minHeight: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (minHeight > 0.dp) Modifier.heightIn(min = minHeight) else Modifier.fillMaxSize()),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface,
    focusedContainerColor = SurfaceElevated,
    unfocusedContainerColor = SurfaceElevated,
    focusedBorderColor = Primary,
    unfocusedBorderColor = BorderDim,
    cursorColor = Primary,
)

/** Подпись сессии в шите применения (как «Сессия N — заметки / дата» в вебе). */
private fun sessionLabel(session: StudentSession): String {
    val number = session.sessionNumber?.let { "Сессия $it" } ?: "Сессия"
    val date = (session.scheduledAt ?: session.completedAt ?: session.createdAt)
        ?.take(10)
    return if (date != null) "$number · $date" else number
}
