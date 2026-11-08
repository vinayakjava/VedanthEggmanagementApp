package com.eggbusinessmanager.domain.usecase.report

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.report.ReportBuilder
import com.eggbusinessmanager.domain.report.ReportData
import com.eggbusinessmanager.domain.report.ReportKind
import com.eggbusinessmanager.domain.report.ReportRequest
import com.eggbusinessmanager.domain.report.TRANSACTION_TYPES
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.repository.ReportRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Loads the current data for a report and builds it (Phase 7). Every figure comes from the database. */
class LoadReportUseCase @Inject constructor(
    private val reports: ReportRepository,
    private val ledger: LedgerRepository,
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(request: ReportRequest): DomainResult<ReportData> = catchingResult {
        val r = request.normalized()
        val customer = r.customerId?.takeIf { r.kind.acceptsCustomer }?.let {
            customers.get(it) ?: return DomainResult.Err(DomainError.NotFound)
        }
        if (r.kind.requiresCustomer && customer == null) {
            return DomainResult.Err(DomainError.Validation(Field.CUSTOMER, ValidationCode.REQUIRED))
        }
        suspend fun rows(types: Set<EntryType>) =
            reports.observeTransactions(TransactionFilter(from = r.from, to = r.to, customerId = customer?.id, types = types)).first()

        val data: ReportData = when (r.kind) {
            ReportKind.CUSTOMER_STATEMENT -> ReportBuilder.statement(r, checkNotNull(customer), ledger.observeEntries(customer.id).first())
            ReportKind.DAILY_SALES -> ReportBuilder.tradePeriods(r, EntryType.SELL, monthly = false, rows(setOf(EntryType.SELL)))
            ReportKind.DAILY_PURCHASES -> ReportBuilder.tradePeriods(r, EntryType.BUY, monthly = false, rows(setOf(EntryType.BUY)))
            ReportKind.MONTHLY_SALES -> ReportBuilder.tradePeriods(r, EntryType.SELL, monthly = true, rows(setOf(EntryType.SELL)))
            ReportKind.MONTHLY_PURCHASES -> ReportBuilder.tradePeriods(r, EntryType.BUY, monthly = true, rows(setOf(EntryType.BUY)))
            ReportKind.RECEIVABLES, ReportKind.PAYABLES -> ReportBuilder.outstanding(
                r,
                reports.observeCustomerBalances().first(),
                receivable = r.kind == ReportKind.RECEIVABLES,
                asOf = clock.today(),
            )
            ReportKind.PAYMENT_HISTORY ->
                ReportBuilder.paymentHistory(r, customer, rows(setOf(EntryType.PAYMENT_RECEIVED, EntryType.PAYMENT_MADE)))
            ReportKind.TRANSACTIONS -> ReportBuilder.transactions(r, customer, rows(TRANSACTION_TYPES))
        }
        DomainResult.Ok(data)
    }
}
