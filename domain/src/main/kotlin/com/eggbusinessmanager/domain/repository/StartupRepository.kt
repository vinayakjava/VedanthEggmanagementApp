package com.eggbusinessmanager.domain.repository

import com.eggbusinessmanager.domain.model.StartupState

/** Prepares encrypted local storage (key, downgrade guard, migrations) before data access. */
interface StartupRepository {
    suspend fun prepare(): StartupState
}
