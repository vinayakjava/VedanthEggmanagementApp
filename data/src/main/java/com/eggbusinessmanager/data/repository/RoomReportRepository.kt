package com.eggbusinessmanager.data.repository

import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.dao.TradeTotalRow
import com.eggbusinessmanager.data.db.dao.TransactionRowEntity
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.data.mapper.toDomain
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.report.DashboardSummary
import com.eggbusinessmanager.domain.report.TradeTotals
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionRow
import com.eggbusinessmanager.domain.repository.ReportRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Provider

/** Dashboard and Transactions from SQL aggregates; re-emits whenever customers or entries change. */
class RoomReportRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReportRepository {

    private val dao get() = database.get().reportDao()

    override fun observeTransactions(filter: TransactionFilter): Flow<List<TransactionRow>> {
        // SQLite rejects `IN ()`; an empty selection simply lists nothing.
        if (filter.types.isEmpty()) return flowOf(emptyList())
        return flow {
            emitAll(
                dao.transactions(
                    types = filter.types.map { it.name },
                    fromDay = filter.from?.toEpochDay(),
                    toDay = filter.to?.toEpochDay(),
                    customerId = filter.customerId,
                    text = likeEscape(filter.searchText),
                    digits = filter.searchDigits,
                ),
            )
        }.map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged().flowOn(io)
    }

    override fun observeDashboard(today: LocalDate, recentLimit: Int): Flow<DashboardSummary> = flow {
        val d = dao
        emitAll(
            combine(d.customerCounts(), d.tradeTotals(null), d.tradeTotals(today.toEpochDay()), d.dues(), d.recent(recentLimit)) {
                    counts, all, day, dues, recent ->
                DashboardSummary(
                    totalCustomers = counts.total,
                    activeCustomers = counts.active,
                    sales = all.of(EntryType.SELL),
                    purchases = all.of(EntryType.BUY),
                    todaySales = day.of(EntryType.SELL),
                    todayPurchases = day.of(EntryType.BUY),
                    receivables = Money(dues.receivablePaise),
                    payables = Money(dues.payablePaise),
                    receivableCustomers = dues.receivableCount,
                    payableCustomers = dues.payableCount,
                    recent = recent.map { it.toDomain() },
                )
            },
        )
    }.distinctUntilChanged().flowOn(io)

    private fun List<TradeTotalRow>.of(type: EntryType): TradeTotals =
        firstOrNull { it.entryType == type.name }?.let { TradeTotals(it.count, it.eggs, Money(it.valuePaise)) } ?: TradeTotals()

    private fun TransactionRowEntity.toDomain() = TransactionRow(entry.toDomain(), customerName, customerMobile)

    private fun likeEscape(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
