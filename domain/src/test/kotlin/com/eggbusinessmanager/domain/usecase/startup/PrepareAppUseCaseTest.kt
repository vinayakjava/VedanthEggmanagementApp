package com.eggbusinessmanager.domain.usecase.startup

import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.model.StartupState
import com.eggbusinessmanager.domain.repository.StartupRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrepareAppUseCaseTest {

    private val silentLogger = object : AppLogger {
        override fun debug(tag: String, message: () -> String) = Unit
        override fun info(tag: String, message: () -> String) = Unit
        override fun warn(tag: String, throwable: Throwable?, message: () -> String) = Unit
        override fun error(tag: String, throwable: Throwable?, message: () -> String) = Unit
    }

    @Test
    fun `returns repository state`() = runTest {
        val useCase = PrepareAppUseCase(repository { StartupState.Ready }, silentLogger)
        assertThat(useCase()).isEqualTo(StartupState.Ready)
    }

    @Test
    fun `unexpected exception becomes Failed UNKNOWN instead of crashing`() = runTest {
        val useCase = PrepareAppUseCase(repository { error("disk exploded") }, silentLogger)
        assertThat(useCase()).isEqualTo(StartupState.Failed(StartupState.Failed.Reason.UNKNOWN))
    }

    private fun repository(block: () -> StartupState) = object : StartupRepository {
        override suspend fun prepare(): StartupState = block()
    }
}
