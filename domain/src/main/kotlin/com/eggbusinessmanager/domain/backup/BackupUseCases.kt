package com.eggbusinessmanager.domain.backup

import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.SecretHasher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Outcome of a backup operation: a value or one [BackupFailure]. */
sealed interface BackupResult<out T> {
    data class Ok<T>(val value: T) : BackupResult<T>
    data class Failed(val failure: BackupFailure) : BackupResult<Nothing>
}

/** Runs [block], turning expected and unexpected errors into [BackupResult.Failed]; cancellation passes through. */
internal suspend fun <T> backupResult(block: suspend () -> T): BackupResult<T> = try {
    BackupResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: BackupException) {
    BackupResult.Failed(e.failure)
} catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
    // Unexpected errors become a generic, user-facing failure; they never crash the backup screen.
    BackupResult.Failed(BackupFailure.UNKNOWN)
}

/** Sets or changes the backup passphrase (FR-045). It must differ from the login password. */
class SetBackupPassphraseUseCase @Inject constructor(
    private val engine: BackupEngine,
    private val accounts: AccountRepository,
    private val hasher: SecretHasher,
) {
    suspend operator fun invoke(passphrase: String, confirmation: String): BackupPassphraseRules.Problem? {
        val sameAsPassword = accounts.credentials()?.let { hasher.verify(passphrase, it.password) } ?: false
        BackupPassphraseRules.check(passphrase, confirmation, sameAsPassword)?.let { return it }
        engine.setPassphrase(passphrase)
        return null
    }
}

/** Last successful backup and the full history, newest first (FR-048). */
class ObserveBackupStatusUseCase @Inject constructor(private val history: BackupHistory) {
    data class Status(val lastSuccess: BackupRecord?, val history: List<BackupRecord>)

    operator fun invoke(): Flow<Status> = history.observe().map { records ->
        val sorted = records.sortedByDescending { it.startedAt }
        Status(
            lastSuccess = sorted.firstOrNull { it.operation == BackupOperation.BACKUP && it.status == BackupStatus.SUCCESS },
            history = sorted,
        )
    }
}

/**
 * A manual backup to the phone (a file the owner picked) or to Google Drive (FR-044/046). The snapshot is
 * encrypted before it leaves the database; the local temporary file is always deleted; every attempt
 * is recorded in the history with its outcome.
 */
class RunBackupUseCase @Inject constructor(
    private val engine: BackupEngine,
    private val history: BackupHistory,
    private val device: DeviceBackupStore,
    private val drive: DriveBackupStore,
    private val lock: BackupLock,
    private val clock: Clock,
) {
    /** [deviceUri] is required for [BackupDestination.DEVICE]. */
    suspend operator fun invoke(destination: BackupDestination, deviceUri: String? = null): BackupResult<BackupRecord> {
        if (!engine.hasPassphrase()) return BackupResult.Failed(BackupFailure.NO_PASSPHRASE)
        if (!lock.mutex.tryLock()) return BackupResult.Failed(BackupFailure.BUSY)
        return try {
            backup(destination, deviceUri)
        } finally {
            lock.mutex.unlock()
        }
    }

    private suspend fun backup(destination: BackupDestination, deviceUri: String?): BackupResult<BackupRecord> {
        val id = history.start(BackupOperation.BACKUP, destination, BackupTrigger.MANUAL, clock.now())
        var artifact: BackupArtifact? = null
        val result = backupResult {
            val made = engine.createBackup(BackupTrigger.MANUAL, clock.now()).also { artifact = it }
            val driveId = when (destination) {
                BackupDestination.DEVICE -> {
                    device.write(made.path, deviceUri ?: throw BackupException(BackupFailure.LOCATION_UNAVAILABLE))
                    null
                }
                BackupDestination.GOOGLE_DRIVE -> drive.upload(made)
            }
            history.succeed(id, clock.now(), made.sizeBytes, made.fileName, driveId)
            made
        }
        artifact?.let { engine.delete(it.path) }
        return when (result) {
            is BackupResult.Ok -> BackupResult.Ok(recordOf(id))
            is BackupResult.Failed -> {
                history.fail(id, clock.now(), result.failure)
                result
            }
        }
    }

    private suspend fun recordOf(id: Long): BackupRecord = history.observe().first().first { it.id == id }
}

