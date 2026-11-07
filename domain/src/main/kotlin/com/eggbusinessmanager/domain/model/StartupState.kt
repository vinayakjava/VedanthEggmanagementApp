package com.eggbusinessmanager.domain.model

/** Outcome of preparing local storage before any screen can use data. */
sealed interface StartupState {
    data object Ready : StartupState

    /** The database was written by a newer app version; the app must be updated (downgrade guard). */
    data class UpdateRequired(val storedVersion: Int, val supportedVersion: Int) : StartupState

    /** Local data could not be opened. Nothing has been deleted. */
    data class Failed(val reason: Reason) : StartupState {
        enum class Reason { KEY_UNAVAILABLE, LOW_STORAGE, UNKNOWN }
    }
}
