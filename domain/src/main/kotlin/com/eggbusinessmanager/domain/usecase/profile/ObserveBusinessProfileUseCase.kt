package com.eggbusinessmanager.domain.usecase.profile

import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.repository.BusinessProfileRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveBusinessProfileUseCase @Inject constructor(private val profiles: BusinessProfileRepository) {
    operator fun invoke(): Flow<BusinessProfile?> = profiles.observe()
}
