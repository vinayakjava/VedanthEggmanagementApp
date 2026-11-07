package com.eggbusinessmanager.domain.usecase.settings

import com.eggbusinessmanager.domain.model.ThemeMode
import com.eggbusinessmanager.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveThemeModeUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke(): Flow<ThemeMode> = settingsRepository.observeThemeMode()
}
