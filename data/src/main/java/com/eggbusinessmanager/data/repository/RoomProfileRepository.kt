package com.eggbusinessmanager.data.repository

import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.settings.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider

/** Shop and owner profile updates (single-row tables). */
class RoomProfileRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ProfileRepository {

    override suspend fun updateShop(profile: BusinessProfile, at: Instant) = withContext(io) {
        database.get().accountDao().updateProfile(
            shopName = profile.shopName,
            ownerName = profile.ownerName,
            address = profile.address,
            city = profile.city,
            stateCode = profile.state.name,
            pinCode = profile.pinCode,
            at = at.toEpochMilli(),
        )
    }

    override suspend fun updateContact(mobile: String, email: String?, at: Instant) = withContext(io) {
        database.get().accountDao().updateContact(mobile, email, at.toEpochMilli())
    }
}
