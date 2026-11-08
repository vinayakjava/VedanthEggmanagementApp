package com.eggbusinessmanager.data.repository

import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.entity.AppSettingEntity
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.model.ThemeMode
import com.eggbusinessmanager.domain.repository.SettingsRepository
import com.eggbusinessmanager.domain.settings.AppPreferences
import com.eggbusinessmanager.domain.settings.AppPreferencesRepository
import com.eggbusinessmanager.domain.settings.CurrencySymbol
import com.eggbusinessmanager.domain.settings.DateStyle
import com.eggbusinessmanager.domain.settings.DigitGrouping
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
) : SettingsRepository, AppPreferencesRepository {

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

    override suspend fun setAutoLockMinutes(minutes: Int) = withContext(io) {
        database.get().appSettingDao().upsert(AppSettingEntity(KEY_AUTO_LOCK, minutes.toString()))
    }

    /** Unknown or missing values fall back to defaults, so a backup from another version still opens. */
    override fun observePreferences(): Flow<AppPreferences> = flow {
        val dao = database.get().appSettingDao()
        emitAll(
            combine(
                dao.observe(KEY_CURRENCY),
                dao.observe(KEY_GROUPING),
                dao.observe(KEY_DATE),
                dao.observe(KEY_DEFAULT_UNIT),
            ) { c, g, d, u ->
                val defaults = AppPreferences()
                AppPreferences(
                    currencySymbol = CurrencySymbol.entries.firstOrNull { it.name == c } ?: defaults.currencySymbol,
                    grouping = DigitGrouping.entries.firstOrNull { it.name == g } ?: defaults.grouping,
                    dateStyle = DateStyle.entries.firstOrNull { it.name == d } ?: defaults.dateStyle,
                    defaultUnitId = u?.toLongOrNull(),
                )
            },
        )
    }.distinctUntilChanged().flowOn(io)

    override suspend fun setPreferences(preferences: AppPreferences) = withContext(io) {
        val dao = database.get().appSettingDao()
        dao.upsert(AppSettingEntity(KEY_CURRENCY, preferences.currencySymbol.name))
        dao.upsert(AppSettingEntity(KEY_GROUPING, preferences.grouping.name))
        dao.upsert(AppSettingEntity(KEY_DATE, preferences.dateStyle.name))
        dao.upsert(AppSettingEntity(KEY_DEFAULT_UNIT, preferences.defaultUnitId?.toString().orEmpty()))
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_AUTO_LOCK = "auto_lock_minutes"
        const val KEY_CURRENCY = "currency_symbol"
        const val KEY_GROUPING = "digit_grouping"
        const val KEY_DATE = "date_style"
        const val KEY_DEFAULT_UNIT = "default_unit_id"
    }
}
