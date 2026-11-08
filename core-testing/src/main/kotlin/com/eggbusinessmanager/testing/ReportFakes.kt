package com.eggbusinessmanager.testing

import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.report.DashboardCalculator
import com.eggbusinessmanager.domain.report.DashboardSummary
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionRow
import com.eggbusinessmanager.domain.repository.ReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** Reports over the in-memory customers and ledger, using the domain's reference definitions. */
class FakeReportRepository(
    private val customers: FakeCustomerRepository,
    private val ledger: FakeLedgerRepository,
) : ReportRepository {

    override fun observeTransactions(filter: TransactionFilter): Flow<List<TransactionRow>> =
        combine(customers.customers, ledger.rows) { list, rows ->
            val byId = list.associateBy { it.id }
            rows.map { TransactionRow(it, byId[it.customerId]?.name.orEmpty(), byId[it.customerId]?.mobile) }.filter(filter::matches)
        }

    override fun observeDashboard(today: LocalDate, recentLimit: Int): Flow<DashboardSummary> =
        combine(customers.customers, ledger.rows) { list, rows -> DashboardCalculator.of(list, rows, today, recentLimit) }

    override fun observeCustomerBalances(): Flow<List<CustomerSummary>> =
        combine(customers.customers, ledger.rows) { list, rows ->
            list.map { c ->
                val mine = rows.filter { it.customerId == c.id }
                CustomerSummary(c, LedgerCalculator.balance(mine), mine.filter { it.status == EntryStatus.ACTIVE }.maxOfOrNull { it.date })
            }
        }
}
