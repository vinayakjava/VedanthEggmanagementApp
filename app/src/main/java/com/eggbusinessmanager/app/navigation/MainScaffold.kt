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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.dashboard.DashboardScreen
import com.eggbusinessmanager.app.feature.settings.catalog.UnitsScreen
import com.eggbusinessmanager.app.feature.settings.catalog.VarietiesScreen
import com.eggbusinessmanager.app.feature.trade.EntryDetailScreen
import com.eggbusinessmanager.app.feature.backup.BackupScreen
import com.eggbusinessmanager.app.feature.backup.RestoreOutcomeDialog
import com.eggbusinessmanager.app.feature.payment.PaymentEditorScreen
import com.eggbusinessmanager.app.feature.reports.ReportViewerScreen
import com.eggbusinessmanager.app.feature.reports.ReportsScreen
import com.eggbusinessmanager.app.feature.trade.TradeEditorScreen
import com.eggbusinessmanager.app.feature.transactions.TransactionsScreen
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.report.ReportKind
import com.eggbusinessmanager.app.feature.customers.detail.CustomerDetailScreen
import com.eggbusinessmanager.app.feature.customers.editor.CustomerEditorScreen
import com.eggbusinessmanager.app.feature.customers.history.CustomerHistoryScreen
import com.eggbusinessmanager.app.feature.customers.list.CustomersScreen
import com.eggbusinessmanager.app.feature.settings.MoreScreen
import com.eggbusinessmanager.app.feature.settings.security.ChangePasswordScreen
import com.eggbusinessmanager.app.feature.settings.security.ChangeRecoveryPinScreen
import com.eggbusinessmanager.app.feature.settings.security.ChangeSecurityQuestionsScreen
import com.eggbusinessmanager.app.feature.settings.security.SecurityScreen

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
            composable<DashboardRoute> {
                DashboardScreen(
                    onSell = { navController.navigate(TradeEditorRoute(EntryType.SELL.name)) },
                    onBuy = { navController.navigate(TradeEditorRoute(EntryType.BUY.name)) },
                    onReceive = { navController.navigate(PaymentEditorRoute(EntryType.PAYMENT_RECEIVED.name)) },
                    onPay = { navController.navigate(PaymentEditorRoute(EntryType.PAYMENT_MADE.name)) },
                    onOpenEntry = { navController.navigate(EntryDetailRoute(it)) },
                    onSeeAll = { navController.navigateToTopLevel(TransactionsRoute) },
                )
            }
            composable<CustomersRoute> {
                CustomersScreen(
                    onOpenCustomer = { navController.navigate(CustomerDetailRoute(it)) },
                    onAddCustomer = { prefill -> navController.navigate(CustomerEditorRoute(prefill = prefill)) },
                )
            }
            composable<CustomerDetailRoute> {
                CustomerDetailScreen(
                    onEdit = { navController.navigate(CustomerEditorRoute(customerId = it)) },
                    onHistory = { navController.navigate(CustomerHistoryRoute(it)) },
                    onTrade = { id, type -> navController.navigate(TradeEditorRoute(type.name, customerId = id)) },
                    onPayment = { id, type -> navController.navigate(PaymentEditorRoute(type.name, customerId = id)) },
                    onStatement = { id -> navController.navigate(ReportViewerRoute(ReportKind.CUSTOMER_STATEMENT.name, customerId = id)) },
                    onBack = navController::popBackStack,
                )
            }
            composable<CustomerEditorRoute> { entry ->
                val returnToPicker = entry.toRoute<CustomerEditorRoute>().returnToPicker
                CustomerEditorScreen(
                    onSaved = { id, isNew ->
                        if (returnToPicker) {
                            // US3-9: hand the new customer back to the Sell/Buy screen, which kept its input.
                            navController.previousBackStackEntry?.savedStateHandle?.set(NEW_CUSTOMER_KEY, id)
                            navController.popBackStack()
                        } else if (isNew) {
                            // Replace the editor with the new customer's detail.
                            navController.navigate(CustomerDetailRoute(id)) {
                                popUpTo<CustomerEditorRoute> { inclusive = true }
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onOpenCustomer = { id ->
                        navController.navigate(CustomerDetailRoute(id)) { popUpTo<CustomerEditorRoute> { inclusive = true } }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable<CustomerHistoryRoute> {
                CustomerHistoryScreen(onOpenEntry = { navController.navigate(EntryDetailRoute(it)) }, onBack = navController::popBackStack)
            }
            composable<TradeEditorRoute> { entry ->
                val newCustomerId by entry.savedStateHandle.getStateFlow<Long?>(NEW_CUSTOMER_KEY, null).collectAsStateWithLifecycle()
                TradeEditorScreen(
                    newCustomerId = newCustomerId,
                    onNewCustomerConsumed = { entry.savedStateHandle[NEW_CUSTOMER_KEY] = null },
                    onAddCustomer = { prefill -> navController.navigate(CustomerEditorRoute(prefill = prefill, returnToPicker = true)) },
                    onClose = navController::popBackStack,
                )
            }
            composable<PaymentEditorRoute> { entry ->
                val newCustomerId by entry.savedStateHandle.getStateFlow<Long?>(NEW_CUSTOMER_KEY, null).collectAsStateWithLifecycle()
                PaymentEditorScreen(
                    newCustomerId = newCustomerId,
                    onNewCustomerConsumed = { entry.savedStateHandle[NEW_CUSTOMER_KEY] = null },
                    onAddCustomer = { prefill -> navController.navigate(CustomerEditorRoute(prefill = prefill, returnToPicker = true)) },
                    onClose = navController::popBackStack,
                )
            }
            composable<EntryDetailRoute> {
                EntryDetailScreen(
                    onEdit = { rootId, type ->
                        val isPayment = type == EntryType.PAYMENT_RECEIVED || type == EntryType.PAYMENT_MADE
                        navController.navigate(
                            if (isPayment) PaymentEditorRoute(type.name, rootId = rootId) else TradeEditorRoute(type.name, rootId = rootId),
                        )
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable<UnitsRoute> { UnitsScreen(onBack = navController::popBackStack) }
            composable<VarietiesRoute> { VarietiesScreen(onBack = navController::popBackStack) }
            composable<BackupRoute> { BackupScreen(onBack = navController::popBackStack) }
            composable<TransactionsRoute> { TransactionsScreen(onOpenEntry = { navController.navigate(EntryDetailRoute(it)) }) }
            composable<ReportsRoute> { ReportsScreen(onOpen = { navController.navigate(ReportViewerRoute(it.name)) }) }
            composable<ReportViewerRoute> { ReportViewerScreen(onBack = navController::popBackStack) }
            composable<MoreRoute> {
                MoreScreen(
                    onSecurity = { navController.navigate(SecurityRoute) },
                    onUnits = { navController.navigate(UnitsRoute) },
                    onVarieties = { navController.navigate(VarietiesRoute) },
                    onBackup = { navController.navigate(BackupRoute) },
                )
            }
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

    RestoreOutcomeDialog()

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

/** SavedStateHandle key for a customer created from the Sell/Buy screen. */
private const val NEW_CUSTOMER_KEY = "new_customer_id"

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
