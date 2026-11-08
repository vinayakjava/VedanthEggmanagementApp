package com.eggbusinessmanager.app.feature.backup

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.domain.backup.BackupArtifact
import com.eggbusinessmanager.domain.backup.BackupDestination
import com.eggbusinessmanager.domain.backup.BackupEngine
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupPassphraseRules
import com.eggbusinessmanager.domain.backup.BackupResult
import com.eggbusinessmanager.domain.backup.ConfirmRestoreUseCase
import com.eggbusinessmanager.domain.backup.DiscardRestoreUseCase
import com.eggbusinessmanager.domain.backup.DownloadDriveBackupUseCase
import com.eggbusinessmanager.domain.backup.DriveAuthorizer
import com.eggbusinessmanager.domain.backup.ListDriveBackupsUseCase
import com.eggbusinessmanager.domain.backup.ObserveBackupStatusUseCase
import com.eggbusinessmanager.domain.backup.RemoteBackup
import com.eggbusinessmanager.domain.backup.RestorePreview
import com.eggbusinessmanager.domain.backup.RunBackupUseCase
import com.eggbusinessmanager.domain.backup.SetBackupPassphraseUseCase
import com.eggbusinessmanager.domain.backup.StageRestoreUseCase
import com.eggbusinessmanager.domain.backup.StageRestoreUseCase.Source
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backup & restore (Phase 9): passphrase, Google Drive connection, backup now, restore with preview, history. */
@HiltViewModel
class BackupViewModel @Inject constructor(
    observeStatus: ObserveBackupStatusUseCase,
    private val engine: BackupEngine,
    private val setPassphrase: SetBackupPassphraseUseCase,
    private val runBackup: RunBackupUseCase,
    private val listDrive: ListDriveBackupsUseCase,
    private val downloadDrive: DownloadDriveBackupUseCase,
    private val stageRestore: StageRestoreUseCase,
    private val confirmRestore: ConfirmRestoreUseCase,
    private val discardRestore: DiscardRestoreUseCase,
    private val authorizer: DriveAuthorizer,
) : ViewModel() {

    /** The restore dialog flow. */
    sealed interface RestoreStep {
        data object None : RestoreStep
        data class PickDrive(val backups: List<RemoteBackup>) : RestoreStep
        data class PickSafety(val backups: List<BackupArtifact>) : RestoreStep
        data class AskPassphrase(val source: Source, val error: UiText? = null) : RestoreStep
        data class Preview(val preview: RestorePreview) : RestoreStep
    }

    data class UiState(
        val hasPassphrase: Boolean = false,
        val destination: BackupDestination = BackupDestination.DEVICE,
        val busy: Boolean = false,
        val message: UiText? = null,
        val restore: RestoreStep = RestoreStep.None,
        val passphraseError: UiText? = null,
        val passphraseDialog: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val status: StateFlow<ObserveBackupStatusUseCase.Status> = observeStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ObserveBackupStatusUseCase.Status(null, emptyList()))

    val driveConnected: StateFlow<Boolean> = authorizer.observeConnected()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    /** One-shot UI actions the screen performs: Google consent and the restart after a confirmed restore. */
    sealed interface Event {
        data class Consent(val intent: PendingIntent) : Event
        data object Restart : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(hasPassphrase = engine.hasPassphrase()) } }
    }

    fun onDestination(destination: BackupDestination) = _state.update { it.copy(destination = destination) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun busy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ---- Passphrase ----

    fun openPassphrase(open: Boolean) = _state.update { it.copy(passphraseDialog = open, passphraseError = null) }

    fun savePassphrase(passphrase: String, confirmation: String) = busy {
        val problem = setPassphrase(passphrase, confirmation)
        _state.update {
            if (problem == null) {
                val saved = UiText.Resource(R.string.backup_pp_saved)
                it.copy(hasPassphrase = true, passphraseDialog = false, passphraseError = null, message = saved)
            } else {
                it.copy(passphraseError = UiText.Resource(passphraseMessage(problem)))
            }
        }
    }

    // ---- Google Drive ----

    fun connectDrive() = busy {
        val consent = runCatching { (authorizer as? GoogleDriveAuthorizer)?.beginConnect() }
        consent.exceptionOrNull()?.let { fail(failureOf(it)) }
        consent.getOrNull()?.let { _events.send(Event.Consent(it)) }
    }

    fun onConsentResult(data: Intent?) = busy {
        (authorizer as? GoogleDriveAuthorizer)?.completeConnect(data)
    }

    fun disconnectDrive() = viewModelScope.launch { authorizer.setConnected(false) }

    // ---- Backup ----

    /** [deviceUri] is the file picked with "Save as" for a backup to the phone. */
    fun backupNow(deviceUri: String?) {
        val destination = _state.value.destination
        if (destination == BackupDestination.DEVICE && deviceUri == null) return
        busy {
            when (val result = runBackup(destination, deviceUri)) {
                is BackupResult.Ok -> {
                    val done = UiText.Resource(R.string.backup_done, listOf(result.value.fileName.orEmpty()))
                    _state.update { it.copy(message = done) }
                }
                is BackupResult.Failed -> fail(result.failure)
            }
        }
    }

    fun download(id: String, deviceUri: String?) {
        if (deviceUri == null) return
        busy {
            when (val result = downloadDrive(id, deviceUri)) {
                is BackupResult.Ok -> _state.update { it.copy(message = UiText.Resource(R.string.bk_downloaded)) }
                is BackupResult.Failed -> fail(result.failure)
            }
        }
    }

    // ---- Restore ----

    fun restoreFromDevice(uri: String?) {
        if (uri != null) _state.update { it.copy(restore = RestoreStep.AskPassphrase(Source.Device(uri))) }
    }

    fun restoreFromDrive() = busy {
        when (val result = listDrive()) {
            is BackupResult.Ok -> _state.update { it.copy(restore = RestoreStep.PickDrive(result.value)) }
            is BackupResult.Failed -> fail(result.failure)
        }
    }

    fun restoreFromSafety() = busy {
        _state.update { it.copy(restore = RestoreStep.PickSafety(engine.safetyBackups())) }
    }

    fun pick(source: Source) = _state.update { it.copy(restore = RestoreStep.AskPassphrase(source)) }

    /** Validates and stages the backup; nothing changes until [confirm]. */
    fun check(passphrase: String) {
        val step = _state.value.restore as? RestoreStep.AskPassphrase ?: return
        busy {
            when (val result = stageRestore(step.source, passphrase)) {
                is BackupResult.Ok -> _state.update { it.copy(restore = RestoreStep.Preview(result.value)) }
                is BackupResult.Failed -> _state.update {
                    if (result.failure == BackupFailure.WRONG_PASSPHRASE) {
                        it.copy(restore = step.copy(error = UiText.Resource(R.string.bf_wrong_passphrase)))
                    } else {
                        it.copy(restore = RestoreStep.None, message = UiText.Resource(failureMessage(result.failure)))
                    }
                }
            }
        }
    }

    /** Safety backup, then the staged data replaces the current data at the restart. */
    fun confirm() = busy {
        when (val result = confirmRestore()) {
            is BackupResult.Ok -> _events.send(Event.Restart)
            is BackupResult.Failed -> {
                discardRestore()
                _state.update { it.copy(restore = RestoreStep.None) }
                fail(result.failure)
            }
        }
    }

    fun cancelRestore() {
        viewModelScope.launch { discardRestore() }
        _state.update { it.copy(restore = RestoreStep.None) }
    }

    private fun fail(failure: BackupFailure) = _state.update { it.copy(message = UiText.Resource(failureMessage(failure))) }

    private fun failureOf(error: Throwable): BackupFailure =
        (error as? com.eggbusinessmanager.domain.backup.BackupException)?.failure ?: BackupFailure.UNKNOWN

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@StringRes
fun failureMessage(failure: BackupFailure): Int = when (failure) {
    BackupFailure.NO_PASSPHRASE -> R.string.bf_no_passphrase
    BackupFailure.NO_NETWORK -> R.string.bf_no_network
    BackupFailure.AUTH_REQUIRED -> R.string.bf_auth_required
    BackupFailure.DRIVE_FULL -> R.string.bf_drive_full
    BackupFailure.LOCATION_UNAVAILABLE -> R.string.bf_location_unavailable
    BackupFailure.LOW_STORAGE -> R.string.bf_low_storage
    BackupFailure.WRONG_PASSPHRASE -> R.string.bf_wrong_passphrase
    BackupFailure.CORRUPT -> R.string.bf_corrupt
    BackupFailure.NEWER_VERSION -> R.string.bf_newer_version
    BackupFailure.NOT_A_BACKUP -> R.string.bf_not_a_backup
    BackupFailure.INTERRUPTED -> R.string.bf_interrupted
    BackupFailure.UNKNOWN -> R.string.bf_unknown
}

@StringRes
private fun passphraseMessage(problem: BackupPassphraseRules.Problem): Int = when (problem) {
    BackupPassphraseRules.Problem.TOO_SHORT -> R.string.v_pp_short
    BackupPassphraseRules.Problem.TOO_LONG -> R.string.v_pp_long
    BackupPassphraseRules.Problem.MISMATCH -> R.string.v_pp_mismatch
    BackupPassphraseRules.Problem.SAME_AS_PASSWORD -> R.string.v_pp_same
}
