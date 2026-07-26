package com.nivel.trainer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import com.nivel.trainer.data.repository.AuthRepository
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

    /** #81 — проверка «уже залогинен?» перед тем, как принять claim-ссылку приглашения. */
    @Inject lateinit var authRepository: AuthRepository

    /** Deep link возврата входа Гречки; потребляется LoginScreen и сбрасывается в null. */
    private val authCallbackUri: MutableState<Uri?> = mutableStateOf(null)

    /** Deep link открытия экрана из тапа по push (напр. `nivel://session/{id}`). */
    private val pushDeepLink: MutableState<Uri?> = mutableStateOf(null)

    /**
     * Claim-токен из App Link приглашения (`https://…/invite/{token}`, #81) —
     * проброшен в [com.nivel.trainer.feature.auth.LoginScreen]. Устанавливается
     * только если пользователь ещё НЕ залогинен (см. [handleInviteLink]) — иначе
     * ссылку молча проглотили бы, ничего не изменив на экране логина.
     *
     * Обнуляется, как только `LoginScreen` применил его к своему `AuthViewModel`
     * (см. `onClaimTokenConsumed` в [NivelRoot]/`NivelNavHost`). Без этого один и
     * тот же токен пережил бы логаут: `AuthViewModel` пересоздаётся при каждом
     * новом заходе на LOGIN (`popUpTo(0)`), и стухший/уже использованный claim
     * молча подмешался бы в совершенно не связанный повторный вход.
     */
    private val inviteClaimToken: MutableState<String?> = mutableStateOf(null)

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
                inviteClaimToken = inviteClaimToken.value,
                onInviteClaimTokenConsumed = { inviteClaimToken.value = null },
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
        when (uri.scheme) {
            "nivel" -> when (uri.host) {
                "auth" -> authCallbackUri.value = uri
                // Любой другой nivel://<host>/... — навигационный deep link из push.
                else -> pushDeepLink.value = uri
            }
            // #81: App Link `https://…/invite/{token}` — единственный https-путь,
            // на который манифест объявляет intent-filter, так что любой https-intent,
            // дошедший сюда, по построению — приглашение.
            "https" -> handleInviteLink(uri)
        }
    }

    /**
     * Claim-ссылка приглашения ученика (#81). Токен без проверки на сервере не
     * бывает — просто прокидываем на экран логина, где реальную валидность (истёк /
     * уже использован / мусор) вернёт `POST /api/v1/auth/token` при попытке claim'а
     * (см. `AuthViewModel.mapError`). Здесь отсекаем только заведомо пустой токен и
     * случай «уже залогинены» — тут ссылку нельзя молча проглотить (acceptance).
     */
    private fun handleInviteLink(uri: Uri) {
        val token = uri.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (token == null) {
            Toast.makeText(this, "Ссылка-приглашение недействительна.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            if (authRepository.hasToken()) {
                Toast.makeText(
                    this@MainActivity,
                    "Вы уже авторизованы. Чтобы принять приглашение на другой аккаунт, сначала выйдите.",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                inviteClaimToken.value = token
            }
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
    inviteClaimToken: String? = null,
    onInviteClaimTokenConsumed: () -> Unit = {},
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
                inviteClaimToken = inviteClaimToken,
                onInviteClaimTokenConsumed = onInviteClaimTokenConsumed,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
