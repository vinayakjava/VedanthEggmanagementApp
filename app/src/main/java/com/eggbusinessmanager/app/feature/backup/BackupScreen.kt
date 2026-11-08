package com.eggbusinessmanager.app.feature.backup

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.PasswordField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.backup.BackupDestination
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupHeader
import com.eggbusinessmanager.domain.backup.BackupOperation
import com.eggbusinessmanager.domain.backup.BackupRecord
import com.eggbusinessmanager.domain.backup.BackupStatus
import com.eggbusinessmanager.domain.backup.BackupTrigger
import com.eggbusinessmanager.domain.backup.RestorePreview
import com.eggbusinessmanager.domain.backup.StageRestoreUseCase.Source
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

const val BACKUP_NOW_TAG = "backup_now"
const val RESTORE_CONFIRM_TAG = "restore_confirm"

/**
 * Backup & restore (Phase 9). [restoreOnly] shows only the restore part (from the welcome screen on a new
 * phone, before an account exists).
 */
@Composable
fun BackupScreen(onBack: () -> Unit, restoreOnly: Boolean = false, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val driveConnected by viewModel.driveConnected.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var downloadId by rememberSaveable { mutableStateOf<String?>(null) }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupHeader.MIME_TYPE)) {
        viewModel.backupNow(it?.toString())
    }
    val saveDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupHeader.MIME_TYPE)) { uri ->
        downloadId?.let { viewModel.download(it, uri?.toString()) }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.restoreFromDevice(it?.toString())
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onConsentResult(it.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupViewModel.Event.Consent -> consent.launch(IntentSenderRequest.Builder(event.intent).build())
                BackupViewModel.Event.Restart -> activity?.let(::restartApp)
            }
        }
    }

    FormScreen(title = stringResource(if (restoreOnly) R.string.welcome_restore else R.string.backup_title), onBack = onBack) {
        state.message?.let { MessageCard(it.asString(), onDismiss = viewModel::messageShown) }
        if (!restoreOnly) {
            LastBackupCard(status.lastSuccess)
            PassphraseCard(state.hasPassphrase, viewModel)
        }
        DriveCard(driveConnected, state.busy, viewModel)
        if (!restoreOnly) {
            BackupNowCard(state, driveConnected, viewModel) { saveBackup.launch(suggestedName()) }
        }
        RestoreCard(state.busy, driveConnected, viewModel, onPickFile = { openBackup.launch(arrayOf("*/*")) })
        if (!restoreOnly) {
            HistoryCard(status.history) { record ->
                downloadId = record.driveFileId
                saveDownload.launch(record.fileName ?: suggestedName())
            }
        }
    }

    if (state.passphraseDialog) PassphraseDialog(state, viewModel)
    RestoreDialogs(state, viewModel)
}

private fun suggestedName(): String =
    "EggBusiness_backup_${DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.LocalDateTime.now())}.${BackupHeader.EXTENSION}"

/** Relaunches the app so the confirmed restore is swapped in before the database opens. */
private fun restartApp(activity: Activity) {
    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
    activity.startActivity(intent)
    activity.finishAffinity()
    exitProcess(0)
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun formatTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).format(instant.atZone(ZoneId.systemDefault()))
}

@Composable
private fun destinationLabel(destination: BackupDestination) =
    stringResource(if (destination == BackupDestination.DEVICE) R.string.bk_dest_device else R.string.bk_dest_drive)

