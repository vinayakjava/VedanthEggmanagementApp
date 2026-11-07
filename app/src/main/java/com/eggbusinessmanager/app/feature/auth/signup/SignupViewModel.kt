package com.eggbusinessmanager.app.feature.auth.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.ui.error.ErrorMessages
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.model.account.SignupForm
import com.eggbusinessmanager.domain.usecase.auth.SignUpUseCase
import com.eggbusinessmanager.domain.validation.SignupValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One ViewModel shared by the three signup screens (contracts/navigation.md). Each step is validated
 * before moving on, and the account is created only at the last step, atomically. Passwords and the
 * PIN are kept in memory only, never in saved state.
 */
@HiltViewModel
class SignupViewModel @Inject constructor(
    private val signUp: SignUpUseCase,
) : ViewModel() {

    data class UiState(
        val form: SignupForm = SignupForm(),
        val feedback: FormFeedback = FormFeedback.None,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun edit(field: Field, transform: SignupForm.() -> SignupForm) = _state.update {
        it.copy(form = it.form.transform(), feedback = it.feedback.without(field).copy(message = null))
    }

    fun onOwnerName(value: String) = edit(Field.OWNER_NAME) { copy(ownerName = value) }
    fun onMobile(value: String) = edit(Field.MOBILE) { copy(mobile = value.filter { it.isDigit() || it == ' ' }.take(MOBILE_INPUT_MAX)) }
    fun onEmail(value: String) = edit(Field.EMAIL) { copy(email = value.trimStart()) }
    fun onPassword(value: String) = edit(Field.PASSWORD) { copy(password = value) }
    fun onConfirmPassword(value: String) = edit(Field.CONFIRM_PASSWORD) { copy(confirmPassword = value) }
    fun onShopName(value: String) = edit(Field.SHOP_NAME) { copy(shopName = value) }
    fun onAddress(value: String) = edit(Field.ADDRESS) { copy(address = value) }
    fun onCity(value: String) = edit(Field.CITY) { copy(city = value) }
    fun onState(value: IndianState) = edit(Field.STATE) { copy(state = value) }
    fun onPinCode(value: String) = edit(Field.PIN_CODE) { copy(pinCode = value.filter(Char::isDigit).take(PIN_CODE_LENGTH)) }
    fun onRecoveryPin(value: String) = edit(Field.RECOVERY_PIN) { copy(recoveryPin = value.filter(Char::isDigit).take(PIN_LENGTH)) }
    fun onConfirmRecoveryPin(value: String) =
        edit(Field.CONFIRM_RECOVERY_PIN) { copy(confirmRecoveryPin = value.filter(Char::isDigit).take(PIN_LENGTH)) }

    fun onQuestion(index: Int, question: SecurityQuestion) = edit(Field.SECURITY_QUESTIONS) {
        copy(securityAnswers = securityAnswers.replace(index) { it.copy(question = question) })
    }

    fun onAnswer(index: Int, answer: String) = edit(Field.securityAnswer(index)) {
        copy(securityAnswers = securityAnswers.replace(index) { it.copy(answer = answer) })
    }

    /** Validates step 1; true when the owner may continue. */
    fun completeAccountStep(): Boolean = check(SignupValidator.accountStep(_state.value.form))

    /** Validates step 2; true when the owner may continue. */
    fun completeShopStep(): Boolean = check(SignupValidator.shopStep(_state.value.form))

    /** Validates step 3 and creates the account. Success switches the app to the main screens. */
    fun submit() {
        if (_state.value.busy || !check(SignupValidator.securityStep(_state.value.form))) return
        _state.update { it.copy(busy = true, feedback = FormFeedback.None) }
        viewModelScope.launch {
            val result = signUp(_state.value.form)
            _state.update {
                it.copy(busy = false, feedback = (result as? DomainResult.Err)?.let { err -> FormFeedback.from(err.error) } ?: it.feedback)
            }
        }
    }

    private fun check(errors: ValidationErrors): Boolean {
        _state.update { it.copy(feedback = FormFeedback(fieldErrors = ErrorMessages.forFields(errors.toMap()))) }
        return errors.isEmpty
    }

    private inline fun List<SecurityAnswerInput>.replace(index: Int, transform: (SecurityAnswerInput) -> SecurityAnswerInput) =
        mapIndexed { i, item -> if (i == index) transform(item) else item }

    private companion object {
        const val PIN_LENGTH = 6
        const val PIN_CODE_LENGTH = 6
        const val MOBILE_INPUT_MAX = 14
    }
}
