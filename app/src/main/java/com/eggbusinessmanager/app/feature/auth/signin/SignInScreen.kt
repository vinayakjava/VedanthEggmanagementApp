package com.eggbusinessmanager.app.feature.auth.signin

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.auth.biometric.BiometricPromptLauncher
import com.eggbusinessmanager.app.feature.auth.biometric.rememberBiometricPromptLauncher
import com.eggbusinessmanager.app.ui.components.BrandMark
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.LockoutCountdown
import com.eggbusinessmanager.app.ui.components.PasswordField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.components.SecondaryTextButton
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.domain.error.Field
import kotlinx.coroutines.launch

/** Sign in with mobile number or email + password, or biometrics when enabled (US1-4..7). */
@Composable
fun SignInScreen(viewModel: SignInViewModel, onForgotPassword: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locked = state.feedback.lockedUntil != null
    val biometric = rememberBiometricUnlock(viewModel, R.string.biometric_prompt_title)

    FormScreen(title = stringResource(R.string.signin_title), onBack = null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(size = 96.dp)
            Text(stringResource(R.string.signin_welcome_back), style = MaterialTheme.typography.headlineSmall)
        }
        BiometricInvalidatedNotice(viewModel)
        FormTextField(
            state.identifier, viewModel::onIdentifier, stringResource(R.string.label_identifier),
            error = state.feedback.error(Field.IDENTIFIER), keyboardType = KeyboardType.Email, enabled = !locked,
        )
        PasswordField(
            state.password, viewModel::onPassword, stringResource(R.string.label_password),
            error = state.feedback.error(Field.PASSWORD), imeAction = ImeAction.Done, onImeAction = viewModel::signIn,
        )
        SubmitArea(state.feedback, state.busy, stringResource(R.string.signin_action), viewModel::signIn, viewModel::onLockoutFinished)
        biometric?.let { BiometricButton(onClick = it) }
        SecondaryTextButton(
            stringResource(R.string.signin_forgot),
            onClick = onForgotPassword,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** Countdown or message, then the primary button (disabled while locked). */
@Composable
internal fun SubmitArea(
    feedback: FormFeedback,
    busy: Boolean,
    actionLabel: String,
    onSubmit: () -> Unit,
    onLockoutFinished: () -> Unit,
) {
    feedback.lockedUntil?.let { LockoutCountdown(until = it, onFinished = onLockoutFinished) }
    feedback.message?.let { ErrorBanner(it.asString()) }
    PrimaryButton(actionLabel, onClick = onSubmit, busy = busy, enabled = feedback.lockedUntil == null)
}

@Composable
internal fun BiometricButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(painterResource(R.drawable.ic_fingerprint), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.signin_biometric), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun BiometricInvalidatedNotice(viewModel: SignInViewModel) {
    val invalidated by viewModel.biometricInvalidated.collectAsStateWithLifecycle()
    if (invalidated) {
        ErrorBanner(stringResource(R.string.biometric_invalidated), onDismiss = viewModel::dismissBiometricNotice)
    }
}

/**
 * When biometric unlock is enabled and available, prompts once automatically and returns an action
 * that prompts again; otherwise null (only the password is offered).
 */
@Composable
internal fun rememberBiometricUnlock(viewModel: SignInViewModel, @StringRes titleRes: Int): (() -> Unit)? {
    val enabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val launcher = rememberBiometricPromptLauncher()
    if (!enabled || !launcher.isAvailable()) return null

    val scope = rememberCoroutineScope()
    val texts = PromptTexts(
        title = stringResource(titleRes),
        subtitle = stringResource(R.string.biometric_prompt_subtitle),
        negative = stringResource(R.string.biometric_use_password),
    )
    val prompt: () -> Unit = { scope.launch { runPrompt(viewModel, launcher, texts) } }

    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoPrompted) {
            autoPrompted = true
            runPrompt(viewModel, launcher, texts)
        }
    }
    return prompt
}

private data class PromptTexts(val title: String, val subtitle: String, val negative: String)

private suspend fun runPrompt(viewModel: SignInViewModel, launcher: BiometricPromptLauncher, texts: PromptTexts) {
    val cipher = viewModel.biometricCipher() ?: return
    viewModel.onBiometricResult(launcher.authenticate(cipher, texts.title, texts.subtitle, texts.negative))
}