@Composable
private fun LastBackupCard(last: BackupRecord?) {
    Section(stringResource(R.string.backup_last)) {
        if (last == null) {
            Text(
                stringResource(R.string.backup_last_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                stringResource(
                    R.string.backup_last_line,
                    formatTime(last.finishedAt ?: last.startedAt),
                    destinationLabel(last.destination),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun PassphraseCard(hasPassphrase: Boolean, viewModel: BackupViewModel) {
    Section(stringResource(R.string.backup_passphrase_title)) {
        Text(
            stringResource(if (hasPassphrase) R.string.backup_passphrase_ok else R.string.backup_passphrase_missing),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { viewModel.openPassphrase(true) }) {
            Text(stringResource(if (hasPassphrase) R.string.backup_passphrase_change else R.string.backup_passphrase_set))
        }
    }
}

@Composable
private fun DriveCard(connected: Boolean, busy: Boolean, viewModel: BackupViewModel) {
    Section(stringResource(R.string.drive_title)) {
        Text(
            stringResource(if (connected) R.string.drive_connected else R.string.drive_not_connected),
            style = MaterialTheme.typography.titleSmall,
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.drive_privacy), style = MaterialTheme.typography.bodySmall)
        if (connected) {
            TextButton(onClick = viewModel::disconnectDrive) { Text(stringResource(R.string.drive_disconnect)) }
        } else {
            OutlinedButton(onClick = viewModel::connectDrive, enabled = !busy) { Text(stringResource(R.string.drive_connect)) }
        }
    }
}

@Composable
private fun BackupNowCard(
    state: BackupViewModel.UiState,
    driveConnected: Boolean,
    viewModel: BackupViewModel,
    onPickDeviceFile: () -> Unit,
) {
    Section(stringResource(R.string.backup_dest_heading)) {
        DestinationOption(stringResource(R.string.backup_dest_device), state.destination == BackupDestination.DEVICE) {
            viewModel.onDestination(BackupDestination.DEVICE)
        }
        DestinationOption(stringResource(R.string.backup_dest_drive), state.destination == BackupDestination.GOOGLE_DRIVE) {
            viewModel.onDestination(BackupDestination.GOOGLE_DRIVE)
        }
        val ready = state.hasPassphrase && (state.destination == BackupDestination.DEVICE || driveConnected)
        PrimaryButton(
            text = stringResource(if (state.busy) R.string.backup_running else R.string.backup_now),
            onClick = { if (state.destination == BackupDestination.DEVICE) onPickDeviceFile() else viewModel.backupNow(null) },
            modifier = Modifier.testTag(BACKUP_NOW_TAG),
            busy = state.busy,
            enabled = ready,
        )
    }
}

@Composable
private fun DestinationOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RestoreCard(busy: Boolean, driveConnected: Boolean, viewModel: BackupViewModel, onPickFile: () -> Unit) {
    Section(stringResource(R.string.restore_title)) {
        OutlinedButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.restore_from_device))
        }
        OutlinedButton(onClick = viewModel::restoreFromDrive, enabled = !busy && driveConnected, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.restore_from_drive))
        }
        OutlinedButton(onClick = viewModel::restoreFromSafety, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.restore_from_safety))
        }
    }
}

@Composable
private fun HistoryCard(records: List<BackupRecord>, onDownload: (BackupRecord) -> Unit) {
    Section(stringResource(R.string.bk_history_title)) {
        if (records.isEmpty()) Text(stringResource(R.string.bk_history_empty), style = MaterialTheme.typography.bodyMedium)
        records.take(HISTORY_SHOWN).forEach { record ->
            HistoryRow(record, onDownload)
            HorizontalDivider()
        }
    }
}

@Composable
private fun HistoryRow(record: BackupRecord, onDownload: (BackupRecord) -> Unit) {
    val kind = when {
        record.operation == BackupOperation.RESTORE -> R.string.bk_history_restore
        record.trigger == BackupTrigger.SAFETY -> R.string.bk_history_safety
        else -> R.string.bk_history_backup
    }
    val outcome = when (record.status) {
        BackupStatus.SUCCESS -> stringResource(R.string.bk_history_ok)
        BackupStatus.FAILED -> {
            val reason = stringResource(failureMessage(record.failure ?: BackupFailure.UNKNOWN))
            stringResource(R.string.bk_history_failed, reason)
        }
        BackupStatus.IN_PROGRESS -> stringResource(R.string.bk_history_running)
    }
    ListItem(
        headlineContent = { Text("${stringResource(kind)} · ${destinationLabel(record.destination)}") },
        supportingContent = {
            Text(listOfNotNull(formatTime(record.startedAt), record.sizeBytes?.let { size(it) }, outcome).joinToString(" · "))
        },
        trailingContent = if (record.status == BackupStatus.SUCCESS && record.driveFileId != null) {
            { TextButton(onClick = { onDownload(record) }) { Text(stringResource(R.string.bk_history_download)) } }
        } else {
            null
        },
    )
}

@Composable
private fun size(bytes: Long): String = if (bytes < MB) {
    stringResource(R.string.bk_size_kb, IndianFormatters.group(maxOf(1, bytes / KB)))
} else {
    stringResource(R.string.bk_size_mb, "%.1f".format(bytes / MB.toDouble()))
}

