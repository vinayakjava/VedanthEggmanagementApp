package com.eggbusinessmanager.domain.ledger

import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.QuantityMilli
import java.time.Instant
import java.time.LocalDate

/** The five kinds of money movement (data-model.md § ledger_entry). Stable names; stored as TEXT. */
enum class EntryType(val referencePrefix: String) {
    OPENING_BALANCE("OB"),
    SELL("S"),
    BUY("B"),
    PAYMENT_RECEIVED("RP"),
    PAYMENT_MADE("PC"),
}

enum class EntryStatus { ACTIVE, SUPERSEDED, VOIDED }

/** Direction of an opening balance carried over from paper records. */
enum class OpeningDirection { CUSTOMER_OWES, SHOP_OWES }

/** How a payment was made (FR-028). Stable names; stored as TEXT. */
enum class PaymentMode { CASH, UPI, BANK_TRANSFER, CHEQUE, OTHER }

/** Balance with direction, as shown to the owner (spec § Balance convention). */
sealed interface Balance {
    data class ToReceive(val amount: Money) : Balance
    data class ToPay(val amount: Money) : Balance
    data object Settled : Balance

    companion object {
        /** Positive = customer owes the shop; negative = the shop owes the customer. */
        fun of(net: Money): Balance = when {
            net.isPositive -> ToReceive(net)
            net.isNegative -> ToPay(net.abs())
            else -> Settled
        }
    }
}

/**
 * The ONLY place where entry sign rules live (BR-001..BR-005): sales and payments made raise what the
 * customer owes; purchases and payments received lower it; an opening balance follows its direction.
 */
object BalanceEffect {
    fun of(type: EntryType, amount: Money, opening: OpeningDirection? = null): Money = when (type) {
        EntryType.SELL, EntryType.PAYMENT_MADE -> amount
        EntryType.BUY, EntryType.PAYMENT_RECEIVED -> -amount
        EntryType.OPENING_BALANCE -> when (requireNotNull(opening) { "Opening balance needs a direction" }) {
            OpeningDirection.CUSTOMER_OWES -> amount
            OpeningDirection.SHOP_OWES -> -amount
        }
    }
}

/**
 * The trade part of a SELL/BUY entry. [piecesPerUnit] is the unit's size when the entry was saved
 * (FR-022): resizing the unit later never changes [pieces] or the total.
 */
data class TradeLine(
    val varietyId: Long,
    val unitId: Long,
    val quantity: QuantityMilli,
    val piecesPerUnit: Int,
    val pieces: Long,
    val pricePerUnit: Money,
)

/** The payment part of a RECEIVE PAYMENT / PAY CUSTOMER entry. */
data class PaymentLine(val mode: PaymentMode, val reference: String?)

/**
 * The business values of one entry, as every ledger write takes them. The balance effect is never
 * passed in: the repository always derives it with [BalanceEffect], the single sign rule.
 */
data class EntryValues(
    val type: EntryType,
    val customerId: Long,
    val date: LocalDate,
    val amount: Money,
    val trade: TradeLine? = null,
    val payment: PaymentLine? = null,
    val remarks: String? = null,
) {
    init {
        val isTrade = type == EntryType.SELL || type == EntryType.BUY
        val isPayment = type == EntryType.PAYMENT_RECEIVED || type == EntryType.PAYMENT_MADE
        require(isTrade == (trade != null)) { "Trade details are required exactly for SELL/BUY" }
        require(isPayment == (payment != null)) { "Payment details are required exactly for payments" }
    }

    val balanceEffect: Money get() = BalanceEffect.of(type, amount)
}

