package com.eggbusinessmanager.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.eggbusinessmanager.app.feature.backup.BackupScreen
import com.eggbusinessmanager.app.feature.auth.recovery.ForgotPasswordScreen
import com.eggbusinessmanager.app.feature.auth.recovery.RecoverWithPinScreen
import com.eggbusinessmanager.app.feature.auth.recovery.SetPasswordAfterRestoreScreen
import com.eggbusinessmanager.app.feature.backup.RestoreOutcomeDialog
import com.eggbusinessmanager.app.feature.auth.recovery.RecoverWithQuestionsScreen
import com.eggbusinessmanager.app.feature.auth.signin.SignInScreen
import com.eggbusinessmanager.app.feature.auth.signup.SignupAccountScreen
import com.eggbusinessmanager.app.feature.auth.signup.SignupSecurityScreen
import com.eggbusinessmanager.app.feature.auth.signup.SignupShopScreen
import com.eggbusinessmanager.app.feature.auth.signup.SignupViewModel
import com.eggbusinessmanager.app.feature.welcome.WelcomeScreen
import com.eggbusinessmanager.app.session.LockOverlay
import com.eggbusinessmanager.app.session.reportUserActivity

/**
 * Root of the UI once storage is ready. Signed out → auth graph; signed in → main screens, with the
 * lock overlay on top when locked so the screens (and unsaved input) underneath are kept.
 */
@Composable
fun AppNavHost(modifier: Modifier = Modifier, viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().reportUserActivity(viewModel::onUserActivity)) {
        when (val root = state) {
            RootViewModel.RootState.Loading -> Unit
            // A fresh auth graph per start destination, e.g. after signing out.
            is RootViewModel.RootState.Auth -> key(root.hasAccount) { AuthNavHost(hasAccount = root.hasAccount) }
            is RootViewModel.RootState.Main -> {
                MainScaffold(
                    reviewRecovery = root.reviewRecovery,
                    onRecoveryReviewHandled = viewModel::onRecoveryReviewHandled,
                    // Hidden from screen readers while locked; the overlay is the only content.
                    modifier = if (root.locked) Modifier.clearAndSetSemantics {} else Modifier,
                )
                if (root.locked) LockOverlay()
            }
        }
    }
}

@Composable
private fun AuthNavHost(hasAccount: Boolean) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = if (hasAccount) SignInRoute else WelcomeRoute) {
        composable<WelcomeRoute> {
            WelcomeScreen(
                onCreateAccount = { navController.navigate(SignupGraph) },
                onRestore = { navController.navigate(RestoreFromBackupRoute) },
            )
        }
        composable<RestoreFromBackupRoute> { BackupScreen(onBack = navController::popBackStack, restoreOnly = true) }
        navigation<SignupGraph>(startDestination = SignupAccountRoute) {
            composable<SignupAccountRoute> { entry ->
                SignupAccountScreen(
                    viewModel = signupViewModel(navController, entry),
                    onNext = { navController.navigate(SignupShopRoute) },
                    onBack = navController::popBackStack,
                )
            }
            composable<SignupShopRoute> { entry ->
                SignupShopScreen(
                    viewModel = signupViewModel(navController, entry),
                    onNext = { navController.navigate(SignupSecurityRoute) },
                    onBack = navController::popBackStack,
                )
            }
            composable<SignupSecurityRoute> { entry ->
                SignupSecurityScreen(viewModel = signupViewModel(navController, entry), onBack = navController::popBackStack)
            }
        }
        composable<SignInRoute> {
            SignInScreen(viewModel = hiltViewModel(), onForgotPassword = { navController.navigate(ForgotPasswordRoute) })
            // After a restore the app restarts here: say how it went, and offer a new password (US9-9).
            RestoreOutcomeDialog(onSetPassword = { navController.navigate(SetPasswordAfterRestoreRoute) })
        }
        composable<SetPasswordAfterRestoreRoute> {
            SetPasswordAfterRestoreScreen(viewModel = hiltViewModel(), onBack = navController::popBackStack)
        }
        composable<ForgotPasswordRoute> {
            ForgotPasswordScreen(
                onUsePin = { navController.navigate(RecoverWithPinRoute) },
                onUseQuestions = { navController.navigate(RecoverWithQuestionsRoute) },
                onBack = navController::popBackStack,
            )
        }
        composable<RecoverWithPinRoute> { RecoverWithPinScreen(viewModel = hiltViewModel(), onBack = navController::popBackStack) }
        composable<RecoverWithQuestionsRoute> {
            RecoverWithQuestionsScreen(viewModel = hiltViewModel(), onBack = navController::popBackStack)
        }
    }
}

/** The signup screens share one ViewModel scoped to the signup sub-graph. */
@Composable
private fun signupViewModel(navController: NavHostController, entry: NavBackStackEntry): SignupViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry(SignupGraph) }
    return hiltViewModel(graphEntry)
}
