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

/** Settings reached from More. */
@Serializable data object SecurityRoute
@Serializable data object ChangePasswordRoute
@Serializable data object ChangeRecoveryPinRoute
@Serializable data object ChangeSecurityQuestionsRoute
