package com.eggbusinessmanager.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.settings.MoreScreen
import com.eggbusinessmanager.app.feature.settings.security.ChangePasswordScreen
import com.eggbusinessmanager.app.feature.settings.security.ChangeRecoveryPinScreen
import com.eggbusinessmanager.app.feature.settings.security.ChangeSecurityQuestionsScreen
import com.eggbusinessmanager.app.feature.settings.security.SecurityScreen
import com.eggbusinessmanager.app.ui.components.EmptyState

/**
 * Adaptive shell: bottom bar on phones, navigation rail on medium widths, drawer on large screens
 * (contracts/navigation.md § Top-level destinations and adaptive layout).
 */
@Composable
fun MainScaffold(
    reviewRecovery: Boolean,
    onRecoveryReviewHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // navigationSuiteItems is not a composable scope, so labels are resolved here.
    val labels = TopLevelDestination.entries.associateWith { stringResource(it.labelRes) }

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentDestination?.hierarchy?.any { it.hasRoute(destination.route::class) } == true
                val label = labels.getValue(destination)
                item(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination.route) },
                    icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                    label = { Text(label) },
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<DashboardRoute> { SectionPlaceholder(TopLevelDestination.DASHBOARD) }
            composable<CustomersRoute> { SectionPlaceholder(TopLevelDestination.CUSTOMERS) }
            composable<TransactionsRoute> { SectionPlaceholder(TopLevelDestination.TRANSACTIONS) }
            composable<ReportsRoute> { SectionPlaceholder(TopLevelDestination.REPORTS) }
            composable<MoreRoute> { MoreScreen(onSecurity = { navController.navigate(SecurityRoute) }) }
            composable<SecurityRoute> {
                SecurityScreen(
                    onChangePassword = { navController.navigate(ChangePasswordRoute) },
                    onChangePin = { navController.navigate(ChangeRecoveryPinRoute) },
                    onChangeQuestions = { navController.navigate(ChangeSecurityQuestionsRoute) },
                    onBack = navController::popBackStack,
                )
            }
            composable<ChangePasswordRoute> { ChangePasswordScreen(onDone = navController::popBackStack) }
            composable<ChangeRecoveryPinRoute> { ChangeRecoveryPinScreen(onDone = navController::popBackStack) }
            composable<ChangeSecurityQuestionsRoute> { ChangeSecurityQuestionsScreen(onDone = navController::popBackStack) }
        }
    }

    if (reviewRecovery) {
        RecoveryReviewDialog(
            onReview = {
                onRecoveryReviewHandled()
                navController.navigateToTopLevel(MoreRoute)
                navController.navigate(SecurityRoute)
            },
            onLater = onRecoveryReviewHandled,
        )
    }
}

private fun NavHostController.navigateToTopLevel(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** After a password reset, prompt the owner to review the recovery PIN and questions (FR-013). */
@Composable
private fun RecoveryReviewDialog(onReview: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.recovery_review_title)) },
        text = { Text(stringResource(R.string.recovery_review_body)) },
        confirmButton = { TextButton(onClick = onReview) { Text(stringResource(R.string.recovery_review_now)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.recovery_review_later)) } },
    )
}

/** Shown until each business module is implemented (later phases). */
@Composable
private fun SectionPlaceholder(destination: TopLevelDestination) {
    EmptyState(
        title = stringResource(destination.labelRes),
        body = stringResource(R.string.placeholder_body),
        iconRes = destination.iconRes,
    )
}
