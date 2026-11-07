package com.eggbusinessmanager.app.feature.auth.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.auth.biometric.BiometricCoordinator
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.usecase.auth.GetActiveLockoutUseCase
import com.eggbusinessmanager.domain.usecase.auth.SignInUseCase
import com.eggbusinessmanager.domain.usecase.auth.SignOutUseCase
import com.eggbusinessmanager.domain.usecase.auth.UnlockWithPasswordUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Backs both the sign-in screen (identifier + password) and the lock overlay (password only).
 * Failures show one generic message (FR-005); lockouts show a countdown that resumes after a
 * restart (US1-6). Biometric unlock is offered when enabled (FR-008).
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val signIn: SignInUseCase,
    private val unlockWithPassword: UnlockWithPasswordUseCase,
    private val signOut: SignOutUseCase,
    private val activeLockout: GetActiveLockoutUseCase,
    private val biometric: BiometricCoordinator,
) : ViewModel() {

    data class UiState(
        val identifier: String = "",
        val password: String = "",
        val feedback: FormFeedback = FormFeedback.None,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val biometricEnabled: StateFlow<Boolean> = biometric.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)
    val biometricInvalidated: StateFlow<Boolean> =
        biometric.invalidatedNotice.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    init {
        viewModelScope.launch {
            activeLockout()?.let { until -> _state.update { it.copy(feedback = FormFeedback(lockedUntil = until)) } }
        }
    }

    fun onIdentifier(value: String) = _state.update {
        it.copy(identifier = value, feedback = it.feedback.without(Field.IDENTIFIER).copy(message = null))
    }

    fun onPassword(value: String) = _state.update {
        it.copy(password = value, feedback = it.feedback.without(Field.PASSWORD).copy(message = null))
    }

    /** Sign-in screen: identifier + password. */
    fun signIn() = submit { signIn(_state.value.identifier, _state.value.password) }

    /** Lock overlay: password only. */
    fun unlock() = submit { unlockWithPassword(_state.value.password) }

    fun onLockoutFinished() = _state.update { it.copy(feedback = it.feedback.copy(lockedUntil = null)) }

    fun signOutInstead() {
        _state.value = UiState()
        signOut()
    }

    /** The cipher to show in the biometric prompt, or null (with a message) when biometrics cannot be used. */
    suspend fun biometricCipher(): Cipher? = when (val prep = biometric.prepareUnlock()) {
        is BiometricCoordinator.Preparation.Ready -> prep.cipher
        BiometricCoordinator.Preparation.Invalidated, BiometricCoordinator.Preparation.NotEnabled -> null
    }

    fun onBiometricResult(authenticatedCipher: Cipher?) {
        if (authenticatedCipher == null) return // cancelled; the password remains available
        viewModelScope.launch {
            if (biometric.completeUnlock(authenticatedCipher) is DomainResult.Err) {
                showMessage(UiText.Resource(R.string.biometric_failed))
            } else {
                _state.value = UiState()
            }
        }
    }

    fun dismissBiometricNotice() {
        viewModelScope.launch { biometric.dismissInvalidatedNotice() }
    }

    private fun showMessage(message: UiText) = _state.update { it.copy(feedback = FormFeedback(message = message)) }

    private fun submit(action: suspend () -> DomainResult<Unit>) {
        if (_state.value.busy || _state.value.feedback.lockedUntil != null) return
        _state.update { it.copy(busy = true, feedback = FormFeedback.None) }
        viewModelScope.launch {
            when (val result = action()) {
                // Clear everything typed: the session switches the app to the main screens.
                is DomainResult.Ok -> _state.value = UiState()
                is DomainResult.Err -> _state.update {
                    it.copy(busy = false, password = "", feedback = FormFeedback.from(result.error))
                }
            }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
