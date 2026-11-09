package com.eggbusinessmanager.app.navigation

import kotlinx.coroutines.launch
import com.eggbusinessmanager.domain.license.LicenseStatus
import com.eggbusinessmanager.app.feature.license.LicenseGate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.domain.usecase.auth.AcknowledgeRecoveryReviewUseCase
import com.eggbusinessmanager.domain.usecase.auth.ObserveAccountExistsUseCase
import com.eggbusinessmanager.domain.usecase.auth.ObserveSessionUseCase
import com.eggbusinessmanager.domain.usecase.auth.RecordActivityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Decides which graph is shown: auth (welcome or sign-in) or the main screens, with the lock overlay. */
@HiltViewModel
class RootViewModel @Inject constructor(
    observeSession: ObserveSessionUseCase,
    observeAccountExists: ObserveAccountExistsUseCase,
    private val recordActivity: RecordActivityUseCase,
    private val acknowledgeRecoveryReview: AcknowledgeRecoveryReviewUseCase,
    licenseGate: LicenseGate,
) : ViewModel() {

    sealed interface RootState {
        data object Loading : RootState

        /** This phone has no valid lifetime license yet: the Activation screen comes first (data untouched). */
        data object Activation : RootState

        /** Signed out: [hasAccount] picks sign-in, otherwise welcome/signup (US1-1, US1-9). */
        data class Auth(val hasAccount: Boolean) : RootState

        data class Main(val locked: Boolean, val reviewRecovery: Boolean) : RootState
    }

    val state: StateFlow<RootState> =
        combine(observeSession(), observeAccountExists(), licenseGate.status) { session, hasAccount, license ->
        when {
            license == null -> RootState.Loading
            license is LicenseStatus.NotActivated -> RootState.Activation
            else -> stateFor(session, hasAccount)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    init {
        viewModelScope.launch { licenseGate.refresh() }
    }

    private fun stateFor(session: SessionState, hasAccount: Boolean): RootState =
        when (session) {
            SessionState.SignedOut -> RootState.Auth(hasAccount)
            SessionState.Locked -> RootState.Main(locked = true, reviewRecovery = false)
            is SessionState.Unlocked -> RootState.Main(locked = false, reviewRecovery = session.reviewRecovery)
        }

    fun onUserActivity() = recordActivity()

    fun onRecoveryReviewHandled() = acknowledgeRecoveryReview()
}
