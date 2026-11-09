package com.eggbusinessmanager.domain.backup

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Creating and restoring encrypted snapshots of the local database (research R9/R10). Every method
 * throws [BackupException] for expected failures. Paths are private app files.
 */
interface BackupEngine {
    suspend fun hasPassphrase(): Boolean

    /** Derives the key-encryption key from [passphrase] and stores it wrapped by the Android Keystore. */
    suspend fun setPassphrase(passphrase: String)

    /** A consistent, encrypted snapshot of the whole database (profile, customers, entries, settings). */
    suspend fun createBackup(trigger: BackupTrigger, at: Instant): BackupArtifact

    /**
     * Validates [path] in contract order (format, passphrase, integrity, version) and prepares it as the
     * staged restore. Nothing in the current data changes.
     */
    suspend fun stage(path: String, passphrase: String): BackupSummary

    suspend fun currentSummary(): CurrentDataSummary

    /** Marks the staged backup to replace the data at the next start; the caller then restarts the app. */
    suspend fun confirmStagedRestore()

    suspend fun discardStaged()

    /** The outcome of a restore that ran at the last start, returned once. */
    suspend fun consumeRestoreOutcome(): RestoreOutcome?

    /** Safety backups kept on this phone, newest first. */
    suspend fun safetyBackups(): List<BackupArtifact>

    fun delete(path: String)
}

/** The backup history (data-model.md § backup_record). */
interface BackupHistory {
    fun observe(): Flow<List<BackupRecord>>

    suspend fun start(operation: BackupOperation, destination: BackupDestination, trigger: BackupTrigger, at: Instant): Long

    suspend fun succeed(id: Long, at: Instant, sizeBytes: Long?, fileName: String?, driveFileId: String?)

    suspend fun fail(id: Long, at: Instant, failure: BackupFailure)

    /** The Drive file [driveFileId] was deleted: its records stay in the history, without a download link. */
    suspend fun forgetDriveFile(driveFileId: String)
}

/** Files the owner picks with the Storage Access Framework; [uri] is a `content://` URI string. */
interface DeviceBackupStore {
    suspend fun write(artifactPath: String, uri: String)

    /** Copies the picked file into private storage and returns the copy's path. */
    suspend fun read(uri: String): String
}

/** The app's hidden Google Drive folder. Only encrypted backup files are ever sent (Constitution I). */
interface DriveBackupStore {
    suspend fun upload(artifact: BackupArtifact): String

    suspend fun list(): List<RemoteBackup>

    /** Downloads into private storage and returns the local path. */
    suspend fun download(id: String): String

    suspend fun delete(id: String)
}

/**
 * Google authorization for the `drive.appdata` scope (research R11). [accessToken] never shows UI: when
 * the owner must (re)grant access it throws [BackupException] with [BackupFailure.AUTH_REQUIRED].
 */
interface DriveAuthorizer {
    suspend fun accessToken(): String

    /** Whether the owner has connected a Google account on this phone. */
    fun observeConnected(): Flow<Boolean>

    suspend fun setConnected(connected: Boolean)
}
