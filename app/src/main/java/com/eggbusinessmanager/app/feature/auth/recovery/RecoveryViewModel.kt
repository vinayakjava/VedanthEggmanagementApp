package com.eggbusinessmanager.app.feature.auth.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.usecase.auth.GetActiveLockoutUseCase
import com.eggbusinessmanager.domain.usecase.auth.GetSecurityQuestionsUseCase
import com.eggbusinessmanager.domain.usecase.auth.RecoverWithPinUseCase
import com.eggbusinessmanager.domain.usecase.auth.SetPasswordAfterRestoreUseCase
import com.eggbusinessmanager.domain.usecase.auth.RecoverWithSecurityAnswersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Password recovery (FR-012/013, US11): either the recovery PIN or all 3 security answers, plus the
 * new password, verified and applied in one step. Success signs in and asks the owner to review
 * their recovery options. Each recovery screen gets its own instance.
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val recoverWithPin: RecoverWithPinUseCase,
    private val recoverWithAnswers: RecoverWithSecurityAnswersUseCase,
    private val getQuestions: GetSecurityQuestionsUseCase,
    private val activeLockout: GetActiveLockoutUseCase,
    private val setPasswordAfterRestore: SetPasswordAfterRestoreUseCase,
) : ViewModel() {

    data class UiState(
        val pin: String = "",
        val questions: List<SecurityQuestion> = emptyList(),
        val answers: List<String> = List(SecurityQuestion.REQUIRED_COUNT) { "" },
        val newPassword: String = "",
        val confirmPassword: String = "",
        val feedback: FormFeedback = FormFeedback.None,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            activeLockout()?.let { until -> _state.update { it.copy(feedback = FormFeedback(lockedUntil = until)) } }
        }
    }

    /** Loads the owner's 3 questions for the security-questions screen. */
    fun loadQuestions() {
        if (_state.value.questions.isNotEmpty()) return
        viewModelScope.launch {
            when (val result = getQuestions()) {
                is DomainResult.Ok -> _state.update { it.copy(questions = result.value) }
                is DomainResult.Err -> _state.update { it.copy(feedback = FormFeedback.from(result.error)) }
            }
        }
    }

    private fun edit(field: Field, transform: UiState.() -> UiState) = _state.update {
        it.transform().copy(feedback = it.feedback.without(field).copy(message = null))
    }

    fun onPin(value: String) = edit(Field.RECOVERY_PIN) { copy(pin = value.filter(Char::isDigit).take(PIN_LENGTH)) }
    fun onAnswer(index: Int, value: String) =
        edit(Field.securityAnswer(index)) { copy(answers = answers.mapIndexed { i, a -> if (i == index) value else a }) }
    fun onNewPassword(value: String) = edit(Field.PASSWORD) { copy(newPassword = value) }
    fun onConfirmPassword(value: String) = edit(Field.CONFIRM_PASSWORD) { copy(confirmPassword = value) }
    fun onLockoutFinished() = _state.update { it.copy(feedback = it.feedback.copy(lockedUntil = null)) }

    fun submitPin() = submit { recoverWithPin(it.pin, it.newPassword, it.confirmPassword) }

    fun submitAnswers() = submit { recoverWithAnswers(it.answers, it.newPassword, it.confirmPassword) }

    /** US9-9: right after a restore, no old password, PIN or answers are needed. */
    fun submitAfterRestore() = submit { setPasswordAfterRestore(it.newPassword, it.confirmPassword) }

    private fun submit(action: suspend (UiState) -> DomainResult<Unit>) {
        val current = _state.value
        if (current.busy || current.feedback.lockedUntil != null) return
        _state.update { it.copy(busy = true, feedback = FormFeedback.None) }
        viewModelScope.launch {
            when (val result = action(current)) {
                is DomainResult.Ok -> _state.value = UiState() // the session switches to the main screens
                is DomainResult.Err -> _state.update {
                    // Clear the secret that was checked; keep the new password the owner typed.
                    it.copy(busy = false, pin = "", feedback = FormFeedback.from(result.error))
                }
            }
        }
    }

    private companion object {
        const val PIN_LENGTH = 6
    }
}
