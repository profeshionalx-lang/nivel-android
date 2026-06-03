package com.nivel.trainer.feature

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nivel.trainer.feature.home.HomeScreen

/** Маршруты приложения. Расширяется по мере добавления экранов (B4/B5/B6 …). */
object NivelRoutes {
    const val HOME = "home"
}

/**
 * Корневой граф навигации (Navigation Compose). Пока — единственный пустой экран;
 * экраны учеников/тренировок/записи подключаются в следующих задачах эпиков 1–4.
 */
@Composable
fun NivelNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = NivelRoutes.HOME,
        modifier = modifier,
    ) {
        composable(NivelRoutes.HOME) {
            HomeScreen()
        }
    }
}
