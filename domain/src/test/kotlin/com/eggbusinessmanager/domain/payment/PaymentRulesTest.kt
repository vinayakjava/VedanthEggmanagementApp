package com.eggbusinessmanager.domain.payment

import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_MADE
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_RECEIVED
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PaymentRulesTest {

    private val today = LocalDate.of(2026, 11, 8)
    private val ram = Customer(1, "Ram", null, null, null, true, Instant.EPOCH, Instant.EPOCH)
    private val receive = PaymentDraft(PAYMENT_RECEIVED, ram.id, today, amount = "1500")

    private fun rs(rupees: Long) = Money.rupees(rupees)

    private fun errors(draft: PaymentDraft, customer: Customer? = ram) =
        ((PaymentValidator.validate(draft, customer, today) as DomainResult.Err).error as DomainError.Validation).fields

    // ---- Validation (FR-028) ----

    @Test
    fun `a valid payment defaults to cash and trims optional text`() {
        val valid = (PaymentValidator.validate(receive.copy(reference = "  UPI123 ", remarks = " "), ram, today) as DomainResult.Ok).value
        assertThat(valid).isEqualTo(ValidPayment(PAYMENT_RECEIVED, ram.id, today, rs(1_500), PaymentMode.CASH, "UPI123", null))
        assertThat(valid.toValues().payment?.mode).isEqualTo(PaymentMode.CASH)
        assertThat(valid.toValues().balanceEffect).isEqualTo(-rs(1_500))
    }

    @Test
    fun `amount must be a positive amount with at most 2 decimals`() {
        assertThat(errors(receive.copy(amount = ""))).containsExactly(Field.AMOUNT, ValidationCode.REQUIRED)
        assertThat(errors(receive.copy(amount = "0"))).containsExactly(Field.AMOUNT, ValidationCode.MUST_BE_POSITIVE)
        assertThat(errors(receive.copy(amount = "1.234"))).containsExactly(Field.AMOUNT, ValidationCode.INVALID_FORMAT)
        assertThat(errors(receive.copy(amount = "1000000000"))).containsExactly(Field.AMOUNT, ValidationCode.OUT_OF_RANGE)
    }

    @Test
    fun `customer, date, reference and remarks rules`() {
        assertThat(errors(receive, customer = null)).containsExactly(Field.CUSTOMER, ValidationCode.REQUIRED)
        assertThat(errors(receive, customer = ram.copy(isActive = false))).containsExactly(Field.CUSTOMER, ValidationCode.INACTIVE)
        assertThat(errors(receive.copy(date = today.plusDays(1)))).containsExactly(Field.ENTRY_DATE, ValidationCode.DATE_IN_FUTURE)
        assertThat(errors(receive.copy(reference = "r".repeat(51)))).containsExactly(Field.PAYMENT_REFERENCE, ValidationCode.TOO_LONG)
        assertThat(errors(receive.copy(remarks = "r".repeat(501)))).containsExactly(Field.REMARKS, ValidationCode.TOO_LONG)
        // Editing keeps a since-deactivated customer allowed.
        assertThat(PaymentValidator.validate(receive, ram.copy(isActive = false), today, keepCustomerId = ram.id))
            .isInstanceOf(DomainResult.Ok::class.java)
    }

    @Test
    fun `a payment is only RECEIVE or PAY`() {
        assertThrows(IllegalArgumentException::class.java) { PaymentDraft(EntryType.SELL) }
    }

    // ---- Advance and impact (FR-030, US4-1..4) ----

    @Test
    fun `US4-1 receiving exactly what is owed settles the customer`() {
        val impact = PaymentImpact.of(PAYMENT_RECEIVED, balanceBefore = rs(2_000), amount = rs(2_000))
        assertThat(impact).isEqualTo(PaymentImpact(outstanding = rs(2_000), advance = Money.ZERO, resultingBalance = Money.ZERO))
        assertThat(impact.createsAdvance).isFalse()
    }

    @Test
    fun `US4-2 receiving more than owed holds the extra as an advance and the shop owes it`() {
        val impact = PaymentImpact.of(PAYMENT_RECEIVED, balanceBefore = rs(2_000), amount = rs(2_500))
        assertThat(impact.advance).isEqualTo(rs(500))
        assertThat(impact.resultingBalance).isEqualTo(-rs(500))
        assertThat(impact.createsAdvance).isTrue()
    }

    @Test
    fun `part payment leaves the rest owed, no advance`() {
        val impact = PaymentImpact.of(PAYMENT_RECEIVED, balanceBefore = rs(2_000), amount = rs(1_200))
        assertThat(impact).isEqualTo(PaymentImpact(rs(2_000), Money.ZERO, rs(800)))
    }

    @Test
    fun `US4-3 paying the shop's debt settles it, paying more is an advance to the supplier`() {
        assertThat(PaymentImpact.of(PAYMENT_MADE, balanceBefore = -rs(3_000), amount = rs(3_000)))
            .isEqualTo(PaymentImpact(rs(3_000), Money.ZERO, Money.ZERO))
        val over = PaymentImpact.of(PAYMENT_MADE, balanceBefore = -rs(3_000), amount = rs(3_400))
        assertThat(over.advance).isEqualTo(rs(400))
        assertThat(over.resultingBalance).isEqualTo(rs(400))
    }

    @Test
    fun `when nothing is owed in that direction the whole payment is an advance`() {
        // Settled customer pays in advance.
        assertThat(PaymentImpact.of(PAYMENT_RECEIVED, Money.ZERO, rs(5_000))).isEqualTo(PaymentImpact(Money.ZERO, rs(5_000), -rs(5_000)))
        // Shop already owes the customer, and the customer pays even more in.
        assertThat(PaymentImpact.of(PAYMENT_RECEIVED, -rs(300), rs(100))).isEqualTo(PaymentImpact(Money.ZERO, rs(100), -rs(400)))
        // Customer owes the shop, yet the shop pays the customer: all of it is an advance to them.
        assertThat(PaymentImpact.of(PAYMENT_MADE, rs(700), rs(200))).isEqualTo(PaymentImpact(Money.ZERO, rs(200), rs(900)))
    }

    @Test
    fun `full amount is what is owed in the payment's direction`() {
        assertThat(PaymentImpact.outstanding(PAYMENT_RECEIVED, rs(1_250))).isEqualTo(rs(1_250))
        assertThat(PaymentImpact.outstanding(PAYMENT_RECEIVED, -rs(1_250))).isEqualTo(Money.ZERO)
        assertThat(PaymentImpact.outstanding(PAYMENT_MADE, -rs(1_250))).isEqualTo(rs(1_250))
        assertThat(PaymentImpact.outstanding(PAYMENT_MADE, rs(1_250))).isEqualTo(Money.ZERO)
        assertThat(PaymentImpact.outstanding(PAYMENT_MADE, Money.ZERO)).isEqualTo(Money.ZERO)
        assertThrows(IllegalArgumentException::class.java) { PaymentImpact.outstanding(EntryType.BUY, Money.ZERO) }
    }

    @Test
    fun `amounts stay exact to the paisa`() {
        val impact = PaymentImpact.of(PAYMENT_RECEIVED, Money.rupees(1_000, 1), Money.rupees(999, 99))
        assertThat(impact.resultingBalance).isEqualTo(Money(2))
    }
}
