package com.eggbusinessmanager.data.repository

import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.entity.AppSettingEntity
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.model.ThemeMode
import com.eggbusinessmanager.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider

/**
 * Settings live in the `app_setting` table so they travel with backups.
 * The database is resolved lazily on the IO dispatcher, never on the main thread.
 */
class RoomSettingsRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SettingsRepository {

    override fun observeThemeMode(): Flow<ThemeMode> =
        flow { emitAll(database.get().appSettingDao().observe(KEY_THEME)) }
            .map(ThemeMode::fromStored)
            .distinctUntilChanged()
            .flowOn(io)

    override suspend fun setThemeMode(mode: ThemeMode) = withContext(io) {
        database.get().appSettingDao().upsert(AppSettingEntity(KEY_THEME, mode.name))
    }

    override fun observeAutoLockMinutes(): Flow<Int> =
        flow { emitAll(database.get().appSettingDao().observe(KEY_AUTO_LOCK)) }
            .map { stored ->
                stored?.toIntOrNull()?.takeIf { it in SettingsRepository.AUTO_LOCK_CHOICES }
                    ?: SettingsRepository.DEFAULT_AUTO_LOCK_MINUTES
            }
            .distinctUntilChanged()
            .flowOn(io)

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_AUTO_LOCK = "auto_lock_minutes"
    }
}
