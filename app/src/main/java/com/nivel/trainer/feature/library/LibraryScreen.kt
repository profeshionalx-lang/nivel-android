package com.nivel.trainer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.domain.ExerciseRef
import com.nivel.trainer.domain.Library
import com.nivel.trainer.domain.SkillRef
import com.nivel.trainer.ui.state.FullScreenError
import com.nivel.trainer.ui.state.LoadingState

// Палитра — один-в-один из веб-Nivel (`globals.css`), как в остальных feature-экранах.
private val Background = Color(0xFF0E0E0E)
private val SurfaceCard = Color(0xFF1E1E1E)
private val Primary = Color(0xFFCAFD00)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)

/**
 * Экран «Library» (E6, #29) — read-only справочник навыков и упражнений тренера.
 * Порт веб-страницы `src/app/trainer/library/page.tsx` один-в-один: секции
 * «Exercises (N)» (список) и «Skills (N)» (чипы), те же тексты и пустые состояния.
 *
 * Источник — `GET /api/v1/reference`. На этом экране, как и в вебе, нет поиска и
 * создания: навыки/упражнения заводятся при добавлении сессий ("…created as
 * sessions are added").
 */
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        LibraryHeader(onBack = onBack)

        when {
            state.library == null && state.loading -> LoadingState()
            state.library == null && state.error != null ->
                FullScreenError(error = state.error, onRetry = viewModel::load)
            else -> LibraryContent(library = state.library ?: Library(emptyList(), emptyList()))
        }
    }
}

@Composable
private fun LibraryHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", color = OnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "Library",
            color = Primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .weight(1f)
                .padding(end = 48.dp),
        )
    }
}

@Composable
private fun LibraryContent(library: Library) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Exercises
        item {
            SectionHeader("Exercises (${library.exercises.size})")
            Spacer(Modifier.height(16.dp))
            if (library.exercises.isEmpty()) {
                EmptyHint("Exercises are created as sessions are added")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    library.exercises.forEach { ExerciseRow(it) }
                }
            }
        }

        // Skills
        item {
            SectionHeader("Skills (${library.skills.size})")
            Spacer(Modifier.height(16.dp))
            if (library.skills.isEmpty()) {
                EmptyHint("Skills are created as sessions are added")
            } else {
                SkillChips(library.skills)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = OnSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.4.sp,
    )
}

@Composable
private fun ExerciseRow(exercise: ExerciseRef) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = exercise.name,
            color = OnSurface,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillChips(skills: List<SkillRef>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        skills.forEach { skill ->
            Text(
                text = skill.name,
                color = Primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primary.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .padding(16.dp),
    ) {
        Text(text = text, color = OnSurfaceVariant, fontSize = 14.sp)
    }
}
