package com.eggbusinessmanager.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.feature.startup.StartupProblemScreen
import com.eggbusinessmanager.app.navigation.AppNavHost
import com.eggbusinessmanager.app.ui.theme.EggBusinessTheme
import com.eggbusinessmanager.domain.model.StartupState
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity. AppCompat is used for per-app language and BiometricPrompt in later phases;
 * all UI is Compose (Constitution VI).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the splash visible until encrypted storage is ready (key, downgrade guard, migrations).
        splash.setKeepOnScreenCondition { appViewModel.uiState.value is AppViewModel.UiState.Loading }

        protectRecentsPreview()
        enableEdgeToEdge()

        setContent {
            val uiState by appViewModel.uiState.collectAsStateWithLifecycle()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()

            EggBusinessTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val state = uiState) {
                        AppViewModel.UiState.Loading -> Unit // splash screen is still showing
                        is AppViewModel.UiState.Done -> when (state.startup) {
                            StartupState.Ready -> AppNavHost()
                            else -> StartupProblemScreen(state = state.startup, onRetry = appViewModel::retry)
                        }
                    }
                }
            }
        }
    }

    /**
     * Hides app content in the recent-apps preview (FR-007). API 33+ has a dedicated switch that keeps
     * screenshots allowed; on API 31–32 FLAG_SECURE is the only option.
     */
    private fun protectRecentsPreview() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        } else {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
