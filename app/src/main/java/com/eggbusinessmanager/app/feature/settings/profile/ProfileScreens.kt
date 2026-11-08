package com.eggbusinessmanager.app.feature.settings.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.DropdownField
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PasswordField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.app.ui.text.ReferenceLabels
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.settings.GetOwnerProfileFormUseCase
import com.eggbusinessmanager.domain.settings.GetShopProfileFormUseCase
import com.eggbusinessmanager.domain.settings.OwnerProfileForm
import com.eggbusinessmanager.domain.settings.ShopProfileForm
import com.eggbusinessmanager.domain.settings.UpdateOwnerProfileUseCase
import com.eggbusinessmanager.domain.settings.UpdateShopProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared state of the two profile forms. */
data class ProfileUiState<F>(
    val form: F,
    val feedback: FormFeedback = FormFeedback.None,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class ShopProfileViewModel @Inject constructor(
    load: GetShopProfileFormUseCase,
    private val save: UpdateShopProfileUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState(ShopProfileForm()))
    val state: StateFlow<ProfileUiState<ShopProfileForm>> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(form = load(), loading = false) } }
    }

    private fun edit(field: Field, transform: ShopProfileForm.() -> ShopProfileForm) =
        _state.update { it.copy(form = it.form.transform(), feedback = it.feedback.without(field), saved = false) }

    fun onShopName(v: String) = edit(Field.SHOP_NAME) { copy(shopName = v) }
    fun onAddress(v: String) = edit(Field.ADDRESS) { copy(address = v) }
    fun onCity(v: String) = edit(Field.CITY) { copy(city = v) }
    fun onState(v: IndianState) = edit(Field.STATE) { copy(state = v) }
    fun onPinCode(v: String) = edit(Field.PIN_CODE) { copy(pinCode = v.filter(Char::isDigit)) }

    fun submit() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = save(_state.value.form)
            _state.update {
                when (result) {
                    is DomainResult.Ok -> it.copy(busy = false, saved = true, feedback = FormFeedback.None)
                    is DomainResult.Err -> it.copy(busy = false, feedback = FormFeedback.from(result.error))
                }
            }
        }
    }
}

@HiltViewModel
class OwnerProfileViewModel @Inject constructor(
    load: GetOwnerProfileFormUseCase,
    private val save: UpdateOwnerProfileUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState(OwnerProfileForm()))
    val state: StateFlow<ProfileUiState<OwnerProfileForm>> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(form = load(), loading = false) } }
    }

    private fun edit(field: Field, transform: OwnerProfileForm.() -> OwnerProfileForm) =
        _state.update { it.copy(form = it.form.transform(), feedback = it.feedback.without(field).copy(message = null), saved = false) }

    fun onOwnerName(v: String) = edit(Field.OWNER_NAME) { copy(ownerName = v) }
    fun onMobile(v: String) = edit(Field.MOBILE) { copy(mobile = v) }
    fun onEmail(v: String) = edit(Field.EMAIL) { copy(email = v) }
    fun onPassword(v: String) = edit(Field.CURRENT_PASSWORD) { copy(currentPassword = v) }

    fun submit() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = save(_state.value.form)
            _state.update {
                when (result) {
                    // The password is never kept after use.
                    is DomainResult.Ok -> it.copy(
                        busy = false, saved = true, feedback = FormFeedback.None, form = it.form.copy(currentPassword = ""),
                    )
                    is DomainResult.Err -> it.copy(busy = false, feedback = FormFeedback.from(result.error))
                }
            }
        }
    }
}

@Composable
fun ShopProfileScreen(onBack: () -> Unit, viewModel: ShopProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val feedback = state.feedback
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    FormScreen(title = stringResource(R.string.more_shop_profile), onBack = onBack) {
        FormTextField(
            form.shopName, viewModel::onShopName, stringResource(R.string.label_shop_name), error = feedback.error(Field.SHOP_NAME),
        )
        FormTextField(
            form.address, viewModel::onAddress, stringResource(R.string.field_optional, stringResource(R.string.label_address)),
            error = feedback.error(Field.ADDRESS), singleLine = false,
        )
        FormTextField(form.city, viewModel::onCity, stringResource(R.string.label_city), error = feedback.error(Field.CITY))
        DropdownField(
            label = stringResource(R.string.label_state),
            options = IndianState.entries,
            selected = form.state,
            optionLabel = { stringResource(ReferenceLabels.state(it)) },
            onSelect = viewModel::onState,
            error = feedback.error(Field.STATE),
        )
        FormTextField(
            form.pinCode, viewModel::onPinCode, stringResource(R.string.label_pin_code),
            error = feedback.error(Field.PIN_CODE), keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
        )
        feedback.message?.let { ErrorBanner(it.asString()) }
        PrimaryButton(stringResource(R.string.action_save), onClick = viewModel::submit, busy = state.busy, enabled = !state.loading)
    }
}

@Composable
fun OwnerProfileScreen(onBack: () -> Unit, viewModel: OwnerProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val feedback = state.feedback
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    FormScreen(title = stringResource(R.string.more_owner_profile), onBack = onBack) {
        FormTextField(
            form.ownerName, viewModel::onOwnerName, stringResource(R.string.label_owner_name), error = feedback.error(Field.OWNER_NAME),
        )
        FormTextField(
            form.mobile, viewModel::onMobile, stringResource(R.string.label_mobile),
            error = feedback.error(Field.MOBILE), keyboardType = KeyboardType.Phone,
        )
        FormTextField(
            form.email, viewModel::onEmail, stringResource(R.string.field_optional, stringResource(R.string.label_email)),
            error = feedback.error(Field.EMAIL), keyboardType = KeyboardType.Email,
        )
        Text(stringResource(R.string.owner_password_note), style = MaterialTheme.typography.bodySmall)
        PasswordField(
            form.currentPassword, viewModel::onPassword, stringResource(R.string.owner_current_password),
            error = feedback.error(Field.CURRENT_PASSWORD), imeAction = ImeAction.Done,
        )
        feedback.message?.let { ErrorBanner(it.asString()) }
        PrimaryButton(stringResource(R.string.action_save), onClick = viewModel::submit, busy = state.busy, enabled = !state.loading)
    }
}
