package com.eggbusinessmanager.app.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.eggbusinessmanager.app.R

/** The five top-level sections shown in the bottom bar / rail / drawer. */
enum class TopLevelDestination(
    val route: Any,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    DASHBOARD(DashboardRoute, R.string.nav_dashboard, R.drawable.ic_nav_dashboard),
    CUSTOMERS(CustomersRoute, R.string.nav_customers, R.drawable.ic_nav_customers),
    TRANSACTIONS(TransactionsRoute, R.string.nav_transactions, R.drawable.ic_nav_transactions),
    REPORTS(ReportsRoute, R.string.nav_reports, R.drawable.ic_nav_reports),
    MORE(MoreRoute, R.string.nav_more, R.drawable.ic_nav_more),
}
