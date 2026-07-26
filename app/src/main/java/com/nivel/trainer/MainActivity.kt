package com.nivel.trainer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.google.firebase.messaging.FirebaseMessaging
import com.nivel.trainer.data.remote.AuthEvents
import com.nivel.trainer.feature.NivelNavHost
import com.nivel.trainer.feature.NivelRoutes
import com.nivel.trainer.push.PushTokenRegistrar
import com.nivel.trainer.ui.theme.NivelTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Единственная Activity приложения (single-activity + Navigation Compose).
 * @AndroidEntryPoint позволяет инжектить зависимости через Hilt в граф навигации.
 *
 * launchMode=singleTask (см. AndroidManifest): возврат из Chrome Custom Tabs по
 * deep link `nivel://auth/callback?...` приходит в [onNewIntent] уже запущенной
 * Activity. URI кладём в Compose-стейт, откуда его читает LoginScreen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pushTokenRegistrar: PushTokenRegistrar

    /** #72 — событие 401 от любого авторизованного клиента (см. `AuthEvents`). */
    @Inject lateinit var authEvents: AuthEvents

    /** Deep link возврата входа Гречки; потребляется LoginScreen и сбрасывается в null. */
    private val authCallbackUri: MutableState<Uri?> = mutableStateOf(null)

    /** Deep link открытия экрана из тапа по push (напр. `nivel://session/{id}`). */
    private val pushDeepLink: MutableState<Uri?> = mutableStateOf(null)

    /**
     * #72 — счётчик 401-событий. 0 = ещё не было ни одного (начальное значение, не
     * триггерит навигацию); каждый инкремент — новое событие для `LaunchedEffect` в
     * [NivelRoot]. Счётчик, а не Boolean/Unit — чтобы повторные 401 подряд (пока
     * первый уже увёл на login) не терялись как «то же самое» значение состояния.
     */
    private val logoutSignal: MutableState<Int> = mutableStateOf(0)

    /** Запрос рантайм-разрешения POST_NOTIFICATIONS (Android 13+). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Разрешение не обязательно для работы FCM-доставки — без него просто
            // не показываем системные уведомления. Регистрацию токена не блокируем.
            Log.d("MainActivity", "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash через androidx SplashScreen API — должен ставиться до super.onCreate().
        // Системный splash-экран (фирменный фон + знак «N») держится до первого кадра Compose.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Холодный старт по deep link (Activity ещё не было) — intent приходит сюда.
        captureIntent(intent)
        maybeRequestNotificationPermission()
        registerFcmToken()
        observeAuthEvents()
        setContent {
            NivelRoot(
                authCallbackUri = authCallbackUri.value,
                onAuthCallbackConsumed = { authCallbackUri.value = null },
                pushDeepLink = pushDeepLink.value,
                onPushDeepLinkConsumed = { pushDeepLink.value = null },
                logoutSignal = logoutSignal.value,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Тёплый возврат из Custom Tabs / тап по push в уже живую Activity (singleTask).
        setIntent(intent)
        captureIntent(intent)
    }

    private fun captureIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "nivel") return
        when (uri.host) {
            "auth" -> authCallbackUri.value = uri
            // Любой другой nivel://<host>/... — навигационный deep link из push.
            else -> pushDeepLink.value = uri
        }
    }

    /** Android 13+ требует рантайм-разрешение на показ уведомлений. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Получить текущий FCM-токен и зарегистрировать его на бэкенде. Registrar сам
     * пропустит регистрацию, если bearer-сессии ещё нет (до логина); токен тогда
     * уедет позже через onNewToken или при следующем старте после входа.
     */
    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                lifecycleScope.launch { pushTokenRegistrar.register(token) }
            }
            .addOnFailureListener { e ->
                Log.w("MainActivity", "failed to fetch FCM token", e)
            }
    }

    /** #72 — 401 от любого авторизованного клиента → бампаем сигнал для [NivelRoot]. */
    private fun observeAuthEvents() {
        lifecycleScope.launch {
            authEvents.unauthorized.collect { logoutSignal.value += 1 }
        }
    }
}

@Composable
private fun NivelRoot(
    authCallbackUri: Uri?,
    onAuthCallbackConsumed: () -> Unit,
    pushDeepLink: Uri?,
    onPushDeepLinkConsumed: () -> Unit,
    logoutSignal: Int = 0,
) {
    NivelTheme {
        val navController = rememberNavController()

        // #72: 401 от сервера → уводим на login с любого экрана. Идемпотентно — если
        // несколько параллельных запросов словили 401 одновременно, logoutSignal
        // бампается несколько раз, но переход делаем только если мы ещё не на
        // login/splash (иначе повторный navigate() на тот же граф — не нужен).
        LaunchedEffect(logoutSignal) {
            if (logoutSignal == 0) return@LaunchedEffect
            val current = navController.currentDestination?.route
            if (current != NivelRoutes.LOGIN && current != NivelRoutes.SPLASH) {
                navController.navigate(NivelRoutes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            NivelNavHost(
                navController = navController,
                authCallbackUri = authCallbackUri,
                onAuthCallbackConsumed = onAuthCallbackConsumed,
                pushDeepLink = pushDeepLink,
                onPushDeepLinkConsumed = onPushDeepLinkConsumed,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
