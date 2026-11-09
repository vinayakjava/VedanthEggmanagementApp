package com.eggbusinessmanager.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.eggbusinessmanager.domain.backup.AutoBackupSettings
import com.eggbusinessmanager.domain.backup.AutoBackupSettingsRepository
import com.eggbusinessmanager.domain.backup.BackupFrequency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic backup settings in the device-only DataStore (no-backup storage): a restore from another
 * phone never changes this phone's schedule. Unreadable or out-of-range values fall back to defaults.
 */
@Singleton
class DataStoreAutoBackupSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AutoBackupSettingsRepository {

    override fun observe(): Flow<AutoBackupSettings> = dataStore.data.map { p ->
        val defaults = AutoBackupSettings()
        AutoBackupSettings(
            enabled = p[ENABLED] ?: defaults.enabled,
            frequency = p[FREQUENCY]?.let { v -> BackupFrequency.entries.firstOrNull { it.name == v } } ?: defaults.frequency,
            preferredTime = p[MINUTE_OF_DAY]?.takeIf { it in 0 until MINUTES_PER_DAY }
                ?.let { LocalTime.of(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR) } ?: defaults.preferredTime,
            dayOfWeek = p[DAY_OF_WEEK]?.takeIf { it in 1..DAYS_PER_WEEK }?.let(DayOfWeek::of) ?: defaults.dayOfWeek,
            dayOfMonth = p[DAY_OF_MONTH]?.takeIf { it in 1..AutoBackupSettings.MAX_DAY_OF_MONTH } ?: defaults.dayOfMonth,
            keepLast = p[KEEP_LAST]?.takeIf { it in AutoBackupSettings.RETENTION_CHOICES } ?: defaults.keepLast,
            wifiOnly = p[WIFI_ONLY] ?: defaults.wifiOnly,
            onlyWhileCharging = p[CHARGING] ?: defaults.onlyWhileCharging,
            enabledSince = p[ENABLED_SINCE]?.let(Instant::ofEpochMilli),
        )
    }.distinctUntilChanged()

    override suspend fun save(settings: AutoBackupSettings) {
        dataStore.edit { p ->
            p[ENABLED] = settings.enabled
            p[FREQUENCY] = settings.frequency.name
            p[MINUTE_OF_DAY] = settings.preferredTime.hour * MINUTES_PER_HOUR + settings.preferredTime.minute
            p[DAY_OF_WEEK] = settings.dayOfWeek.value
            p[DAY_OF_MONTH] = settings.dayOfMonth
            p[KEEP_LAST] = settings.keepLast
            p[WIFI_ONLY] = settings.wifiOnly
            p[CHARGING] = settings.onlyWhileCharging
            val since = settings.enabledSince
            if (since == null) p.remove(ENABLED_SINCE) else p[ENABLED_SINCE] = since.toEpochMilli()
        }
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * 60
        const val DAYS_PER_WEEK = 7
        val ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val FREQUENCY = stringPreferencesKey("auto_backup_frequency")
        val MINUTE_OF_DAY = intPreferencesKey("auto_backup_minute_of_day")
        val DAY_OF_WEEK = intPreferencesKey("auto_backup_day_of_week")
        val DAY_OF_MONTH = intPreferencesKey("auto_backup_day_of_month")
        val KEEP_LAST = intPreferencesKey("auto_backup_keep_last")
        val WIFI_ONLY = booleanPreferencesKey("auto_backup_wifi_only")
        val CHARGING = booleanPreferencesKey("auto_backup_only_charging")
        val ENABLED_SINCE = longPreferencesKey("auto_backup_enabled_since")
    }
}
