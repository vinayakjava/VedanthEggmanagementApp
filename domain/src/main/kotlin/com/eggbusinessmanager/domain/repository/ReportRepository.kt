package com.eggbusinessmanager.domain.repository

import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.report.DashboardSummary
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionRow
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Read-only aggregates over customers and the ledger. Flows re-emit whenever the underlying rows change. */
interface ReportRepository {
    /** Active transactions matching [filter] (as [TransactionFilter.matches] defines it), in no particular order. */
    fun observeTransactions(filter: TransactionFilter): Flow<List<TransactionRow>>

    /** The dashboard as [com.eggbusinessmanager.domain.report.DashboardCalculator] defines it. */
    fun observeDashboard(today: LocalDate, recentLimit: Int): Flow<DashboardSummary>

    /** Every customer, active or deactivated, with its balance (for outstanding reports). */
    fun observeCustomerBalances(): Flow<List<CustomerSummary>>
}
