package com.eggbusinessmanager.app.feature.auth.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.auth.signin.SubmitArea
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PasswordField
import com.eggbusinessmanager.app.ui.text.ReferenceLabels
import com.eggbusinessmanager.domain.error.Field

/** Choose a recovery method, with the "forgot everything" help from US11-5. */
@Composable
fun ForgotPasswordScreen(onUsePin: () -> Unit, onUseQuestions: () -> Unit, onBack: () -> Unit) {
    FormScreen(title = stringResource(R.string.recovery_title), onBack = onBack) {
        Text(stringResource(R.string.recovery_choose_body), style = MaterialTheme.typography.bodyLarge)
        MethodCard(R.drawable.ic_pin, stringResource(R.string.recovery_with_pin), onUsePin)
        MethodCard(R.drawable.ic_help, stringResource(R.string.recovery_with_questions), onUseQuestions)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recovery_lost_everything_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.recovery_lost_everything_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MethodCard(icon: Int, label: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

@Composable
private fun NewPasswordFields(state: RecoveryViewModel.UiState, viewModel: RecoveryViewModel, onDone: () -> Unit) {
    Text(stringResource(R.string.recovery_new_password_heading), style = MaterialTheme.typography.titleMedium)
    PasswordField(
        state.newPassword, viewModel::onNewPassword, stringResource(R.string.label_new_password),
        error = state.feedback.error(Field.PASSWORD), supportingText = stringResource(R.string.hint_password_rules),
    )
    PasswordField(
        state.confirmPassword, viewModel::onConfirmPassword, stringResource(R.string.label_confirm_new_password),
        error = state.feedback.error(Field.CONFIRM_PASSWORD), imeAction = ImeAction.Done, onImeAction = onDone,
    )
}

/** Reset with the 6-digit recovery PIN. */
@Composable
fun RecoverWithPinScreen(viewModel: RecoveryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FormScreen(title = stringResource(R.string.recovery_pin_title), onBack = onBack) {
        PasswordField(
            state.pin, viewModel::onPin, stringResource(R.string.label_recovery_pin),
            error = state.feedback.error(Field.RECOVERY_PIN), numeric = true,
        )
        NewPasswordFields(state, viewModel, viewModel::submitPin)
        SubmitArea(
            state.feedback, state.busy, stringResource(R.string.recovery_reset_action),
            viewModel::submitPin, viewModel::onLockoutFinished,
        )
    }
}

/** US9-9: a new login password right after restoring a backup (the passphrase proved ownership). */
@Composable
fun SetPasswordAfterRestoreScreen(viewModel: RecoveryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FormScreen(title = stringResource(R.string.restore_set_password), onBack = onBack) {
        Text(stringResource(R.string.restore_set_password_body), style = MaterialTheme.typography.bodyMedium)
        NewPasswordFields(state, viewModel, viewModel::submitAfterRestore)
        SubmitArea(
            state.feedback, state.busy, stringResource(R.string.restore_set_password),
            viewModel::submitAfterRestore, viewModel::onLockoutFinished,
        )
    }
}

/** Reset by answering the owner's 3 security questions. */
@Composable
fun RecoverWithQuestionsScreen(viewModel: RecoveryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadQuestions() }
    FormScreen(title = stringResource(R.string.recovery_questions_title), onBack = onBack) {
        state.questions.forEachIndexed { index, question ->
            Text(stringResource(ReferenceLabels.question(question)), style = MaterialTheme.typography.titleSmall)
            FormTextField(
                state.answers[index], { viewModel.onAnswer(index, it) }, stringResource(R.string.label_security_answer, index + 1),
                error = state.feedback.error(Field.securityAnswer(index)),
            )
        }
        NewPasswordFields(state, viewModel, viewModel::submitAnswers)
        SubmitArea(
            state.feedback, state.busy, stringResource(R.string.recovery_reset_action),
            viewModel::submitAnswers, viewModel::onLockoutFinished,
        )
    }
}

