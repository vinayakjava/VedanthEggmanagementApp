package com.eggbusinessmanager.app.feature.settings.security

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.auth.components.SecurityQuestionsEditor
import com.eggbusinessmanager.app.feature.auth.signin.SubmitArea
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.PasswordField
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.usecase.auth.ChangePasswordUseCase
import com.eggbusinessmanager.domain.usecase.auth.ChangeRecoveryPinUseCase
import com.eggbusinessmanager.domain.usecase.auth.UpdateSecurityQuestionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the three "change" screens (FR-009, FR-013). Every change needs the current password, and a
 * wrong one counts toward lockout (US11-2). Each screen gets its own instance.
 */
@HiltViewModel
class ChangeCredentialsViewModel @Inject constructor(
    private val changePassword: ChangePasswordUseCase,
    private val changePin: ChangeRecoveryPinUseCase,
    private val updateQuestions: UpdateSecurityQuestionsUseCase,
) : ViewModel() {

    data class UiState(
        val currentPassword: String = "",
        val newPassword: String = "",
        val confirmPassword: String = "",
        val pin: String = "",
        val confirmPin: String = "",
        val answers: List<SecurityAnswerInput> = List(SecurityQuestion.REQUIRED_COUNT) { SecurityAnswerInput(null, "") },
        val feedback: FormFeedback = FormFeedback.None,
        val busy: Boolean = false,
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun edit(field: Field, transform: UiState.() -> UiState) = _state.update {
        it.transform().copy(feedback = it.feedback.without(field).copy(message = null))
    }

    fun onCurrentPassword(value: String) = edit(Field.CURRENT_PASSWORD) { copy(currentPassword = value) }
    fun onNewPassword(value: String) = edit(Field.PASSWORD) { copy(newPassword = value) }
    fun onConfirmPassword(value: String) = edit(Field.CONFIRM_PASSWORD) { copy(confirmPassword = value) }
    fun onPin(value: String) = edit(Field.RECOVERY_PIN) { copy(pin = value.filter(Char::isDigit).take(PIN_LENGTH)) }
    fun onConfirmPin(value: String) = edit(Field.CONFIRM_RECOVERY_PIN) { copy(confirmPin = value.filter(Char::isDigit).take(PIN_LENGTH)) }
    fun onQuestion(index: Int, question: SecurityQuestion) =
        edit(Field.SECURITY_QUESTIONS) { copy(answers = answers.mapIndexed { i, a -> if (i == index) a.copy(question = question) else a }) }
    fun onAnswer(index: Int, value: String) =
        edit(Field.securityAnswer(index)) { copy(answers = answers.mapIndexed { i, a -> if (i == index) a.copy(answer = value) else a }) }
    fun onLockoutFinished() = _state.update { it.copy(feedback = it.feedback.copy(lockedUntil = null)) }

    fun savePassword() = submit { changePassword(it.currentPassword, it.newPassword, it.confirmPassword) }
    fun savePin() = submit { changePin(it.currentPassword, it.pin, it.confirmPin) }
    fun saveQuestions() = submit { updateQuestions(it.currentPassword, it.answers) }

    private fun submit(action: suspend (UiState) -> DomainResult<Unit>) {
        val current = _state.value
        if (current.busy || current.feedback.lockedUntil != null) return
        _state.update { it.copy(busy = true, feedback = FormFeedback.None) }
        viewModelScope.launch {
            when (val result = action(current)) {
                is DomainResult.Ok -> _state.update { UiState(saved = true) }
                is DomainResult.Err -> _state.update {
                    it.copy(busy = false, currentPassword = "", feedback = FormFeedback.from(result.error))
                }
            }
        }
    }

    private companion object {
        const val PIN_LENGTH = 6
    }
}

@Composable
private fun CurrentPasswordField(state: ChangeCredentialsViewModel.UiState, viewModel: ChangeCredentialsViewModel) {
    Text(stringResource(R.string.security_confirm_with_password), style = MaterialTheme.typography.bodyMedium)
    PasswordField(
        state.currentPassword, viewModel::onCurrentPassword, stringResource(R.string.label_current_password),
        error = state.feedback.error(Field.CURRENT_PASSWORD),
    )
}

@Composable
private fun CloseWhenSaved(saved: Boolean, onDone: () -> Unit) {
    LaunchedEffect(saved) { if (saved) onDone() }
}

@Composable
fun ChangePasswordScreen(onDone: () -> Unit, viewModel: ChangeCredentialsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloseWhenSaved(state.saved, onDone)
    FormScreen(title = stringResource(R.string.security_change_password), onBack = onDone) {
        CurrentPasswordField(state, viewModel)
        PasswordField(
            state.newPassword, viewModel::onNewPassword, stringResource(R.string.label_new_password),
            error = state.feedback.error(Field.PASSWORD), supportingText = stringResource(R.string.hint_password_rules),
        )
        PasswordField(
            state.confirmPassword, viewModel::onConfirmPassword, stringResource(R.string.label_confirm_new_password),
            error = state.feedback.error(Field.CONFIRM_PASSWORD), imeAction = ImeAction.Done, onImeAction = viewModel::savePassword,
        )
        SubmitArea(state.feedback, state.busy, stringResource(R.string.action_save), viewModel::savePassword, viewModel::onLockoutFinished)
    }
}

@Composable
fun ChangeRecoveryPinScreen(onDone: () -> Unit, viewModel: ChangeCredentialsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloseWhenSaved(state.saved, onDone)
    FormScreen(title = stringResource(R.string.security_change_pin), onBack = onDone) {
        CurrentPasswordField(state, viewModel)
        PasswordField(
            state.pin, viewModel::onPin, stringResource(R.string.label_recovery_pin),
            error = state.feedback.error(Field.RECOVERY_PIN), supportingText = stringResource(R.string.hint_recovery_pin), numeric = true,
        )
        PasswordField(
            state.confirmPin, viewModel::onConfirmPin, stringResource(R.string.label_confirm_recovery_pin),
            error = state.feedback.error(Field.CONFIRM_RECOVERY_PIN), numeric = true,
        )
        SubmitArea(state.feedback, state.busy, stringResource(R.string.action_save), viewModel::savePin, viewModel::onLockoutFinished)
    }
}

@Composable
fun ChangeSecurityQuestionsScreen(onDone: () -> Unit, viewModel: ChangeCredentialsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloseWhenSaved(state.saved, onDone)
    FormScreen(title = stringResource(R.string.security_change_questions), onBack = onDone) {
        CurrentPasswordField(state, viewModel)
        SecurityQuestionsEditor(state.answers, state.feedback, viewModel::onQuestion, viewModel::onAnswer)
        SubmitArea(state.feedback, state.busy, stringResource(R.string.action_save), viewModel::saveQuestions, viewModel::onLockoutFinished)
    }
}
