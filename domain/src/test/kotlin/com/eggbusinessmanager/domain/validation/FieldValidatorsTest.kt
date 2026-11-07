package com.eggbusinessmanager.domain.validation

import com.eggbusinessmanager.domain.error.ValidationCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FieldValidatorsTest {

    @Test
    fun `mobile numbers are normalized to 10 digits`() {
        listOf("9876543210", "98765 43210", "98765-43210", "+91 98765 43210", "+919876543210", "919876543210", "09876543210")
            .forEach { assertThat(FieldValidators.normalizeMobile(it)).isEqualTo("9876543210") }
    }

    @Test
    fun `invalid mobile numbers are rejected`() {
        listOf("5876543210", "987654321", "98765432101", "+44 9876543210", "98765abcde", "", "+9876543210")
            .forEach { assertThat(FieldValidators.normalizeMobile(it)).isNull() }
    }

    @Test
    fun `mobile codes`() {
        assertThat(FieldValidators.mobileCode("  ")).isEqualTo(ValidationCode.REQUIRED)
        assertThat(FieldValidators.mobileCode("12345")).isEqualTo(ValidationCode.INVALID_FORMAT)
        assertThat(FieldValidators.mobileCode("6000000000")).isNull()
    }

    @Test
    fun `email is optional, trimmed and lower-cased`() {
        assertThat(FieldValidators.optionalEmailCode("")).isNull()
        assertThat(FieldValidators.optionalEmailCode(" Owner@Shop.co.in ")).isNull()
        assertThat(FieldValidators.normalizeEmail(" Owner@Shop.co.in ")).isEqualTo("owner@shop.co.in")
        assertThat(FieldValidators.normalizeEmail("   ")).isNull()
    }

    @Test
    fun `malformed or overlong email is rejected`() {
        listOf("owner", "owner@", "@shop.com", "owner@shop", "owner shop@x.com").forEach {
            assertThat(FieldValidators.optionalEmailCode(it)).isEqualTo(ValidationCode.INVALID_FORMAT)
        }
        val long = "a".repeat(250) + "@x.in"
        assertThat(FieldValidators.optionalEmailCode(long)).isEqualTo(ValidationCode.TOO_LONG)
    }

    @Test
    fun `required text lengths are checked after trimming`() {
        assertThat(FieldValidators.requiredTextCode("   ", 2, 80)).isEqualTo(ValidationCode.REQUIRED)
        assertThat(FieldValidators.requiredTextCode(" A ", 2, 80)).isEqualTo(ValidationCode.TOO_SHORT)
        assertThat(FieldValidators.requiredTextCode("x".repeat(81), 2, 80)).isEqualTo(ValidationCode.TOO_LONG)
        assertThat(FieldValidators.requiredTextCode("  Ab  ", 2, 80)).isNull()
    }

    @Test
    fun `optional text only has a maximum`() {
        assertThat(FieldValidators.optionalTextCode("", 250)).isNull()
        assertThat(FieldValidators.optionalTextCode("x".repeat(251), 250)).isEqualTo(ValidationCode.TOO_LONG)
    }

    @Test
    fun `PIN code is 6 digits not starting with 0`() {
        assertThat(FieldValidators.pinCodeCode(" 422001 ")).isNull()
        assertThat(FieldValidators.pinCodeCode("")).isEqualTo(ValidationCode.REQUIRED)
        listOf("022001", "42200", "4220011", "42a001").forEach {
            assertThat(FieldValidators.pinCodeCode(it)).isEqualTo(ValidationCode.INVALID_FORMAT)
        }
    }
}
