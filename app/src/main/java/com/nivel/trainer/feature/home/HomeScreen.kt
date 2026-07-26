package com.nivel.trainer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nivel.trainer.ui.theme.NivelTheme

private val SurfaceCard = Color(0xFF1E1E1E)
private val Primary = Color(0xFFCAFD00)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFADAAAA)
private val ErrorColor = Color(0xFFFF7351)

/** Минимальная тач-зона по mobile-first гайдлайну Nivel. */
private val TouchTarget = 48.dp

/**
 * Стартовый экран-каркас (B1). Полноценный дашборд тренера — в следующих
 * задачах (#76); пока здесь точка входа на экран «Ученики» (B4) и выход из
 * аккаунта (#72).
 *
 * @param onOpenStudents переход на экран списка учеников.
 * @param onLoggedOut выход подтверждён и выполнен — экран уводит на login.
 */
@Composable
fun HomeScreen(
    onOpenStudents: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreenContent(
        confirmLogout = state.confirmLogout,
        loggingOut = state.loggingOut,
        onOpenStudents = onOpenStudents,
        onLogoutClick = viewModel::openLogoutConfirm,
        onDismissLogoutConfirm = viewModel::dismissLogoutConfirm,
        onConfirmLogout = { viewModel.confirmLogout(onDone = onLoggedOut) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    confirmLogout: Boolean,
    loggingOut: Boolean,
    onOpenStudents: () -> Unit,
    onLogoutClick: () -> Unit,
    onDismissLogoutConfirm: () -> Unit,
    onConfirmLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Тулбар: пока только «Выйти» (#72) — полноценный дашборд в #76.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onLogoutClick, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Выйти", color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    text = "Nivel",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Button(
                    onClick = onOpenStudents,
                    modifier = Modifier.heightIn(min = TouchTarget),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Ученики")
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

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    NivelTheme {
        HomeScreenContent(
            confirmLogout = false,
            loggingOut = false,
            onOpenStudents = {},
            onLogoutClick = {},
            onDismissLogoutConfirm = {},
            onConfirmLogout = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0E)
@Composable
private fun HomeScreenLogoutConfirmPreview() {
    NivelTheme {
        HomeScreenContent(
            confirmLogout = true,
            loggingOut = false,
            onOpenStudents = {},
            onLogoutClick = {},
            onDismissLogoutConfirm = {},
            onConfirmLogout = {},
        )
    }
}
