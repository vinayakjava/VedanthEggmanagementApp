package com.eggbusinessmanager.domain.usecase.payment

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.MoneyParser
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.payment.PaymentImpact
import com.eggbusinessmanager.domain.payment.PaymentValidator
import com.eggbusinessmanager.domain.payment.ValidPayment
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Looks up the customer and validates a payment draft. */
class PaymentChecks @Inject constructor(
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend fun check(draft: PaymentDraft, keepCustomerId: Long? = null): DomainResult<ValidPayment> =
        PaymentValidator.validate(draft, draft.customerId?.let { customers.get(it) }, clock.today(), keepCustomerId)
}

/**
 * Records RECEIVE PAYMENT (RP-) or PAY CUSTOMER (PC-) (FR-028). Advances need no special handling here:
 * the balance simply goes the other way (FR-030). The screen shows and confirms the advance first
 * using [PreviewPaymentUseCase].
 */
class RecordPaymentUseCase @Inject constructor(
    private val checks: PaymentChecks,
    private val writer: EntryWriter,
) {
    suspend operator fun invoke(draft: PaymentDraft, clientRequestId: String): DomainResult<EntryRef> = catchingResult {
        writer.existing(clientRequestId)?.let { return DomainResult.Ok(it) }
        when (val result = checks.check(draft)) {
            is DomainResult.Err -> result
            is DomainResult.Ok -> DomainResult.Ok(writer.record(result.value.toValues(), clientRequestId))
        }
    }
}

/**
 * Corrects a payment (FR-033): amount, date, customer, mode, reference, remarks, or RECEIVE ↔ PAY
 * (new reference in the new series, FR-033b). The original stays as a superseded revision; every
 * balance derived from the ledger reflects the correction immediately.
 */
class EditPaymentUseCase @Inject constructor(
    private val checks: PaymentChecks,
    private val ledger: LedgerRepository,
    private val writer: EntryWriter,
) {
    suspend operator fun invoke(rootId: Long, draft: PaymentDraft, reason: String = ""): DomainResult<EntryRef> = catchingResult {
        val current = ledger.currentRevision(rootId)
        if (current == null || current.status != EntryStatus.ACTIVE || current.payment == null) {
            return DomainResult.Err(if (current == null) DomainError.NotFound else DomainError.EntryNotEditable)
        }
        if (reason.trim().length > REASON_MAX) return DomainResult.Err(DomainError.Validation(Field.REASON, ValidationCode.TOO_LONG))
        when (val result = checks.check(draft, keepCustomerId = current.customerId)) {
            is DomainResult.Err -> result
            is DomainResult.Ok -> DomainResult.Ok(writer.edit(current, result.value.toValues(), reason.trim().ifEmpty { null }))
        }
    }

    private companion object {
        const val REASON_MAX = 200
    }
}

/** The editor's starting values for an existing payment. */
class GetPaymentDraftUseCase @Inject constructor(private val ledger: LedgerRepository) {
    suspend operator fun invoke(rootId: Long): DomainResult<PaymentDraft> = catchingResult {
        val current = ledger.currentRevision(rootId) ?: return DomainResult.Err(DomainError.NotFound)
        val payment = current.payment
        if (current.status != EntryStatus.ACTIVE || payment == null) return DomainResult.Err(DomainError.EntryNotEditable)
        DomainResult.Ok(
            PaymentDraft(
                type = current.type,
                customerId = current.customerId,
                date = current.date,
                amount = MoneyParser.toInput(current.amount),
                mode = payment.mode,
                reference = payment.reference.orEmpty(),
                remarks = current.remarks.orEmpty(),
            ),
        )
    }
}

/**
 * Before saving: the outstanding amount ("Full amount"), the advance if any, and the resulting balance
 * (FR-030, US4-2/3/4). When editing, the entry being corrected is left out of the starting balance.
 */
class PreviewPaymentUseCase @Inject constructor(private val ledger: LedgerRepository) {
    suspend operator fun invoke(customerId: Long, type: EntryType, amount: Money, editingRootId: Long? = null): PaymentImpact {
        val entries = ledger.observeEntries(customerId).first().filterNot { editingRootId != null && it.rootId == editingRootId }
        return PaymentImpact.of(type, LedgerCalculator.balance(entries), amount)
    }
}
