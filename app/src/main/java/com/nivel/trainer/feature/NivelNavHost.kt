package com.nivel.trainer.feature

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nivel.trainer.feature.auth.LoginScreen
import com.nivel.trainer.feature.auth.SplashScreen
import com.nivel.trainer.feature.home.HomeScreen

/** Маршруты приложения. Расширяется по мере добавления экранов (B4/B5/B6 …). */
object NivelRoutes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
}

/**
 * Корневой граф навигации (Navigation Compose). Старт — сплэш, который решает
 * home/login по наличию bearer-токена. Экраны учеников/тренировок/записи
 * подключаются в следующих задачах эпиков 1–4.
 */
@Composable
fun NivelNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = NivelRoutes.SPLASH,
        modifier = modifier,
    ) {
        composable(NivelRoutes.SPLASH) {
            SplashScreen(
                onHome = {
                    navController.navigate(NivelRoutes.HOME) {
                        popUpTo(NivelRoutes.SPLASH) { inclusive = true }
                    }
                },
                onLogin = {
                    navController.navigate(NivelRoutes.LOGIN) {
                        popUpTo(NivelRoutes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(NivelRoutes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NivelRoutes.HOME) {
                        popUpTo(NivelRoutes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(NivelRoutes.HOME) {
            HomeScreen()
        }
    }
}
