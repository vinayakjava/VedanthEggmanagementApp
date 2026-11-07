package com.eggbusinessmanager.domain.repository

import com.eggbusinessmanager.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Typed access to backed-up app settings. More settings are added with their features. */
interface SettingsRepository {
    fun observeThemeMode(): Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** Auto-lock after this many minutes of inactivity or background (FR-007; 1, 5, 15 or 30; default 5). */
    fun observeAutoLockMinutes(): Flow<Int>

    companion object {
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
        val AUTO_LOCK_CHOICES = listOf(1, 5, 15, 30)
    }
}
