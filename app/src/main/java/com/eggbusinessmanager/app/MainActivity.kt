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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.feature.startup.StartupProblemScreen
import com.eggbusinessmanager.app.navigation.AppNavHost
import com.eggbusinessmanager.app.ui.format.IndianFormatters
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
        splash.setKeepOnScreenCondition {
            val state = appViewModel.uiState.value
            state is AppViewModel.UiState.Loading ||
                (state is AppViewModel.UiState.Done && state.startup == StartupState.Ready && appViewModel.preferences.value == null)
        }

        protectRecentsPreview()
        enableEdgeToEdge()

        setContent {
            val uiState by appViewModel.uiState.collectAsStateWithLifecycle()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val preferences by appViewModel.preferences.collectAsStateWithLifecycle()
            // Display formats: applied before the first screen; a later change recreates the activity so
            // every screen (and every PDF) uses the new format, like a language change.
            val current = preferences
            if (current != null && current != IndianFormatters.preferences) {
                val firstLoad = !formatsApplied
                IndianFormatters.preferences = current
                formatsApplied = true
                if (!firstLoad) LaunchedEffect(current) { recreate() }
            } else if (current != null) {
                formatsApplied = true
            }

            EggBusinessTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val state = uiState) {
                        AppViewModel.UiState.Loading -> Unit // splash screen is still showing
                        is AppViewModel.UiState.Done -> when (state.startup) {
                            StartupState.Ready -> if (preferences != null) AppNavHost()
                            else -> StartupProblemScreen(state = state.startup, onRetry = appViewModel::retry)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Survives activity recreation (process-wide), so a recreate does not count as a first load. */
        @Volatile
        var formatsApplied = false
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
