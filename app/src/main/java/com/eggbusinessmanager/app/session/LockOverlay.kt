package com.eggbusinessmanager.app.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.auth.signin.BiometricButton
import com.eggbusinessmanager.app.feature.auth.signin.BiometricInvalidatedNotice
import com.eggbusinessmanager.app.feature.auth.signin.SignInViewModel
import com.eggbusinessmanager.app.feature.auth.signin.SubmitArea
import com.eggbusinessmanager.app.feature.auth.signin.rememberBiometricUnlock
import com.eggbusinessmanager.app.ui.components.PasswordField
import com.eggbusinessmanager.app.ui.components.SecondaryTextButton
import com.eggbusinessmanager.domain.error.Field

/**
 * Drawn above the main screens while the session is Locked (contracts/navigation.md): the screens
 * underneath stay composed, so unsaved form input survives (US1-8). It blocks touches and back
 * navigation, and offers password or biometric unlock.
 */
@Composable
fun LockOverlay(modifier: Modifier = Modifier, viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val biometric = rememberBiometricUnlock(viewModel, R.string.biometric_prompt_title)
    BackHandler(enabled = true) { /* Locked: back must not reveal or leave the hidden screens. */ }

    Surface(
        modifier = modifier.fillMaxSize().semantics { isTraversalGroup = true },
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        painterResource(R.drawable.ic_lock),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(20.dp).size(40.dp),
                    )
                }
                Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.lock_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                BiometricInvalidatedNotice(viewModel)
                PasswordField(
                    state.password, viewModel::onPassword, stringResource(R.string.label_password),
                    error = state.feedback.error(Field.PASSWORD), imeAction = ImeAction.Done, onImeAction = viewModel::unlock,
                )
                SubmitArea(
                    state.feedback, state.busy, stringResource(R.string.lock_unlock),
                    viewModel::unlock, viewModel::onLockoutFinished,
                )
                biometric?.let { BiometricButton(onClick = it) }
                SecondaryTextButton(stringResource(R.string.lock_sign_out), onClick = viewModel::signOutInstead)
            }
        }
    }
}
