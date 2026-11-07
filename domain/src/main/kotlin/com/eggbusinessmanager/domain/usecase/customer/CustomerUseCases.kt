package com.eggbusinessmanager.domain.usecase.customer

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.customer.OpeningBalance
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.ledger.CustomerLedger
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.MoneyParser
import com.eggbusinessmanager.domain.repository.AuditRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.repository.TransactionRunner
import com.eggbusinessmanager.domain.validation.CustomerValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Search-as-you-type list with filter and sort (FR-017, FR-018). */
class SearchCustomersUseCase @Inject constructor(private val customers: CustomerRepository) {
    operator fun invoke(query: CustomerQuery): Flow<List<CustomerSummary>> = customers.search(query)
}

/** One customer with balance and last activity, updated live. */
class ObserveCustomerUseCase @Inject constructor(private val customers: CustomerRepository) {
    operator fun invoke(id: Long): Flow<CustomerSummary?> = customers.observe(id)
}

/** The customer's transaction history with a running balance (FR-029, BR-011). */
class ObserveCustomerLedgerUseCase @Inject constructor(private val ledger: LedgerRepository) {
    operator fun invoke(customerId: Long): Flow<CustomerLedger> = ledger.observeEntries(customerId).map(LedgerCalculator::ledger)
}

/** The editor's starting values for an existing customer, including the current opening balance. */
class GetCustomerDraftUseCase @Inject constructor(
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
) {
    suspend operator fun invoke(id: Long): DomainResult<CustomerDraft> = catchingResult {
        val customer = customers.get(id) ?: return DomainResult.Err(DomainError.NotFound)
        val opening = ledger.activeOpening(id)
        DomainResult.Ok(
            CustomerDraft(
                name = customer.name,
                mobile = customer.mobile.orEmpty(),
                address = customer.address.orEmpty(),
                notes = customer.notes.orEmpty(),
                openingAmount = opening?.amount?.takeUnless { it.isZero }?.let(MoneyParser::toInput).orEmpty(),
                openingDirection = opening?.openingDirection ?: OpeningDirection.CUSTOMER_OWES,
                openingDate = opening?.date,
            ),
        )
    }
}

/** Non-blocking duplicate-name warning (spec § Validation Rules: duplicates allowed with a warning). */
class CheckDuplicateNameUseCase @Inject constructor(private val customers: CustomerRepository) {
    suspend operator fun invoke(name: String, excludingId: Long? = null): Boolean =
        name.isNotBlank() && runCatching { customers.nameExists(name, excludingId) }.getOrDefault(false)
}

/**
 * Validation shared by create and update: field rules, then mobile uniqueness across active and
 * deactivated customers (FR-016).
 */
class CustomerChecks @Inject constructor(
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend fun check(draft: CustomerDraft, editingId: Long?): DomainResult<ValidCustomer> {
        val valid = when (val result = CustomerValidator.validate(draft, clock.today())) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        val owner = valid.mobile?.let { customers.findByMobile(it) }?.takeIf { it.id != editingId }
        return if (owner != null) DomainResult.Err(DomainError.DuplicateMobile(owner.id, owner.name)) else DomainResult.Ok(valid)
    }
}

/** FR-014/015/016: adds a customer and, if entered, its opening balance as an OB- ledger entry, atomically. */
class CreateCustomerUseCase @Inject constructor(
    private val checks: CustomerChecks,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: CustomerDraft): DomainResult<Long> = catchingResult {
        val valid = when (val result = checks.check(draft, editingId = null)) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        val now = clock.now()
        val id = transactions.inTransaction {
            val id = customers.insert(valid, now)
            valid.opening?.takeUnless { it.amount.isZero }?.let { ledger.insertOpening(id, it, now) }
            audit.append(AuditEvent(AuditEvent.ENTITY_CUSTOMER, id, id, AuditEvent.ACTION_CREATE, after = snapshot(valid), at = now))
            id
        }
        DomainResult.Ok(id)
    }
}

/**
 * FR-019: edits a customer. A changed opening balance is never overwritten: the current opening entry
 * is superseded by a new revision and an OPENING_CHANGE audit row records old and new values.
 */
