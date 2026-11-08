package com.eggbusinessmanager.domain.receipt

import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.BusinessProfileRepository
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.repository.LedgerRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Everything a one-page receipt shows (FR-042a), for one active sale, purchase or payment.
 *
 * @property revised the entry was corrected after it was first saved; the receipt shows the corrected values
 * @property linked the payment taken on the spot with a trade, or the trade a payment was taken with (FR-023)
 * @property balanceAfter the customer's balance right after this entry, in ledger order
 * @property currentBalance the customer's balance now, including later entries
 * @property shopPhone the owner's mobile number, printed under the shop address
 */
data class Receipt(
    val entry: LedgerEntry,
    val revised: Boolean,
    val customer: Customer,
    val linked: LedgerEntry?,
    val balanceAfter: Money,
    val currentBalance: Money,
    val shop: BusinessProfile?,
    val shopPhone: String?,
)

/**
 * Builds the receipt for [rootId] (FR-042a). Deleted entries, entries replaced by a type change and
 * opening balances have no receipt.
 */
class BuildReceiptUseCase @Inject constructor(
    private val ledger: LedgerRepository,
    private val customers: CustomerRepository,
    private val profiles: BusinessProfileRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(rootId: Long): DomainResult<Receipt> = catchingResult {
        val revisions = ledger.observeRevisions(rootId).first()
        val entry = revisions.lastOrNull()
        val customer = entry?.let { customers.get(it.customerId) }
        when {
            entry == null || customer == null -> DomainResult.Err(DomainError.NotFound)
            !hasReceipt(entry) -> DomainResult.Err(DomainError.EntryNotEditable)
            else -> DomainResult.Ok(build(entry, revised = revisions.size > 1, customer))
        }
    }

    private suspend fun build(entry: LedgerEntry, revised: Boolean, customer: Customer): Receipt {
        val book = LedgerCalculator.ledger(ledger.observeEntries(entry.customerId).first())
        return Receipt(
            entry = entry,
            revised = revised,
            customer = customer,
            linked = entry.linkedRootId?.let { ledger.currentRevision(it) }?.takeIf { it.status == EntryStatus.ACTIVE },
            balanceAfter = book.lines.firstOrNull { it.entry.rootId == entry.rootId }?.runningBalance ?: book.balance,
            currentBalance = book.balance,
            shop = profiles.observe().first(),
            shopPhone = accounts.credentials()?.mobile,
        )
    }

    companion object {
        /** Only active trades and payments have a receipt (FR-042a). */
        fun hasReceipt(entry: LedgerEntry): Boolean =
            entry.status == EntryStatus.ACTIVE && (entry.trade != null || entry.payment != null)
    }
}