@Composable
private fun PassphraseDialog(state: BackupViewModel.UiState, viewModel: BackupViewModel) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { viewModel.openPassphrase(false) },
        title = { Text(stringResource(R.string.backup_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_passphrase_body), style = MaterialTheme.typography.bodyMedium)
                PasswordField(passphrase, { passphrase = it }, stringResource(R.string.label_backup_passphrase))
                PasswordField(
                    confirmation,
                    { confirmation = it },
                    stringResource(R.string.label_backup_passphrase_confirm),
                    error = state.passphraseError,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.savePassphrase(passphrase, confirmation) }, enabled = !state.busy) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.openPassphrase(false) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RestoreDialogs(state: BackupViewModel.UiState, viewModel: BackupViewModel) {
    when (val step = state.restore) {
        BackupViewModel.RestoreStep.None -> Unit
        is BackupViewModel.RestoreStep.PickDrive -> PickDialog(
            items = step.backups.map { Triple(Source.Drive(it.id), formatTime(it.createdAt), size(it.sizeBytes)) },
            empty = stringResource(R.string.restore_drive_empty),
            onPick = viewModel::pick,
            onDismiss = viewModel::cancelRestore,
        )
        is BackupViewModel.RestoreStep.PickSafety -> PickDialog(
            items = step.backups.map { Triple(Source.Safety(it.path), formatTime(it.header.createdAt), size(it.sizeBytes)) },
            empty = stringResource(R.string.restore_safety_empty),
            onPick = viewModel::pick,
            onDismiss = viewModel::cancelRestore,
        )
        is BackupViewModel.RestoreStep.AskPassphrase -> RestorePassphraseDialog(step, state.busy, viewModel)
        is BackupViewModel.RestoreStep.Preview -> PreviewDialog(step.preview, state.busy, viewModel)
    }
}

@Composable
private fun PickDialog(items: List<Triple<Source, String, String>>, empty: String, onPick: (Source) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_pick)) },
        text = {
            if (items.isEmpty()) {
                Text(empty)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(items) { (source, date, size) ->
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.restore_item, date, size)) },
                            modifier = Modifier.clickable { onPick(source) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RestorePassphraseDialog(step: BackupViewModel.RestoreStep.AskPassphrase, busy: Boolean, viewModel: BackupViewModel) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::cancelRestore,
        title = { Text(stringResource(R.string.restore_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (busy) R.string.restore_checking else R.string.restore_passphrase_body))
                PasswordField(passphrase, { passphrase = it }, stringResource(R.string.label_backup_passphrase), error = step.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.check(passphrase) }, enabled = !busy && passphrase.isNotEmpty()) {
                Text(stringResource(R.string.restore_check))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** FR-050: the backup's contents next to the data on the phone now; restoring needs an explicit acknowledgement. */
@Composable
private fun PreviewDialog(preview: RestorePreview, busy: Boolean, viewModel: BackupViewModel) {
    var understood by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val b = preview.backup
    val c = preview.current
    AlertDialog(
        onDismissRequest = viewModel::cancelRestore,
        title = { Text(stringResource(R.string.restore_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.restore_preview_backup, formatTime(b.createdAt)), style = MaterialTheme.typography.titleSmall)
                b.shopName?.let { Text(stringResource(R.string.restore_shop, it)) }
                Text(stringResource(R.string.restore_counts, b.customers, b.entries))
                b.lastEntryDate?.let { Text(stringResource(R.string.restore_last_entry, IndianFormatters.date(it, locale))) }
                Text(stringResource(R.string.restore_device, b.deviceModel), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.restore_preview_current), style = MaterialTheme.typography.titleSmall)
                c.shopName?.let { Text(stringResource(R.string.restore_shop, it)) }
                Text(stringResource(R.string.restore_counts, c.customers, c.entries))
                c.lastEntryDate?.let { Text(stringResource(R.string.restore_last_entry, IndianFormatters.date(it, locale))) }
                Text(
                    stringResource(R.string.restore_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text(stringResource(R.string.restore_understand))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::confirm,
                enabled = understood && !busy,
                modifier = Modifier.testTag(RESTORE_CONFIRM_TAG),
            ) { Text(stringResource(R.string.restore_confirm)) }
        },
        dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val HISTORY_SHOWN = 30
private const val KB = 1024L
private const val MB = 1024L * 1024L
