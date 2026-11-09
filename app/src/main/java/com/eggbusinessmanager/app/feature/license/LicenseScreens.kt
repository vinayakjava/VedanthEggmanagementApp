package com.eggbusinessmanager.app.feature.license

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.BrandMark
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.license.ActivateLicenseUseCase
import com.eggbusinessmanager.domain.license.ActiveLicense
import com.eggbusinessmanager.domain.license.CheckLicenseUseCase
import com.eggbusinessmanager.domain.license.GetDeviceCodeUseCase
import com.eggbusinessmanager.domain.license.LicenseCheck
import com.eggbusinessmanager.domain.license.LicenseStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

const val DEVICE_CODE_TAG = "license_device_code"
const val LICENSE_KEY_FIELD_TAG = "license_key_field"

/**
 * The app-wide license state: checked once at start (offline), then updated on activation. Null while
 * the first check runs.
 */
@Singleton
class LicenseGate @Inject constructor(private val check: CheckLicenseUseCase) {
    private val _status = MutableStateFlow<LicenseStatus?>(null)
    val status: StateFlow<LicenseStatus?> = _status.asStateFlow()

    suspend fun refresh() {
        _status.value = check()
    }

    fun activated(license: ActiveLicense) {
        _status.value = LicenseStatus.Activated(license)
    }
}

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val deviceCode: GetDeviceCodeUseCase,
    private val activate: ActivateLicenseUseCase,
    private val gate: LicenseGate,
) : ViewModel() {

    data class UiState(val deviceCode: String = "", val key: String = "", val error: Int? = null, val busy: Boolean = false)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(deviceCode = deviceCode()) } }
    }

    fun onKey(value: String) = _state.update { it.copy(key = value, error = null) }

    fun submit() {
        val current = _state.value
        if (current.busy) return
        if (current.key.isBlank()) {
            _state.update { it.copy(error = R.string.license_error_empty) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = activate(current.key)) {
                is ActivateLicenseUseCase.Result.Activated -> gate.activated(result.license)
                is ActivateLicenseUseCase.Result.Rejected ->
                    _state.update { it.copy(busy = false, error = rejectionMessage(result.reason)) }
            }
        }
    }

    private fun rejectionMessage(reason: LicenseCheck): Int = when (reason) {
        LicenseCheck.OtherDevice -> R.string.license_error_other_device
        LicenseCheck.NotGenuine -> R.string.license_error_not_genuine
        else -> R.string.license_error_malformed
    }
}

/**
 * Shown before anything else until this phone is activated (once; offline afterwards). Business data is
 * never touched here: an existing shop that needs a new key keeps all its data.
 */
@Composable
fun ActivationScreen(viewModel: ActivationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    FormScreen(title = stringResource(R.string.license_activate_title), onBack = null) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandMark(size = 72.dp)
            Text(stringResource(R.string.license_activate_body), style = MaterialTheme.typography.bodyLarge)
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.license_step_send), style = MaterialTheme.typography.titleSmall)
                Text(
                    state.deviceCode,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(DEVICE_CODE_TAG),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(context, state.deviceCode) }) { Text(stringResource(R.string.license_copy)) }
                    OutlinedButton(onClick = { shareCode(context, state.deviceCode) }) { Text(stringResource(R.string.license_share)) }
                }
                DeveloperContact()
            }
        }
        Text(stringResource(R.string.license_step_paste), style = MaterialTheme.typography.titleSmall)
        FormTextField(
            state.key, viewModel::onKey, stringResource(R.string.license_key_label),
            singleLine = false, modifier = Modifier.testTag(LICENSE_KEY_FIELD_TAG),
        )
        TextButton(onClick = { paste(context)?.let(viewModel::onKey) }) { Text(stringResource(R.string.license_paste)) }
        state.error?.let { ErrorBanner(stringResource(it)) }
        PrimaryButton(stringResource(R.string.license_activate_action), onClick = viewModel::submit, busy = state.busy)
        Text(
            stringResource(R.string.license_activate_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

/** "Buy or get your key from …" with tap-to-call, the developer from About. */
@Composable
private fun DeveloperContact() {
    val context = LocalContext.current
    val phone = stringResource(R.string.about_developer_phone)
    val name = stringResource(R.string.about_developer_name)
    TextButton(onClick = {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it == '+' || it.isDigit() })))
        } catch (ignored: ActivityNotFoundException) {
            // No phone app: the number stays visible to copy.
        }
    }) { Text(stringResource(R.string.license_contact, name, phone)) }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Device code", text))
}

private fun paste(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()

private fun shareCode(context: Context, code: String) {
    val text = context.getString(R.string.license_share_text, code)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.license_share)))
    } catch (ignored: ActivityNotFoundException) {
        copy(context, code) // nothing can share: at least the code is on the clipboard
    }
}

@HiltViewModel
class LicenseInfoViewModel @Inject constructor(gate: LicenseGate, private val deviceCode: GetDeviceCodeUseCase) : ViewModel() {
    val status: StateFlow<LicenseStatus?> = gate.status
    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    init {
        viewModelScope.launch { _code.value = deviceCode() }
    }
}

/** Settings → License: Activated · Lifetime License, with the license id, device limit and this phone's code. */
@Composable
fun LicenseScreen(onBack: () -> Unit, viewModel: LicenseInfoViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val code by viewModel.code.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    FormScreen(title = stringResource(R.string.license_title), onBack = onBack) {
        val license = (status as? LicenseStatus.Activated)?.license
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(if (license != null) R.string.license_status_activated else R.string.license_status_not_activated),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (license != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (license != null) {
                    Text(stringResource(R.string.license_lifetime), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.license_lifetime_note), style = MaterialTheme.typography.bodyMedium)
                    Info(R.string.license_id_label, license.licenseId)
                    Info(R.string.license_devices_label, license.maxDevices.toString())
                    val activatedOn = license.activatedAt.atZone(ZoneId.systemDefault()).toLocalDate()
                    Info(R.string.license_activated_on, IndianFormatters.date(activatedOn, locale))
                }
                Info(R.string.license_device_code_label, code)
            }
        }
        Text(
            stringResource(R.string.license_info_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DeveloperContact()
    }
}

@Composable
private fun Info(label: Int, value: String) {
    Text(stringResource(R.string.license_info_line, stringResource(label), value), style = MaterialTheme.typography.bodyMedium)
}
