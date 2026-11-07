package com.eggbusinessmanager.app.feature.auth.signup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.auth.components.SecurityQuestionsEditor
import com.eggbusinessmanager.app.ui.components.DropdownField
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PasswordField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.text.ReferenceLabels
import com.eggbusinessmanager.app.ui.theme.EggBusinessTheme
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.model.account.IndianState

private const val STEPS = 3

@Composable
private fun StepHeader(step: Int, heading: String, body: String) {
    Text(
        stringResource(R.string.signup_step, step, STEPS),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    LinearProgressIndicator(progress = { step / STEPS.toFloat() }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    Text(heading, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Step 1: owner, mobile, optional email, password (FR-002). */
@Composable
fun SignupAccountScreen(viewModel: SignupViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val feedback = state.feedback
    val next = { if (viewModel.completeAccountStep()) onNext() }

    FormScreen(title = stringResource(R.string.signup_title), onBack = onBack) {
        StepHeader(1, stringResource(R.string.signup_account_heading), stringResource(R.string.signup_account_body))
        FormTextField(
            form.ownerName, viewModel::onOwnerName, stringResource(R.string.label_owner_name),
            error = feedback.error(Field.OWNER_NAME),
        )
        FormTextField(
            form.mobile, viewModel::onMobile, stringResource(R.string.label_mobile),
            error = feedback.error(Field.MOBILE), keyboardType = KeyboardType.Phone, prefix = stringResource(R.string.hint_mobile_prefix),
        )
        FormTextField(
            form.email, viewModel::onEmail, stringResource(R.string.field_optional, stringResource(R.string.label_email)),
            error = feedback.error(Field.EMAIL), keyboardType = KeyboardType.Email,
        )
        PasswordField(
            form.password, viewModel::onPassword, stringResource(R.string.label_password),
            error = feedback.error(Field.PASSWORD), supportingText = stringResource(R.string.hint_password_rules),
        )
        PasswordField(
            form.confirmPassword, viewModel::onConfirmPassword, stringResource(R.string.label_confirm_password),
            error = feedback.error(Field.CONFIRM_PASSWORD), imeAction = ImeAction.Done, onImeAction = next,
        )
        PrimaryButton(stringResource(R.string.action_next), onClick = next)
    }
}

/** Step 2: shop details with city, state and PIN code (FR-002). */
@Composable
fun SignupShopScreen(viewModel: SignupViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val feedback = state.feedback
    val next = { if (viewModel.completeShopStep()) onNext() }

    FormScreen(title = stringResource(R.string.signup_title), onBack = onBack) {
        StepHeader(2, stringResource(R.string.signup_shop_heading), stringResource(R.string.signup_shop_body))
        FormTextField(
            form.shopName, viewModel::onShopName, stringResource(R.string.label_shop_name),
            error = feedback.error(Field.SHOP_NAME),
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
            error = feedback.error(Field.PIN_CODE), keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, onImeAction = next,
        )
        PrimaryButton(stringResource(R.string.action_next), onClick = next)
    }
}

/** Step 3: recovery PIN and 3 security questions (FR-012), then create the account. */
@Composable
fun SignupSecurityScreen(viewModel: SignupViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val feedback = state.feedback

    FormScreen(title = stringResource(R.string.signup_title), onBack = onBack.takeUnless { state.busy }) {
        StepHeader(3, stringResource(R.string.signup_security_heading), stringResource(R.string.signup_security_body))
        PasswordField(
            form.recoveryPin, viewModel::onRecoveryPin, stringResource(R.string.label_recovery_pin),
            error = feedback.error(Field.RECOVERY_PIN), supportingText = stringResource(R.string.hint_recovery_pin), numeric = true,
        )
        PasswordField(
            form.confirmRecoveryPin, viewModel::onConfirmRecoveryPin, stringResource(R.string.label_confirm_recovery_pin),
            error = feedback.error(Field.CONFIRM_RECOVERY_PIN), numeric = true,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.signup_questions_heading), style = MaterialTheme.typography.titleMedium)
        SecurityQuestionsEditor(form.securityAnswers, feedback, viewModel::onQuestion, viewModel::onAnswer)
        feedback.message?.let { ErrorBanner(it.asString()) }
        if (state.busy) {
            Text(stringResource(R.string.secure_processing), style = MaterialTheme.typography.bodyMedium)
        }
        PrimaryButton(stringResource(R.string.signup_create), onClick = viewModel::submit, busy = state.busy)
    }
}

@Preview(showBackground = true)
@Composable
private fun StepHeaderPreview() {
    EggBusinessTheme {
        androidx.compose.foundation.layout.Column {
            StepHeader(2, "Shop details", "These details appear on statements and receipts.")
        }
    }
}