class UpdateCustomerUseCase @Inject constructor(
    private val checks: CustomerChecks,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long, draft: CustomerDraft): DomainResult<Unit> = catchingResult {
        val before = customers.get(id) ?: return DomainResult.Err(DomainError.NotFound)
        val valid = when (val result = checks.check(draft, editingId = id)) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        val now = clock.now()
        transactions.inTransaction {
            customers.update(id, valid, now)
            audit.append(
                AuditEvent(AuditEvent.ENTITY_CUSTOMER, id, id, AuditEvent.ACTION_EDIT, snapshot(before), snapshot(valid), at = now),
            )
            updateOpening(id, valid.opening, now)
        }
        DomainResult.Ok(Unit)
    }

    private suspend fun updateOpening(id: Long, opening: OpeningBalance?, now: java.time.Instant) {
        val current = ledger.activeOpening(id)
        when {
            current == null -> opening?.takeUnless { it.amount.isZero }?.let { ledger.insertOpening(id, it, now) }
            else -> {
                // Clearing the amount records a revision to zero, keeping the history of what it was.
                val wanted = opening ?: OpeningBalance(Money.ZERO, checkNotNull(current.openingDirection), current.date)
                if (!current.matches(wanted)) {
                    ledger.reviseOpening(current, wanted, now)
                    audit.append(
                        AuditEvent(
                            AuditEvent.ENTITY_LEDGER_ENTRY, current.rootId, id, AuditEvent.ACTION_OPENING_CHANGE,
                            before = openingSnapshot(current.amount, current.openingDirection, current.date.toString()),
                            after = openingSnapshot(wanted.amount, wanted.direction, wanted.date.toString()),
                            at = now,
                        ),
                    )
                }
            }
        }
    }

    private fun LedgerEntry.matches(opening: OpeningBalance) =
        amount == opening.amount && openingDirection == opening.direction && date == opening.date
}

/** Deactivate (archive): hidden from pickers and the default list, kept everywhere else (US2-6). */
class DeactivateCustomerUseCase @Inject constructor(private val activation: CustomerActivation) {
    suspend operator fun invoke(id: Long): DomainResult<Unit> = activation.set(id, active = false)
}

/** Activate (un-archive) a deactivated customer. */
class ActivateCustomerUseCase @Inject constructor(private val activation: CustomerActivation) {
    suspend operator fun invoke(id: Long): DomainResult<Unit> = activation.set(id, active = true)
}

class CustomerActivation @Inject constructor(
    private val customers: CustomerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend fun set(id: Long, active: Boolean): DomainResult<Unit> = catchingResult {
        val customer = customers.get(id) ?: return DomainResult.Err(DomainError.NotFound)
        if (customer.isActive != active) {
            val now = clock.now()
            transactions.inTransaction {
                customers.setActive(id, active, now)
                val action = if (active) AuditEvent.ACTION_UNARCHIVE else AuditEvent.ACTION_ARCHIVE
                audit.append(AuditEvent(AuditEvent.ENTITY_CUSTOMER, id, id, action, at = now))
            }
        }
        DomainResult.Ok(Unit)
    }
}

/** Whether a customer can be deleted, i.e. has no ledger entries at all (US2-6/7). */
class CanDeleteCustomerUseCase @Inject constructor(private val ledger: LedgerRepository) {
    suspend operator fun invoke(id: Long): Boolean = !ledger.hasEntries(id)
}

/**
 * Permanently deletes a customer with no ledger entries (US2-7). A customer with any entry, even a
 * voided one, is refused with [DomainError.CustomerHasEntries]; the database FK is the backstop.
 */
class DeleteCustomerUseCase @Inject constructor(
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): DomainResult<Unit> = catchingResult {
        val customer = customers.get(id) ?: return DomainResult.Err(DomainError.NotFound)
        if (ledger.hasEntries(id)) return DomainResult.Err(DomainError.CustomerHasEntries)
        val now = clock.now()
        val deleted = transactions.inTransaction {
            customers.deleteIfUnused(id).also { deleted ->
                // The audit row keeps who was deleted; customer_id is null because the row is gone.
                if (deleted) {
                    audit.append(AuditEvent(AuditEvent.ENTITY_CUSTOMER, id, null, AuditEvent.ACTION_DELETE, snapshot(customer), at = now))
                }
            }
        }
        if (deleted) DomainResult.Ok(Unit) else DomainResult.Err(DomainError.CustomerHasEntries)
    }
}

private fun snapshot(customer: ValidCustomer): Map<String, String?> = mapOf(
    "name" to customer.name,
    "mobile" to customer.mobile,
    "address" to customer.address,
    "notes" to customer.notes,
)

private fun snapshot(customer: Customer): Map<String, String?> = mapOf(
    "name" to customer.name,
    "mobile" to customer.mobile,
    "address" to customer.address,
    "notes" to customer.notes,
)

private fun openingSnapshot(amount: Money, direction: OpeningDirection?, date: String): Map<String, String?> = mapOf(
    "amount_paise" to amount.paise.toString(),
    "direction" to direction?.name,
    "date" to date,
)
