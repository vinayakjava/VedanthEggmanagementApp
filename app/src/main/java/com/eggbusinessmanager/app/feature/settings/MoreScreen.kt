package com.eggbusinessmanager.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.text.ReferenceLabels
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.usecase.auth.SignOutUseCase
import com.eggbusinessmanager.domain.usecase.profile.ObserveBusinessProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(
    observeProfile: ObserveBusinessProfileUseCase,
    private val signOut: SignOutUseCase,
) : ViewModel() {
    val profile: StateFlow<BusinessProfile?> =
        observeProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    fun signOut() = signOut.invoke()

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** The "More" tab: shop header, security settings and sign out (FR-011). Other settings arrive with US12. */
@Composable
fun MoreScreen(
    onSecurity: () -> Unit,
    onUnits: () -> Unit,
    onVarieties: () -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            profile?.let { ShopHeader(it) }
            SettingsRow(
                R.drawable.ic_shield, stringResource(R.string.more_security), stringResource(R.string.more_security_summary),
                onClick = onSecurity,
            )
            SettingsRow(
                R.drawable.ic_sort, stringResource(R.string.more_units), stringResource(R.string.more_units_summary),
                onClick = onUnits,
            )
            SettingsRow(
                R.drawable.ic_nav_dashboard, stringResource(R.string.more_varieties), stringResource(R.string.more_varieties_summary),
                onClick = onVarieties,
            )
            SettingsRow(
                R.drawable.ic_logout, stringResource(R.string.more_sign_out), stringResource(R.string.more_sign_out_summary),
                onClick = { confirmSignOut = true },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.more_sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.more_sign_out_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.more_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ShopHeader(profile: BusinessProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.shopName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(profile.ownerName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            val place = listOfNotNull(profile.address, profile.city, stringResource(ReferenceLabels.state(profile.state)), profile.pinCode)
            Text(
                place.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
internal fun SettingsRow(icon: Int, title: String, summary: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = trailing ?: { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        )
    }
}
