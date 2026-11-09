package com.eggbusinessmanager.app.feature.backup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.domain.backup.ConsumeRestoreOutcomeUseCase
import com.eggbusinessmanager.domain.backup.RestoreOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RestoreOutcomeViewModel @Inject constructor(consume: ConsumeRestoreOutcomeUseCase) : ViewModel() {
    private val _outcome = MutableStateFlow<RestoreOutcome?>(null)
    val outcome: StateFlow<RestoreOutcome?> = _outcome.asStateFlow()

    init {
        viewModelScope.launch { _outcome.value = consume() }
    }

    fun dismiss() {
        _outcome.value = null
    }
}

/**
 * After the restart that applied (or rolled back) a restore: says what happened, once. On the sign-in
 * screen, [onSetPassword] offers a new login password, since restoring needed the passphrase (US9-9).
 */
@Composable
fun RestoreOutcomeDialog(onSetPassword: (() -> Unit)? = null, viewModel: RestoreOutcomeViewModel = hiltViewModel()) {
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val restored = outcome == RestoreOutcome.RESTORED
    if (outcome == null) return
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(if (restored) R.string.restore_outcome_ok_title else R.string.restore_outcome_fail_title)) },
        text = { Text(stringResource(if (restored) R.string.restore_outcome_ok_body else R.string.restore_outcome_fail_body)) },
        confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = if (restored && onSetPassword != null) {
            {
                TextButton(onClick = {
                    viewModel.dismiss()
                    onSetPassword()
                }) { Text(stringResource(R.string.restore_set_password)) }
            }
        } else {
            null
        },
    )
}
