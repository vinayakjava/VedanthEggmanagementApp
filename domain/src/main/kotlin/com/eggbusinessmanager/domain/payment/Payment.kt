package com.eggbusinessmanager.domain.payment

import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.ledger.BalanceEffect
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.EntryValues
import com.eggbusinessmanager.domain.ledger.PaymentLine
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.MoneyParser
import com.eggbusinessmanager.domain.validation.CustomerValidator
import java.time.LocalDate

/** What the owner typed on the Receive payment / Pay customer screen (FR-028). */
data class PaymentDraft(
    val type: EntryType,
    val customerId: Long? = null,
    val date: LocalDate? = null,
    val amount: String = "",
    val mode: PaymentMode = PaymentMode.CASH,
    val reference: String = "",
    val remarks: String = "",
) {
    init {
        require(type == EntryType.PAYMENT_RECEIVED || type == EntryType.PAYMENT_MADE) { "A payment is RECEIVE or PAY" }
    }
}

/** A validated payment, ready to store. */
data class ValidPayment(
    val type: EntryType,
    val customerId: Long,
    val date: LocalDate,
    val amount: Money,
    val mode: PaymentMode,
    val reference: String?,
    val remarks: String?,
) {
    fun toValues() = EntryValues(type, customerId, date, amount, payment = PaymentLine(mode, reference), remarks = remarks)
}

/** Payment rules (spec § Validation Rules, FR-028). */
object PaymentValidator {
    const val REFERENCE_MAX = 50
    const val REMARKS_MAX = 500

    /** [keepCustomerId]: when editing, the original customer stays allowed even if since deactivated. */
    fun validate(draft: PaymentDraft, customer: Customer?, today: LocalDate, keepCustomerId: Long? = null): DomainResult<ValidPayment> {
        val errors = ValidationErrors()
        errors.add(
            Field.CUSTOMER,
            when {
                customer == null -> ValidationCode.REQUIRED
                !customer.isActive && customer.id != keepCustomerId -> ValidationCode.INACTIVE
                else -> null
            },
        )
        errors.add(Field.ENTRY_DATE, CustomerValidator.dateCode(draft.date, today))
        val parsed = MoneyParser.parse(draft.amount)
        val amount = (parsed as? MoneyParser.Result.Valid)?.money?.takeUnless { it.isZero }
        errors.add(Field.AMOUNT, amountCode(parsed))
        if (draft.reference.trim().length > REFERENCE_MAX) errors.add(Field.PAYMENT_REFERENCE, ValidationCode.TOO_LONG)
        if (draft.remarks.trim().length > REMARKS_MAX) errors.add(Field.REMARKS, ValidationCode.TOO_LONG)
        return errors.result {
            ValidPayment(
                type = draft.type,
                customerId = checkNotNull(customer).id,
                date = checkNotNull(draft.date),
                amount = checkNotNull(amount),
                mode = draft.mode,
                reference = draft.reference.trim().ifEmpty { null },
                remarks = draft.remarks.trim().ifEmpty { null },
            )
        }
    }
}

private fun amountCode(parsed: MoneyParser.Result): ValidationCode? = when (parsed) {
    MoneyParser.Result.Blank -> ValidationCode.REQUIRED
    MoneyParser.Result.Invalid -> ValidationCode.INVALID_FORMAT
    MoneyParser.Result.TooLarge -> ValidationCode.OUT_OF_RANGE
    is MoneyParser.Result.Valid -> if (parsed.money.isZero) ValidationCode.MUST_BE_POSITIVE else null
}

/**
 * What a payment does to a customer's balance (FR-030, US4-1..4). An advance is not a separate record:
 * it is simply the part of the payment beyond what is owed in that direction, which turns the balance
 * the other way; later trades use it up through the running balance.
 *
 * @property outstanding what is owed in the payment's direction before it (the "Full amount", US4-4)
 * @property advance the part of the payment beyond [outstanding] (zero when there is none)
 * @property resultingBalance the customer's balance after the payment
 */
data class PaymentImpact(val outstanding: Money, val advance: Money, val resultingBalance: Money) {
    val createsAdvance: Boolean get() = advance.isPositive

    companion object {
        /** [balanceBefore]: the customer's balance without this payment (positive = customer owes the shop). */
        fun of(type: EntryType, balanceBefore: Money, amount: Money): PaymentImpact {
            val outstanding = outstanding(type, balanceBefore)
            val advance = if (amount > outstanding) amount - outstanding else Money.ZERO
            return PaymentImpact(outstanding, advance, balanceBefore + BalanceEffect.of(type, amount))
        }

        /** Owed in the direction of [type]: what the customer owes for a receipt, what the shop owes for a payment out. */
        fun outstanding(type: EntryType, balance: Money): Money = when (type) {
            EntryType.PAYMENT_RECEIVED -> if (balance.isPositive) balance else Money.ZERO
            EntryType.PAYMENT_MADE -> if (balance.isNegative) -balance else Money.ZERO
            else -> throw IllegalArgumentException("Not a payment type: $type")
        }
    }
}
