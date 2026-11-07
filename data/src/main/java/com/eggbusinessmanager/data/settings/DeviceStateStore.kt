package com.eggbusinessmanager.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-only state kept in DataStore under `noBackupFilesDir` (data-model.md § Device-only state).
 * Never backed up and never restored: it describes this installation, not the business.
 */
@Singleton
class DeviceStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    data class DeviceState(
        val installationId: String?,
        val firstLaunchAt: Instant?,
        val lastStartupAt: Instant?,
    )

    val state: Flow<DeviceState> = dataStore.data.map { prefs ->
        DeviceState(
            installationId = prefs[INSTALLATION_ID],
            firstLaunchAt = prefs[FIRST_LAUNCH_AT]?.let(Instant::ofEpochMilli),
            lastStartupAt = prefs[LAST_STARTUP_AT]?.let(Instant::ofEpochMilli),
        )
    }

    /** Records a successful startup; creates the installation id on the very first launch. */
    suspend fun recordStartup(now: Instant, newInstallationId: () -> String) {
        dataStore.edit { prefs ->
            if (prefs[INSTALLATION_ID] == null) {
                prefs[INSTALLATION_ID] = newInstallationId()
                prefs[FIRST_LAUNCH_AT] = now.toEpochMilli()
            }
            prefs[LAST_STARTUP_AT] = now.toEpochMilli()
        }
    }

    private companion object {
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val FIRST_LAUNCH_AT = longPreferencesKey("first_launch_at")
        val LAST_STARTUP_AT = longPreferencesKey("last_startup_at")
    }
}
