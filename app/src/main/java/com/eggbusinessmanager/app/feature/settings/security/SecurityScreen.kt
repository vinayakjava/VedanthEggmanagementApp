package com.eggbusinessmanager.app.feature.settings.security

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.domain.repository.SettingsRepository
import com.eggbusinessmanager.domain.settings.SetAutoLockMinutesUseCase
import com.eggbusinessmanager.app.ui.components.DropdownField
import com.eggbusinessmanager.app.feature.auth.biometric.BiometricCoordinator
import com.eggbusinessmanager.app.feature.auth.biometric.rememberBiometricPromptLauncher
import com.eggbusinessmanager.app.feature.settings.SettingsRow
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.domain.usecase.auth.LockAppUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val biometric: BiometricCoordinator,
    private val lockApp: LockAppUseCase,
    settings: SettingsRepository,
    private val setAutoLock: SetAutoLockMinutesUseCase,
) : ViewModel() {
    /** FR-007: lock after this many minutes in the background or idle. */
    val autoLockMinutes: StateFlow<Int> = settings.observeAutoLockMinutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), SettingsRepository.DEFAULT_AUTO_LOCK_MINUTES)

    fun onAutoLock(minutes: Int) {
        viewModelScope.launch { setAutoLock(minutes) }
    }

    val biometricEnabled: StateFlow<Boolean> = biometric.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    /** Enrolment needs a fresh auth-bound key; the prompt then authenticates its cipher. */
    fun enrollmentCipher(): Cipher = biometric.prepareEnrollment()

    fun onEnrollmentResult(authenticatedCipher: Cipher?) {
        if (authenticatedCipher == null) return
        viewModelScope.launch { biometric.completeEnrollment(authenticatedCipher) }
    }

    fun disableBiometric() {
        viewModelScope.launch { biometric.disable() }
    }

    fun showMessage(text: UiText?) {
        _message.value = text
    }

    fun lockNow() = lockApp()

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** Security settings (US11, FR-008/009/013). */
@Composable
fun SecurityScreen(
    onChangePassword: () -> Unit,
    onChangePin: () -> Unit,
    onChangeQuestions: () -> Unit,
    onBack: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val enabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val launcher = rememberBiometricPromptLauncher()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.biometric_prompt_enable_title)
    val subtitle = stringResource(R.string.biometric_prompt_subtitle)
    val negative = stringResource(R.string.action_cancel)

    val toggleBiometric: () -> Unit = {
        when {
            enabled -> viewModel.disableBiometric()
            !launcher.isAvailable() -> viewModel.showMessage(UiText.Resource(R.string.biometric_unavailable))
            else -> scope.launch {
                viewModel.showMessage(null)
                val result = launcher.authenticate(viewModel.enrollmentCipher(), title, subtitle, negative)
                viewModel.onEnrollmentResult(result)
            }
        }
    }

    FormScreen(title = stringResource(R.string.security_title), onBack = onBack) {
        SettingsRow(R.drawable.ic_key, stringResource(R.string.security_change_password), null, onChangePassword)
        SettingsRow(R.drawable.ic_pin, stringResource(R.string.security_change_pin), null, onChangePin)
        SettingsRow(R.drawable.ic_help, stringResource(R.string.security_change_questions), null, onChangeQuestions)
        SettingsRow(
            R.drawable.ic_fingerprint,
            stringResource(R.string.security_biometric),
            stringResource(R.string.security_biometric_summary),
            onClick = toggleBiometric,
            trailing = { Switch(checked = enabled, onCheckedChange = { toggleBiometric() }) },
        )
        message?.let { ErrorBanner(it.asString(), onDismiss = { viewModel.showMessage(null) }) }
        val autoLock by viewModel.autoLockMinutes.collectAsStateWithLifecycle()
        DropdownField(
            label = stringResource(R.string.security_auto_lock),
            options = SettingsRepository.AUTO_LOCK_CHOICES,
            selected = autoLock,
            optionLabel = { stringResource(R.string.security_auto_lock_option, it) },
            onSelect = viewModel::onAutoLock,
        )
        SettingsRow(R.drawable.ic_lock, stringResource(R.string.security_lock_now), null, viewModel::lockNow)
    }
}