/** One stored revision of a ledger entry. */
data class LedgerEntry(
    val id: Long,
    val rootId: Long,
    val reference: String,
    val type: EntryType,
    val status: EntryStatus,
    val customerId: Long,
    val date: LocalDate,
    val amount: Money,
    val balanceEffect: Money,
    val openingDirection: OpeningDirection?,
    val remarks: String?,
    val createdAt: Instant,
    val trade: TradeLine? = null,
    val payment: PaymentLine? = null,
    /** The revision this one corrected, if any. */
    val supersedesId: Long? = null,
    /** Set on the last revision of an entry whose type was changed: the new entry's root (FR-033b). */
    val replacedByRootId: Long? = null,
    /** FR-023: the root of the entry saved together with this one (a sale and the payment taken on the spot). */
    val linkedRootId: Long? = null,
    val voidReason: String? = null,
    val voidedAt: Instant? = null,
)

/** A ledger row with the customer's balance after it (BR-011). */
data class LedgerLine(val entry: LedgerEntry, val runningBalance: Money)

/**
 * Per-type totals of a customer's active entries (US4-5, FR-031). [opening] is signed like a balance
 * (positive = customer owes). They always satisfy the balance formula:
 * balance = opening + sales − purchases − received + paid.
 */
data class LedgerTotals(
    val opening: Money = Money.ZERO,
    val sales: Money = Money.ZERO,
    val purchases: Money = Money.ZERO,
    val received: Money = Money.ZERO,
    val paid: Money = Money.ZERO,
) {
    /** The balance formula from the spec Overview, written out literally. */
    val balance: Money get() = sales - purchases - received + paid + opening
}

/**
 * A customer's full ledger: active lines oldest first with running balances, the closing (current)
 * balance, per-type totals, and deleted (voided) entries, which stay visible but never count (FR-034).
 */
data class CustomerLedger(
    val lines: List<LedgerLine>,
    val balance: Money,
    val deleted: List<LedgerEntry> = emptyList(),
    val totals: LedgerTotals = LedgerTotals(),
) {
    /** The balance after the last line; equal to [balance] by construction. */
    val closingBalance: Money get() = balance
}

/**
 * Pure recomputation of balances (contracts/domain-interfaces.md). Must equal the SQL aggregates;
 * only ACTIVE revisions count (voided and superseded rows never do).
 */
object LedgerCalculator {

    /** BR-011: by business date, then time recorded, then id. */
    private val LEDGER_ORDER = compareBy<LedgerEntry>({ it.date }, { it.createdAt }, { it.id })

    fun balance(entries: List<LedgerEntry>): Money =
        entries.filter { it.status == EntryStatus.ACTIVE }.fold(Money.ZERO) { sum, entry -> sum + entry.balanceEffect }

    fun runningLines(entries: List<LedgerEntry>): List<LedgerLine> {
        var running = Money.ZERO
        return entries.filter { it.status == EntryStatus.ACTIVE }
            .sortedWith(LEDGER_ORDER)
            .map { entry ->
                running += entry.balanceEffect
                LedgerLine(entry, running)
            }
    }

    fun ledger(entries: List<LedgerEntry>): CustomerLedger {
        val lines = runningLines(entries)
        return CustomerLedger(
            lines = lines,
            balance = lines.lastOrNull()?.runningBalance ?: Money.ZERO,
            deleted = entries.filter { it.status == EntryStatus.VOIDED }.sortedWith(LEDGER_ORDER),
            totals = totals(entries),
        )
    }

    /** Per-type totals of active entries. Payments and trades are counted as positive amounts. */
    fun totals(entries: List<LedgerEntry>): LedgerTotals = entries.filter { it.status == EntryStatus.ACTIVE }
        .fold(LedgerTotals()) { t, e ->
            when (e.type) {
                EntryType.OPENING_BALANCE -> t.copy(opening = t.opening + e.balanceEffect)
                EntryType.SELL -> t.copy(sales = t.sales + e.amount)
                EntryType.BUY -> t.copy(purchases = t.purchases + e.amount)
                EntryType.PAYMENT_RECEIVED -> t.copy(received = t.received + e.amount)
                EntryType.PAYMENT_MADE -> t.copy(paid = t.paid + e.amount)
            }
        }
}
