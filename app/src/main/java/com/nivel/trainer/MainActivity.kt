package com.nivel.trainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.nivel.trainer.feature.NivelNavHost
import com.nivel.trainer.ui.theme.NivelTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Единственная Activity приложения (single-activity + Navigation Compose).
 * @AndroidEntryPoint позволяет инжектить зависимости через Hilt в граф навигации.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NivelRoot()
        }
    }
}

@Composable
private fun NivelRoot() {
    NivelTheme {
        val navController = rememberNavController()
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            NivelNavHost(
                navController = navController,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
