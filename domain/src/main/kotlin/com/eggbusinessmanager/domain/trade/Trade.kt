package com.eggbusinessmanager.domain.trade

import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.EntryValues
import com.eggbusinessmanager.domain.ledger.PaymentLine
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.ledger.TradeLine
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.MoneyParser
import com.eggbusinessmanager.domain.money.QuantityMilli
import com.eggbusinessmanager.domain.money.QuantityParser
import com.eggbusinessmanager.domain.money.TradeMath
import com.eggbusinessmanager.domain.validation.CustomerValidator
import java.time.LocalDate

/**
 * FR-023: the optional "Amount received now" (Sell) or "Amount paid now" (Buy). Saved as a separate
 * payment linked to the trade, on the same date, so the ledger shows both lines (BR-013).
 */
data class PaidNow(val amount: String = "", val mode: PaymentMode = PaymentMode.CASH)

/** What the owner typed on the Sell or Buy screen (FR-020). */
data class TradeDraft(
    val type: EntryType,
    val customerId: Long? = null,
    val date: LocalDate? = null,
    val varietyId: Long? = null,
    val unitId: Long? = null,
    val quantity: String = "",
    val price: String = "",
    val remarks: String = "",
    /** Only for a new trade; an existing one's payment is corrected on its own. */
    val paidNow: PaidNow = PaidNow(),
) {
    init {
        require(type == EntryType.SELL || type == EntryType.BUY) { "A trade is a SELL or a BUY" }
    }
}

/** A validated trade with its computed total and pieces, ready to store. */
data class ValidTrade(
    val type: EntryType,
    val customerId: Long,
    val date: LocalDate,
    val varietyId: Long,
    val unitId: Long,
    val piecesPerUnit: Int,
    val quantity: QuantityMilli,
    val pieces: Long,
    val pricePerUnit: Money,
    val total: Money,
    val remarks: String?,
    /** The amount taken on the spot (FR-023), at most [total]; null when nothing was paid now. */
    val paidNow: Money? = null,
    val paidMode: PaymentMode = PaymentMode.CASH,
)

/** The values a trade is stored with. */
fun ValidTrade.toValues() = EntryValues(
    type = type,
    customerId = customerId,
    date = date,
    amount = total,
    trade = TradeLine(varietyId, unitId, quantity, piecesPerUnit, pieces, pricePerUnit),
    remarks = remarks,
)

/** The linked payment for [ValidTrade.paidNow]: received for a sale, paid out for a purchase. */
fun ValidTrade.paymentValues(): EntryValues? = paidNow?.let { amount ->
    EntryValues(
        type = if (type == EntryType.SELL) EntryType.PAYMENT_RECEIVED else EntryType.PAYMENT_MADE,
        customerId = customerId,
        date = date,
        amount = amount,
        payment = PaymentLine(paidMode, null),
    )
}

/** Live total and pieces for the editor (FR-021): null parts until the inputs make sense. */
data class TradePreview(val total: Money?, val pieces: Long?)

/** The records a draft refers to, looked up before validation. */
data class TradeContext(
    val customer: Customer?,
    val variety: EggVariety?,
    val unit: MeasurementUnit?,
    val today: LocalDate,
    /** When editing, the original's customer/variety/unit stay allowed even if since deactivated. */
    val keepIds: KeepIds = KeepIds(),
) {
    data class KeepIds(
        val customerId: Long? = null,
        val varietyId: Long? = null,
        val unitId: Long? = null,
        /** The unit size the original was saved with; kept while the unit is unchanged (FR-022). */
        val unitSize: Int? = null,
    )
}

/** Trade rules (spec § Validation Rules, FR-020–FR-022). */
object TradeValidator {
    const val MAX_PIECES = 10_000_000L
    val MAX_PRICE = Money(100_000_000L) // ₹10,00,000.00 per unit
    const val REMARKS_MAX = 500

    fun preview(quantity: String, price: String, unit: MeasurementUnit?): TradePreview {
        val q = (QuantityParser.parse(quantity) as? QuantityParser.Result.Valid)?.quantity
        val p = (MoneyParser.parse(price) as? MoneyParser.Result.Valid)?.money
        val total = if (q != null && p != null && p <= MAX_PRICE) TradeMath.lineTotal(q, p) else null
        val pieces = if (q != null && unit != null) TradeMath.pieces(q, unit.piecesPerUnit) else null
        return TradePreview(total, pieces)
    }

