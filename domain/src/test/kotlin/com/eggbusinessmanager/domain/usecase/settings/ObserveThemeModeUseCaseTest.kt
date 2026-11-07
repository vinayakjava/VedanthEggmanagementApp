package com.eggbusinessmanager.domain.usecase.settings

import com.eggbusinessmanager.domain.model.ThemeMode
import com.eggbusinessmanager.domain.repository.SettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ObserveThemeModeUseCaseTest {

    private val repository = object : SettingsRepository {
        val mode = MutableStateFlow(ThemeMode.SYSTEM)
        override fun observeThemeMode(): Flow<ThemeMode> = mode
        override suspend fun setThemeMode(mode: ThemeMode) {
            this.mode.value = mode
        }
        override fun observeAutoLockMinutes(): Flow<Int> = MutableStateFlow(SettingsRepository.DEFAULT_AUTO_LOCK_MINUTES)
    }

    @Test
    fun `emits the stored theme and follows changes`() = runTest {
        val useCase = ObserveThemeModeUseCase(repository)
        assertThat(useCase().first()).isEqualTo(ThemeMode.SYSTEM)

        repository.setThemeMode(ThemeMode.DARK)

        assertThat(useCase().first()).isEqualTo(ThemeMode.DARK)
    }
}
