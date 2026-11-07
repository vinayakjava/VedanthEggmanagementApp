package com.eggbusinessmanager.domain.ledger

import com.eggbusinessmanager.domain.money.Money
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

/** One stored revision of a ledger entry. Trade and payment details arrive with US3/US4. */
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
)

/** A ledger row with the customer's balance after it (BR-011). */
data class LedgerLine(val entry: LedgerEntry, val runningBalance: Money)

/** A customer's full ledger: lines oldest first, and the current balance. */
data class CustomerLedger(val lines: List<LedgerLine>, val balance: Money)

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

    fun ledger(entries: List<LedgerEntry>): CustomerLedger =
        runningLines(entries).let { lines -> CustomerLedger(lines, lines.lastOrNull()?.runningBalance ?: Money.ZERO) }
}
