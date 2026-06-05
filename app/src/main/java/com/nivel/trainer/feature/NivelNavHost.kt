package com.nivel.trainer.feature

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nivel.trainer.feature.auth.LoginScreen
import com.nivel.trainer.feature.auth.SplashScreen
import com.nivel.trainer.feature.home.HomeScreen
import com.nivel.trainer.feature.home.StudentsListScreen
import com.nivel.trainer.feature.recorder.RecorderScreen
import com.nivel.trainer.feature.session.SessionDetailScreen
import com.nivel.trainer.feature.student.StudentProfileScreen
import com.nivel.trainer.feature.transcript.TranscriptScreen

/** Маршруты приложения. Расширяется по мере добавления экранов (B4/B5/B6 …). */
object NivelRoutes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val STUDENTS = "students"

    /** Профиль ученика (B5). Аргумент — id ученика. */
    const val STUDENT_ARG = "studentId"
    const val STUDENT_PROFILE = "students/{$STUDENT_ARG}"
    fun studentProfile(studentId: String) = "students/$studentId"

    /** Карточка тренировки (B6). Аргумент — id сессии. */
    const val SESSION_ARG = "sessionId"
    const val SESSION_DETAIL = "sessions/{$SESSION_ARG}"
    fun sessionDetail(sessionId: String) = "sessions/$sessionId"

    /** Транскрипт сессии (D1). Аргумент — id сессии (см. SESSION_ARG). */
    const val TRANSCRIPT = "sessions/{$SESSION_ARG}/transcript"
    fun transcript(sessionId: String) = "sessions/$sessionId/transcript"

    /** Экран записи тренировки (C2). Аргумент — id сессии (см. SESSION_ARG). */
    const val RECORD = "sessions/{$SESSION_ARG}/record"
    fun record(sessionId: String) = "sessions/$sessionId/record"
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
            HomeScreen(
                onOpenStudents = { navController.navigate(NivelRoutes.STUDENTS) },
            )
        }

        // B4 (#7) — экран «Ученики» + создание теневого ученика и приглашение.
        composable(NivelRoutes.STUDENTS) {
            StudentsListScreen(
                onClose = { navController.popBackStack() },
                onOpenStudent = { studentId ->
                    navController.navigate(NivelRoutes.studentProfile(studentId))
                },
            )
        }

        // B5 (#8) — профиль ученика (просмотр): цели, сессии, мастер-план.
        composable(
            route = NivelRoutes.STUDENT_PROFILE,
            arguments = listOf(navArgument(NivelRoutes.STUDENT_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val studentId = backStackEntry.arguments?.getString(NivelRoutes.STUDENT_ARG).orEmpty()
            StudentProfileScreen(
                studentId = studentId,
                onBack = { navController.popBackStack() },
                onOpenSession = { sessionId ->
                    navController.navigate(NivelRoutes.sessionDetail(sessionId))
                },
            )
        }

        // B6 (#9) — карточка тренировки (просмотр): статус/дата + аудио + карточки.
        composable(
            route = NivelRoutes.SESSION_DETAIL,
            arguments = listOf(navArgument(NivelRoutes.SESSION_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(NivelRoutes.SESSION_ARG).orEmpty()
            SessionDetailScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
                onRecord = { navController.navigate(NivelRoutes.record(sessionId)) },
            )
        }

        // C2 (#11) — экран записи тренировки (таймер, «Стоп» → заливка).
        composable(
            route = NivelRoutes.RECORD,
            arguments = listOf(navArgument(NivelRoutes.SESSION_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(NivelRoutes.SESSION_ARG).orEmpty()
            RecorderScreen(
                sessionId = sessionId,
                onClose = { navController.popBackStack() },
            )
        }

        // D1 (#19) — экран транскрипта тренировки (просмотр, выгрузка).
        composable(
            route = NivelRoutes.TRANSCRIPT,
            arguments = listOf(navArgument(NivelRoutes.SESSION_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(NivelRoutes.SESSION_ARG).orEmpty()
            TranscriptScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
