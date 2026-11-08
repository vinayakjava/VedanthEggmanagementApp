package com.eggbusinessmanager.app.feature.backup.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.backup.failureMessage
import com.eggbusinessmanager.app.ui.components.DropdownField
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.domain.backup.AutoBackupSettings
import com.eggbusinessmanager.domain.backup.AutoBackupStatus
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupFrequency
import com.eggbusinessmanager.domain.backup.ObserveAutoBackupStatusUseCase
import com.eggbusinessmanager.domain.backup.UpdateAutoBackupSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import javax.inject.Inject

const val AUTO_SWITCH_TAG = "auto_backup_switch"

@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    observeStatus: ObserveAutoBackupStatusUseCase,
    private val update: UpdateAutoBackupSettingsUseCase,
) : ViewModel() {

    val status: StateFlow<AutoBackupStatus?> = observeStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    /** Saves a change; switching on is refused (with the reason) without a passphrase or Google Drive. */
    fun change(transform: AutoBackupSettings.() -> AutoBackupSettings) {
        val current = status.value?.settings ?: return
        viewModelScope.launch {
            val problem = update(current.transform())
            // Without Google Drive the useful hint is how to switch it on, not the technical failure.
            val text = problem?.let { if (it == BackupFailure.AUTH_REQUIRED) R.string.auto_needs else failureMessage(it) }
            _message.value = text?.let { UiText.Resource(it) }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** Automatic backup settings and status, shown on the Backup & restore screen (Phase 10). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBackupSection(viewModel: AutoBackupViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val s = status?.settings ?: return
    val context = LocalContext.current
    var timeDialog by rememberSaveable { mutableStateOf(false) }
    // Ask once for notifications (Android 13+) so failures can be reported; the backup works either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.auto_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.auto_enable), Modifier.weight(1f))
                Switch(
                    checked = s.enabled,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.change { copy(enabled = on) }
                    },
                    modifier = Modifier.testTag(AUTO_SWITCH_TAG),
                )
            }
            message?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.auto_destination), style = MaterialTheme.typography.bodySmall)
            if (s.enabled) {
                Schedule(s, viewModel) { timeDialog = true }
                Options(s, viewModel)
                Status(status!!)
                if (!ignoresBatteryOptimizations(context)) BatteryHint(context)
            }
        }
    }

    if (timeDialog) {
        val state = rememberTimePickerState(s.preferredTime.hour, s.preferredTime.minute)
        AlertDialog(
            onDismissRequest = { timeDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.change { copy(preferredTime = LocalTime.of(state.hour, state.minute)) }
                    timeDialog = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Schedule(s: AutoBackupSettings, viewModel: AutoBackupViewModel, onPickTime: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Text(stringResource(R.string.auto_frequency), style = MaterialTheme.typography.labelLarge)
    val options = listOf(
        BackupFrequency.DAILY to R.string.auto_daily,
        BackupFrequency.WEEKLY to R.string.auto_weekly,
        BackupFrequency.MONTHLY to R.string.auto_monthly,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (frequency, label) ->
            SegmentedButton(
                selected = s.frequency == frequency,
                onClick = { viewModel.change { copy(frequency = frequency) } },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(stringResource(label)) },
            )
        }
    }
    when (s.frequency) {
        BackupFrequency.WEEKLY -> DropdownField(
            label = stringResource(R.string.auto_day_of_week),
            options = DayOfWeek.entries,
            selected = s.dayOfWeek,
            optionLabel = { it.getDisplayName(TextStyle.FULL, locale) },
            onSelect = { day -> viewModel.change { copy(dayOfWeek = day) } },
        )
        BackupFrequency.MONTHLY -> DropdownField(
            label = stringResource(R.string.auto_day_of_month),
            options = (1..AutoBackupSettings.MAX_DAY_OF_MONTH).toList(),
            selected = s.dayOfMonth,
            optionLabel = { it.toString() },
            onSelect = { day -> viewModel.change { copy(dayOfMonth = day) } },
        )
        BackupFrequency.DAILY -> Unit
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(s.preferredTime)
        Text(stringResource(R.string.auto_time, time), Modifier.weight(1f))
        TextButton(onClick = onPickTime) { Text(stringResource(R.string.auto_time_pick)) }
    }
}

@Composable
private fun Options(s: AutoBackupSettings, viewModel: AutoBackupViewModel) {
    DropdownField(
        label = stringResource(R.string.auto_keep_label),
        options = AutoBackupSettings.RETENTION_CHOICES,
        selected = s.keepLast,
        optionLabel = { stringResource(R.string.auto_keep_option, it) },
        onSelect = { n -> viewModel.change { copy(keepLast = n) } },
    )
    Text(stringResource(R.string.auto_keep_note), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.auto_wifi), s.wifiOnly) { on -> viewModel.change { copy(wifiOnly = on) } }
    SwitchRow(stringResource(R.string.auto_charging), s.onlyWhileCharging) { on -> viewModel.change { copy(onlyWhileCharging = on) } }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Status(status: AutoBackupStatus) {
    status.nextRun?.let {
        Text(stringResource(R.string.auto_next, formatTime(it)), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.auto_next_note), style = MaterialTheme.typography.bodySmall)
    }
    status.lastAutomaticFailure?.let { failed ->
        Text(
            stringResource(
                R.string.auto_last_failed,
                formatTime(failed.finishedAt ?: failed.startedAt),
                stringResource(failureMessage(failed.failure ?: BackupFailure.UNKNOWN)),
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** We never ask to be exempted from battery optimisation; the owner can choose in system settings. */
@Composable
private fun BatteryHint(context: Context) {
    Text(stringResource(R.string.auto_battery), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }) { Text(stringResource(R.string.auto_battery_open)) }
}

private fun ignoresBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

@Composable
private fun formatTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", locale).format(instant.atZone(ZoneId.systemDefault()))
}
