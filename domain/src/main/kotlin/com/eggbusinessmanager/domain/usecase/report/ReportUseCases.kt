package com.eggbusinessmanager.domain.usecase.report

import com.eggbusinessmanager.domain.report.DashboardCalculator
import com.eggbusinessmanager.domain.report.DashboardSummary
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionList
import com.eggbusinessmanager.domain.report.TransactionTotals
import com.eggbusinessmanager.domain.report.comparator
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.ReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The Transactions screen: filtered, sorted, with totals of exactly what is listed. */
class ObserveTransactionsUseCase @Inject constructor(private val reports: ReportRepository) {
    operator fun invoke(filter: TransactionFilter): Flow<TransactionList> {
        val normalized = filter.normalized()
        return reports.observeTransactions(normalized).map { rows ->
            TransactionList(rows.sortedWith(normalized.sort.comparator()), TransactionTotals.of(rows))
        }
    }
}

/** Live dashboard figures for today's business date. */
class ObserveDashboardUseCase @Inject constructor(private val reports: ReportRepository, private val clock: Clock) {
    operator fun invoke(): Flow<DashboardSummary> = reports.observeDashboard(clock.today(), DashboardCalculator.RECENT_LIMIT)
}
