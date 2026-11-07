package com.eggbusinessmanager.app.feature.startup

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.EmptyState
import com.eggbusinessmanager.app.ui.error.ErrorMessages
import com.eggbusinessmanager.domain.model.StartupState

/** Shown when local data cannot be opened. It never offers to delete data. */
@Composable
fun StartupProblemScreen(state: StartupState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        when (state) {
            is StartupState.UpdateRequired -> EmptyState(
                title = stringResource(R.string.startup_update_required_title),
                body = stringResource(R.string.startup_update_required_body),
                iconRes = R.drawable.ic_warning,
                modifier = Modifier.safeDrawingPadding(),
            )
            is StartupState.Failed -> EmptyState(
                title = stringResource(R.string.startup_failed_title),
                body = ErrorMessages.forStartupFailure(state.reason).asString(),
                iconRes = R.drawable.ic_warning,
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
                modifier = Modifier.safeDrawingPadding(),
            )
            StartupState.Ready -> Unit
        }
    }
}
