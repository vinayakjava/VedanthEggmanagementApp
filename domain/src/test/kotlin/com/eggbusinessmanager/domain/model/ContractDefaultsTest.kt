package com.eggbusinessmanager.domain.model

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.logging.AppLogger
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the default values of the shared domain contracts that every layer relies on. */
class ContractDefaultsTest {

    @Test
    fun `logger warn and error default to no throwable`() {
        val throwables = mutableListOf<Throwable?>()
        val logger = object : AppLogger {
            override fun debug(tag: String, message: () -> String) = Unit
            override fun info(tag: String, message: () -> String) = Unit
            override fun warn(tag: String, throwable: Throwable?, message: () -> String) { throwables += throwable }
            override fun error(tag: String, throwable: Throwable?, message: () -> String) { throwables += throwable }
        }

        logger.warn("T") { "w" }
        logger.error("T") { "e" }

        assertThat(throwables).containsExactly(null, null)
    }

    @Test
    fun `unexpected error carries no cause by default and validation keeps its fields`() {
        assertThat(DomainError.Unexpected().cause).isNull()
        assertThat(DomainError.Validation(Field.SHOP_NAME, ValidationCode.REQUIRED).fields)
            .containsExactly(Field.SHOP_NAME, ValidationCode.REQUIRED)
    }

    @Test
    fun `update required reports both schema versions`() {
        val state = StartupState.UpdateRequired(storedVersion = 3, supportedVersion = 2)
        assertThat(state.storedVersion).isGreaterThan(state.supportedVersion)
    }
}