    fun validate(draft: TradeDraft, context: TradeContext): DomainResult<ValidTrade> {
        val errors = ValidationErrors()
        val keep = context.keepIds
        val customer = context.customer
        val variety = context.variety
        val unit = context.unit
        errors.add(Field.CUSTOMER, recordCode(customer != null, customer?.isActive, customer?.id == keep.customerId))
        errors.add(Field.ENTRY_DATE, CustomerValidator.dateCode(draft.date, context.today))
        errors.add(Field.VARIETY, recordCode(variety != null, variety?.isActive, variety?.id == keep.varietyId))
        errors.add(Field.UNIT, recordCode(unit != null, unit?.isActive, unit?.id == keep.unitId))

        val quantity = quantity(draft.quantity, errors)
        val pieces = if (quantity != null && unit != null) TradeMath.pieces(quantity, unit.piecesPerUnit) else null
        if (quantity != null && unit != null) errors.add(Field.QUANTITY, piecesCode(pieces))
        val price = price(draft.price, errors)
        val total = if (quantity != null && price != null) TradeMath.lineTotal(quantity, price) else null
        if (total != null && (total < Money(1) || total > Money.MAX_ENTRY)) errors.add(Field.TOTAL, ValidationCode.OUT_OF_RANGE)
        if (draft.remarks.trim().length > REMARKS_MAX) errors.add(Field.REMARKS, ValidationCode.TOO_LONG)
        val paidNow = paidNow(draft.paidNow.amount, total, errors)

        return errors.result {
            ValidTrade(
                type = draft.type,
                customerId = checkNotNull(customer).id,
                date = checkNotNull(draft.date),
                varietyId = checkNotNull(variety).id,
                unitId = checkNotNull(unit).id,
                piecesPerUnit = unit.piecesPerUnit,
                quantity = checkNotNull(quantity),
                pieces = checkNotNull(pieces),
                pricePerUnit = checkNotNull(price),
                total = checkNotNull(total),
                remarks = draft.remarks.trim().ifEmpty { null },
                paidNow = paidNow,
                paidMode = draft.paidNow.mode,
            )
        }
    }

    /** Blank or zero means nothing was paid now; more than the trade total belongs in a separate payment. */
    private fun paidNow(text: String, total: Money?, errors: ValidationErrors): Money? = when (val parsed = MoneyParser.parse(text)) {
        MoneyParser.Result.Blank -> null
        MoneyParser.Result.Invalid -> null.also { errors.add(Field.PAID_NOW, ValidationCode.INVALID_FORMAT) }
        MoneyParser.Result.TooLarge -> null.also { errors.add(Field.PAID_NOW, ValidationCode.OUT_OF_RANGE) }
        is MoneyParser.Result.Valid -> when {
            parsed.money.isZero -> null
            total != null && parsed.money > total -> null.also { errors.add(Field.PAID_NOW, ValidationCode.OUT_OF_RANGE) }
            else -> parsed.money
        }
    }

    /** A referenced customer/variety/unit must exist and be active, unless it is the edited entry's own. */
    private fun recordCode(present: Boolean, active: Boolean?, isKept: Boolean): ValidationCode? = when {
        !present -> ValidationCode.REQUIRED
        active == false && !isKept -> ValidationCode.INACTIVE
        else -> null
    }

    private fun quantity(text: String, errors: ValidationErrors): QuantityMilli? = when (val parsed = QuantityParser.parse(text)) {
        QuantityParser.Result.Blank -> null.also { errors.add(Field.QUANTITY, ValidationCode.REQUIRED) }
        QuantityParser.Result.Invalid -> null.also { errors.add(Field.QUANTITY, ValidationCode.INVALID_FORMAT) }
        is QuantityParser.Result.Valid -> parsed.quantity.takeIf { it.value > 0 }
            ?: null.also { errors.add(Field.QUANTITY, ValidationCode.MUST_BE_POSITIVE) }
    }

    private fun piecesCode(pieces: Long?): ValidationCode? = when {
        pieces == null -> ValidationCode.NOT_WHOLE_PIECES
        pieces > MAX_PIECES -> ValidationCode.OUT_OF_RANGE
        else -> null
    }

    private fun price(text: String, errors: ValidationErrors): Money? = when (val parsed = MoneyParser.parse(text)) {
        MoneyParser.Result.Blank -> null.also { errors.add(Field.PRICE, ValidationCode.REQUIRED) }
        MoneyParser.Result.Invalid -> null.also { errors.add(Field.PRICE, ValidationCode.INVALID_FORMAT) }
        MoneyParser.Result.TooLarge -> null.also { errors.add(Field.PRICE, ValidationCode.OUT_OF_RANGE) }
        is MoneyParser.Result.Valid -> when {
            parsed.money.isZero -> null.also { errors.add(Field.PRICE, ValidationCode.MUST_BE_POSITIVE) }
            parsed.money > MAX_PRICE -> null.also { errors.add(Field.PRICE, ValidationCode.OUT_OF_RANGE) }
            else -> parsed.money
        }
    }
}
