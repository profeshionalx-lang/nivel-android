package com.nivel.trainer.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val SplashBackground = Color(0xFF0E0E0E)
private val SplashPrimary = Color(0xFFCAFD00)

/** Решение сплэша: куда навигировать после проверки токена. */
sealed interface SplashDestination {
    data object Loading : SplashDestination
    data object Home : SplashDestination
    data object Login : SplashDestination
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {
    private val _destination = MutableStateFlow<SplashDestination>(SplashDestination.Loading)
    val destination: StateFlow<SplashDestination> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            _destination.value = if (authRepository.hasToken()) {
                SplashDestination.Home
            } else {
                SplashDestination.Login
            }
        }
    }
}

/**
 * Сплэш-экран: пока читает токен из DataStore — показывает логотип; решает, куда идти.
 * Есть валидный bearer → home, нет → login. (Истечение токена обрабатывается на уровне
 * запросов 401 в последующих задачах; здесь — только наличие.)
 */
@Composable
fun SplashScreen(
    onHome: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    LaunchedEffect(destination) {
        when (destination) {
            SplashDestination.Home -> onHome()
            SplashDestination.Login -> onLogin()
            SplashDestination.Loading -> Unit
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(SplashBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Nivel",
            color = SplashPrimary,
            fontSize = 48.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
        )
    }
}
