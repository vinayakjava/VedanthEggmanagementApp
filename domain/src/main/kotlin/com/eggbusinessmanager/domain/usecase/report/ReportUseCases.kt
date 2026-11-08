package com.eggbusinessmanager.domain.usecase.report

import com.eggbusinessmanager.domain.backup.BackupHistory
import com.eggbusinessmanager.domain.backup.BackupOperation
import com.eggbusinessmanager.domain.backup.BackupStatus
import com.eggbusinessmanager.domain.report.DashboardCalculator
import com.eggbusinessmanager.domain.report.DashboardPeriod
import com.eggbusinessmanager.domain.report.DashboardSummary
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionList
import com.eggbusinessmanager.domain.report.TransactionTotals
import com.eggbusinessmanager.domain.report.comparator
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.ReportRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
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

/**
 * Live dashboard figures for [period] (US6). The business date is re-read every [tickMs], so "Today"
 * moves to the new day at midnight while the app stays open (US6-4).
 */
class ObserveDashboardUseCase @Inject constructor(private val reports: ReportRepository, private val clock: Clock) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(period: DashboardPeriod = DashboardPeriod.TODAY, tickMs: Long = TICK_MS): Flow<DashboardSummary> =
        flow {
            while (true) {
                emit(clock.today())
                delay(tickMs)
            }
        }.distinctUntilChanged().flatMapLatest { today ->
            combine(
                reports.observeDashboard(period.from(today), today, DashboardCalculator.RECENT_LIMIT),
                reports.observeCustomerBalances(),
            ) { summary, balances -> summary.copy(topReceivables = DashboardCalculator.topReceivables(balances)) }
        }

    private companion object {
        const val TICK_MS = 60_000L
    }
}

/**
 * US6-5 / FR-055: true when there has been no successful backup in the last [DAYS] days, or never;
 * the dashboard then shows a reminder with "Back up now".
 */
class ObserveBackupReminderUseCase @Inject constructor(private val history: BackupHistory, private val clock: Clock) {
    operator fun invoke(): Flow<Boolean> = history.observe().map { records ->
        val last = records.filter { it.operation == BackupOperation.BACKUP && it.status == BackupStatus.SUCCESS }
            .maxOfOrNull { it.finishedAt ?: it.startedAt }
        last == null || Duration.between(last, clock.now()) > Duration.ofDays(DAYS)
    }.distinctUntilChanged()

    companion object {
        const val DAYS = 7L
    }
}
