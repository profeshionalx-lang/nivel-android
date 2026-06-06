package com.nivel.trainer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.nivel.trainer.feature.NivelNavHost
import com.nivel.trainer.ui.theme.NivelTheme
import dagger.hilt.android.AndroidEntryPoint

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

    /** Deep link возврата входа Гречки; потребляется LoginScreen и сбрасывается в null. */
    private val authCallbackUri: MutableState<Uri?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash через androidx SplashScreen API — должен ставиться до super.onCreate().
        // Системный splash-экран (фирменный фон + знак «N») держится до первого кадра Compose.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Холодный старт по deep link (Activity ещё не было) — intent приходит сюда.
        captureAuthCallback(intent)
        setContent {
            NivelRoot(
                authCallbackUri = authCallbackUri.value,
                onAuthCallbackConsumed = { authCallbackUri.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Тёплый возврат из Custom Tabs в уже живую Activity (singleTask).
        setIntent(intent)
        captureAuthCallback(intent)
    }

    private fun captureAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "nivel" && uri.host == "auth") {
            authCallbackUri.value = uri
        }
    }
}

@Composable
private fun NivelRoot(
    authCallbackUri: Uri?,
    onAuthCallbackConsumed: () -> Unit,
) {
    NivelTheme {
        val navController = rememberNavController()
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            NivelNavHost(
                navController = navController,
                authCallbackUri = authCallbackUri,
                onAuthCallbackConsumed = onAuthCallbackConsumed,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
