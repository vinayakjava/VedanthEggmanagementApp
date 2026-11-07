package com.eggbusinessmanager.domain.validation

import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.customer.OpeningBalance
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class CustomerValidatorTest {

    private val today = LocalDate.of(2026, 11, 8)

    private fun errors(draft: CustomerDraft) =
        ((CustomerValidator.validate(draft, today) as DomainResult.Err).error as DomainError.Validation).fields

    private fun valid(draft: CustomerDraft) = (CustomerValidator.validate(draft, today) as DomainResult.Ok<ValidCustomer>).value

    @Test
    fun `a name alone is enough`() {
        assertThat(valid(CustomerDraft(name = "  Ram Traders "))).isEqualTo(ValidCustomer("Ram Traders", null, null, null, null))
    }

    @Test
    fun `name is required and 2 to 80 characters`() {
        assertThat(errors(CustomerDraft(name = " "))).containsExactly(Field.CUSTOMER_NAME, ValidationCode.REQUIRED)
        assertThat(errors(CustomerDraft(name = "R"))).containsExactly(Field.CUSTOMER_NAME, ValidationCode.TOO_SHORT)
        assertThat(errors(CustomerDraft(name = "R".repeat(81)))).containsExactly(Field.CUSTOMER_NAME, ValidationCode.TOO_LONG)
    }

    @Test
    fun `mobile is optional but must be valid, and is normalized`() {
        assertThat(valid(CustomerDraft(name = "Ram", mobile = "+91 98765-43210")).mobile).isEqualTo("9876543210")
        assertThat(errors(CustomerDraft(name = "Ram", mobile = "12345"))).containsExactly(Field.MOBILE, ValidationCode.INVALID_FORMAT)
    }

    @Test
    fun `address and notes have maximum lengths`() {
        val fields = errors(CustomerDraft(name = "Ram", address = "a".repeat(251), notes = "n".repeat(501)))
        assertThat(fields).containsExactly(Field.ADDRESS, ValidationCode.TOO_LONG, Field.NOTES, ValidationCode.TOO_LONG)
    }

    @Test
    fun `opening balance becomes an amount with direction and date`() {
        val draft = CustomerDraft(
            name = "Ram", openingAmount = "1,500", openingDirection = OpeningDirection.SHOP_OWES, openingDate = today.minusDays(3),
        )
        assertThat(valid(draft).opening).isEqualTo(OpeningBalance(Money.rupees(1_500), OpeningDirection.SHOP_OWES, today.minusDays(3)))
    }

    @Test
    fun `opening amount must be a valid amount within range`() {
        assertThat(errors(CustomerDraft(name = "Ram", openingAmount = "12.345", openingDate = today)))
            .containsExactly(Field.OPENING_AMOUNT, ValidationCode.INVALID_FORMAT)
        assertThat(errors(CustomerDraft(name = "Ram", openingAmount = "1000000000", openingDate = today)))
            .containsExactly(Field.OPENING_AMOUNT, ValidationCode.TOO_LONG)
    }

    @Test
    fun `opening date is required with an amount, not in the future, not before 2000`() {
        assertThat(errors(CustomerDraft(name = "Ram", openingAmount = "10")))
            .containsExactly(Field.OPENING_DATE, ValidationCode.REQUIRED)
        assertThat(errors(CustomerDraft(name = "Ram", openingAmount = "10", openingDate = today.plusDays(1))))
            .containsExactly(Field.OPENING_DATE, ValidationCode.DATE_IN_FUTURE)
        assertThat(errors(CustomerDraft(name = "Ram", openingAmount = "10", openingDate = LocalDate.of(1999, 12, 31))))
            .containsExactly(Field.OPENING_DATE, ValidationCode.DATE_TOO_EARLY)
        assertThat(valid(CustomerDraft(name = "Ram", openingAmount = "10", openingDate = LocalDate.of(2000, 1, 1))).opening).isNotNull()
    }

    @Test
    fun `no amount means no opening balance and the date is not checked`() {
        assertThat(valid(CustomerDraft(name = "Ram", openingAmount = " ", openingDate = today.plusDays(9))).opening).isNull()
    }

    @Test
    fun `search keys ignore case, spacing and width`() {
        assertThat(SearchKey.of("  RAM   Traders ")).isEqualTo("ram traders")
        assertThat(SearchKey.of("ＲＡＭ")).isEqualTo("ram")
        assertThat(SearchKey.digits("+91 98765-43210")).isEqualTo("919876543210")
    }
}
