package com.eggbusinessmanager.app.navigation

import kotlinx.serialization.Serializable

// Type-safe routes (contracts/navigation.md). Arguments are ids only, never whole objects.

/** Auth graph (shown while signed out). */
@Serializable data object WelcomeRoute
@Serializable data object SignupGraph
@Serializable data object SignupAccountRoute
@Serializable data object SignupShopRoute
@Serializable data object SignupSecurityRoute
@Serializable data object SignInRoute
@Serializable data object ForgotPasswordRoute
@Serializable data object RecoverWithPinRoute
@Serializable data object RecoverWithQuestionsRoute

/** Top-level destinations inside the main scaffold. */
@Serializable data object DashboardRoute
@Serializable data object CustomersRoute
@Serializable data object TransactionsRoute
@Serializable data object ReportsRoute
@Serializable data object MoreRoute

/** Customers (US2). `customerId = 0` opens the editor for a new customer; `prefill` is text typed in search. */
@Serializable data class CustomerDetailRoute(val customerId: Long)
@Serializable data class CustomerEditorRoute(val customerId: Long = 0, val prefill: String = "", val returnToPicker: Boolean = false)
@Serializable data class CustomerHistoryRoute(val customerId: Long)

/**
 * Sell / Buy (US3). `type` is SELL or BUY; `customerId` > 0 pre-selects a customer; `rootId` > 0 edits
 * that entry instead of recording a new one.
 */
@Serializable data class TradeEditorRoute(val type: String, val customerId: Long = 0, val rootId: Long = 0)
@Serializable data class EntryDetailRoute(val rootId: Long)

/** Receive payment / Pay customer (US4). `type` is PAYMENT_RECEIVED or PAYMENT_MADE; ids as for trades. */
/** One report (Phase 7). `kind` is a ReportKind name; `customerId` > 0 pre-selects a customer. */
@Serializable data class ReportViewerRoute(val kind: String, val customerId: Long = 0)

@Serializable data class PaymentEditorRoute(val type: String, val customerId: Long = 0, val rootId: Long = 0)

/** Settings reached from More. */
@Serializable data object SecurityRoute
@Serializable data object ChangePasswordRoute
@Serializable data object ChangeRecoveryPinRoute
@Serializable data object ChangeSecurityQuestionsRoute
@Serializable data object UnitsRoute
@Serializable data object VarietiesRoute
