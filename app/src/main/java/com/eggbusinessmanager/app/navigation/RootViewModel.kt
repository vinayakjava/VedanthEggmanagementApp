package com.eggbusinessmanager.app.navigation

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
) : ViewModel() {

    sealed interface RootState {
        data object Loading : RootState

        /** Signed out: [hasAccount] picks sign-in, otherwise welcome/signup (US1-1, US1-9). */
        data class Auth(val hasAccount: Boolean) : RootState

        data class Main(val locked: Boolean, val reviewRecovery: Boolean) : RootState
    }

    val state: StateFlow<RootState> = combine(observeSession(), observeAccountExists()) { session, hasAccount ->
        when (session) {
            SessionState.SignedOut -> RootState.Auth(hasAccount)
            SessionState.Locked -> RootState.Main(locked = true, reviewRecovery = false)
            is SessionState.Unlocked -> RootState.Main(locked = false, reviewRecovery = session.reviewRecovery)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    fun onUserActivity() = recordActivity()

    fun onRecoveryReviewHandled() = acknowledgeRecoveryReview()
}