/** Lists the backups in the app's Google Drive folder, newest first. */
class ListDriveBackupsUseCase @Inject constructor(private val drive: DriveBackupStore) {
    suspend operator fun invoke(): BackupResult<List<RemoteBackup>> = backupResult { drive.list().sortedByDescending { it.createdAt } }
}

/** "Download": saves a Drive backup as a file the owner picks on the phone (FR-047). */
class DownloadDriveBackupUseCase @Inject constructor(
    private val drive: DriveBackupStore,
    private val device: DeviceBackupStore,
    private val engine: BackupEngine,
) {
    suspend operator fun invoke(id: String, deviceUri: String): BackupResult<Unit> = backupResult {
        val path = drive.download(id)
        try {
            device.write(path, deviceUri)
        } finally {
            engine.delete(path)
        }
    }
}

/** What the owner sees before confirming a restore: the backup's contents next to the data on the phone now. */
data class RestorePreview(val backup: BackupSummary, val current: CurrentDataSummary)

/**
 * Step 1 of a restore: fetch the file, validate it fully (format, passphrase, integrity, version) and
 * stage it. Nothing on the phone changes (FR-050).
 */
class StageRestoreUseCase @Inject constructor(
    private val engine: BackupEngine,
    private val device: DeviceBackupStore,
    private val drive: DriveBackupStore,
) {
    sealed interface Source {
        data class Device(val uri: String) : Source
        data class Drive(val id: String) : Source
        data class Safety(val path: String) : Source
    }

    suspend operator fun invoke(source: Source, passphrase: String): BackupResult<RestorePreview> = backupResult {
        val (path, temporary) = when (source) {
            is Source.Device -> device.read(source.uri) to true
            is Source.Drive -> drive.download(source.id) to true
            is Source.Safety -> source.path to false
        }
        try {
            RestorePreview(engine.stage(path, passphrase), engine.currentSummary())
        } finally {
            if (temporary) engine.delete(path)
        }
    }
}

/**
 * Step 2, only after the owner confirmed the preview (FR-050): a safety backup of the current data is
 * made first (FR-051), the restore is recorded, and the staged data is marked to replace the database
 * at the next start, where it is swapped in, verified, and rolled back automatically if anything fails.
 */
class ConfirmRestoreUseCase @Inject constructor(
    private val engine: BackupEngine,
    private val history: BackupHistory,
    private val lock: BackupLock,
    private val clock: Clock,
) {
    suspend operator fun invoke(): BackupResult<Unit> {
        if (!lock.mutex.tryLock()) return BackupResult.Failed(BackupFailure.BUSY)
        return try {
            confirm()
        } finally {
            lock.mutex.unlock()
        }
    }

    private suspend fun confirm(): BackupResult<Unit> = backupResult {
        val safety = engine.createBackup(BackupTrigger.SAFETY, clock.now())
        val safetyId = history.start(BackupOperation.BACKUP, BackupDestination.DEVICE, BackupTrigger.SAFETY, clock.now())
        history.succeed(safetyId, clock.now(), safety.sizeBytes, safety.fileName, null)
        history.start(BackupOperation.RESTORE, BackupDestination.DEVICE, BackupTrigger.MANUAL, clock.now())
        engine.confirmStagedRestore()
    }
}

/** After a restart: whether the restore was applied or rolled back (shown once). */
class ConsumeRestoreOutcomeUseCase @Inject constructor(private val engine: BackupEngine) {
    suspend operator fun invoke(): RestoreOutcome? = engine.consumeRestoreOutcome()
}

/** Cancels a staged restore; the current data stays as it is. */
class DiscardRestoreUseCase @Inject constructor(private val engine: BackupEngine) {
    suspend operator fun invoke() = engine.discardStaged()
}
