package com.eggbusinessmanager.domain.ledger

import com.eggbusinessmanager.domain.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class LedgerTest {

    private val amount = Money.rupees(1000)

    @Test
    fun `balance effect follows BR-001 to BR-005`() {
        assertThat(BalanceEffect.of(EntryType.SELL, amount)).isEqualTo(amount)
        assertThat(BalanceEffect.of(EntryType.PAYMENT_MADE, amount)).isEqualTo(amount)
        assertThat(BalanceEffect.of(EntryType.BUY, amount)).isEqualTo(-amount)
        assertThat(BalanceEffect.of(EntryType.PAYMENT_RECEIVED, amount)).isEqualTo(-amount)
        assertThat(BalanceEffect.of(EntryType.OPENING_BALANCE, amount, OpeningDirection.CUSTOMER_OWES)).isEqualTo(amount)
        assertThat(BalanceEffect.of(EntryType.OPENING_BALANCE, amount, OpeningDirection.SHOP_OWES)).isEqualTo(-amount)
    }

    @Test
    fun `an opening balance needs a direction`() {
        assertThrows(IllegalArgumentException::class.java) { BalanceEffect.of(EntryType.OPENING_BALANCE, amount) }
    }

    @Test
    fun `balance direction labels`() {
        assertThat(Balance.of(Money(150_000))).isEqualTo(Balance.ToReceive(Money(150_000)))
        assertThat(Balance.of(Money(-150_000))).isEqualTo(Balance.ToPay(Money(150_000)))
        assertThat(Balance.of(Money.ZERO)).isEqualTo(Balance.Settled)
    }

    @Test
    fun `one customer can be both buyer and supplier with one netted balance`() {
        val entries = listOf(
            entry(1, EntryType.SELL, 5_000, day = 1),
            entry(2, EntryType.BUY, 8_000, day = 2),
            entry(3, EntryType.PAYMENT_MADE, 1_000, day = 3),
        )
        // 5,000 sold − 8,000 bought + 1,000 paid to them = shop still owes 2,000.
        assertThat(Balance.of(LedgerCalculator.balance(entries))).isEqualTo(Balance.ToPay(Money.rupees(2_000)))
    }

    @Test
    fun `only active revisions count`() {
        val entries = listOf(
            entry(1, EntryType.SELL, 1_000, day = 1),
            entry(2, EntryType.SELL, 9_999, day = 1, status = EntryStatus.SUPERSEDED),
            entry(3, EntryType.SELL, 7_777, day = 1, status = EntryStatus.VOIDED),
        )
        assertThat(LedgerCalculator.balance(entries)).isEqualTo(Money.rupees(1_000))
        assertThat(LedgerCalculator.runningLines(entries).map { it.entry.id }).containsExactly(1L)
    }

    @Test
    fun `running balance is ordered by date, then time recorded, then id`() {
        val entries = listOf(
            entry(3, EntryType.PAYMENT_RECEIVED, 300, day = 2, minute = 0),
            entry(1, EntryType.OPENING_BALANCE, 1_000, day = 1, opening = OpeningDirection.CUSTOMER_OWES),
            entry(5, EntryType.SELL, 50, day = 2, minute = 5),
            entry(4, EntryType.SELL, 40, day = 2, minute = 5),
        )

        val lines = LedgerCalculator.runningLines(entries)

        assertThat(lines.map { it.entry.id }).containsExactly(1L, 3L, 4L, 5L).inOrder()
        assertThat(lines.map { it.runningBalance }).containsExactly(
            Money.rupees(1_000), Money.rupees(700), Money.rupees(740), Money.rupees(790),
        ).inOrder()
        assertThat(LedgerCalculator.ledger(entries).balance).isEqualTo(Money.rupees(790))
    }

    @Test
    fun `an empty ledger is settled`() {
        assertThat(LedgerCalculator.ledger(emptyList())).isEqualTo(CustomerLedger(emptyList(), Money.ZERO))
    }

    private fun entry(
        id: Long,
        type: EntryType,
        rupees: Long,
        day: Int,
        minute: Long = 0,
        status: EntryStatus = EntryStatus.ACTIVE,
        opening: OpeningDirection? = null,
    ): LedgerEntry {
        val amount = Money.rupees(rupees)
        return LedgerEntry(
            id = id, rootId = id, reference = "X-$id", type = type, status = status, customerId = 1,
            date = LocalDate.of(2026, 11, day), amount = amount, balanceEffect = BalanceEffect.of(type, amount, opening),
            openingDirection = opening, remarks = null, createdAt = Instant.parse("2026-11-08T10:00:00Z").plusSeconds(minute * 60),
        )
    }
}
