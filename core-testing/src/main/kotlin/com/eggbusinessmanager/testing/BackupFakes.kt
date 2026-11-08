package com.eggbusinessmanager.testing

import com.eggbusinessmanager.domain.backup.BackupArtifact
import com.eggbusinessmanager.domain.backup.BackupDestination
import com.eggbusinessmanager.domain.backup.BackupEngine
import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupHeader
import com.eggbusinessmanager.domain.backup.BackupHistory
import com.eggbusinessmanager.domain.backup.BackupOperation
import com.eggbusinessmanager.domain.backup.BackupRecord
import com.eggbusinessmanager.domain.backup.BackupStatus
import com.eggbusinessmanager.domain.backup.BackupSummary
import com.eggbusinessmanager.domain.backup.BackupTrigger
import com.eggbusinessmanager.domain.backup.CurrentDataSummary
import com.eggbusinessmanager.domain.backup.DeviceBackupStore
import com.eggbusinessmanager.domain.backup.DriveBackupStore
import com.eggbusinessmanager.domain.backup.RemoteBackup
import com.eggbusinessmanager.domain.backup.RestoreOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** In-memory backup engine: "files" are names in a set; the passphrase is kept in clear (tests only). */
class FakeBackupEngine : BackupEngine {
    var passphrase: String? = null
    val files = mutableSetOf<String>()
    val events = mutableListOf<String>()
    var failCreateWith: BackupFailure? = null
    var staged: String? = null
    var confirmed = false
    /** Which local files hold which passphrase, for [stage]. */
    val passphrases = mutableMapOf<String, String>()
    private var counter = 0

    override suspend fun hasPassphrase() = passphrase != null

    override suspend fun setPassphrase(passphrase: String) {
        this.passphrase = passphrase
    }

    override suspend fun createBackup(trigger: BackupTrigger, at: Instant): BackupArtifact {
        failCreateWith?.let { throw BackupException(it) }
        val name = "backup-${++counter}-$trigger"
        files += name
        events += "create:$trigger"
        val header = BackupHeader(1, at, "1.0", 1, 1, "Test", trigger, 600_000, ByteArray(16), 100, "00")
        return BackupArtifact(name, "$name.ebmbackup", 100, header)
    }

    override suspend fun stage(path: String, passphrase: String): BackupSummary {
        if (path !in files) throw BackupException(BackupFailure.NOT_A_BACKUP)
        if (passphrases[path] != null && passphrases[path] != passphrase) throw BackupException(BackupFailure.WRONG_PASSPHRASE)
        staged = path
        events += "stage:$path"
        return BackupSummary(Instant.EPOCH, "Patil Egg Centre", "Ramesh", 4, 20, null, 1, "Test")
    }

    override suspend fun currentSummary() = CurrentDataSummary("Patil Egg Centre", 2, 5, null)

    override suspend fun confirmStagedRestore() {
        checkNotNull(staged) { "Nothing staged" }
        confirmed = true
        events += "confirm"
    }

    override suspend fun discardStaged() {
        staged = null
    }

    var outcome: RestoreOutcome? = null

    override suspend fun consumeRestoreOutcome(): RestoreOutcome? = outcome.also { outcome = null }

    override suspend fun safetyBackups(): List<BackupArtifact> = emptyList()

    override fun delete(path: String) {
        files -= path
        events += "delete:$path"
    }
}

class FakeBackupHistory : BackupHistory {
    val records = MutableStateFlow<List<BackupRecord>>(emptyList())
    private var nextId = 1L

    override fun observe(): Flow<List<BackupRecord>> = records

    override suspend fun start(operation: BackupOperation, destination: BackupDestination, trigger: BackupTrigger, at: Instant): Long {
        val id = nextId++
        records.value += BackupRecord(id, operation, destination, trigger, BackupStatus.IN_PROGRESS, at, null, null, null, null, 1, null)
        return id
    }

    override suspend fun succeed(id: Long, at: Instant, sizeBytes: Long?, fileName: String?, driveFileId: String?) = change(id) {
        it.copy(status = BackupStatus.SUCCESS, finishedAt = at, sizeBytes = sizeBytes, fileName = fileName, driveFileId = driveFileId)
    }

    override suspend fun fail(id: Long, at: Instant, failure: BackupFailure) = change(id) {
        it.copy(status = BackupStatus.FAILED, finishedAt = at, failure = failure)
    }

    private fun change(id: Long, transform: (BackupRecord) -> BackupRecord) {
        records.value = records.value.map { if (it.id == id) transform(it) else it }
    }
}

/** Phone storage picked with SAF: [written] maps URIs to the artifact copied there. */
class FakeDeviceBackupStore(private val engine: FakeBackupEngine) : DeviceBackupStore {
    val written = mutableMapOf<String, String>()
    var failWith: BackupFailure? = null

    override suspend fun write(artifactPath: String, uri: String) {
        failWith?.let { throw BackupException(it) }
        check(artifactPath in engine.files) { "Writing a file that does not exist" }
        written[uri] = artifactPath
    }

    override suspend fun read(uri: String): String {
        failWith?.let { throw BackupException(it) }
        val copy = "copy-of-$uri"
        engine.files += copy
        written[uri]?.let { original -> engine.passphrases[original]?.let { engine.passphrases[copy] = it } }
        return copy
    }
}

class FakeDriveBackupStore(private val engine: FakeBackupEngine) : DriveBackupStore {
    val uploaded = mutableMapOf<String, BackupArtifact>()
    var failWith: BackupFailure? = null

    override suspend fun upload(artifact: BackupArtifact): String {
        failWith?.let { throw BackupException(it) }
        val id = "drive-${uploaded.size + 1}"
        uploaded[id] = artifact
        return id
    }

    override suspend fun list(): List<RemoteBackup> {
        failWith?.let { throw BackupException(it) }
        return uploaded.map { (id, a) -> RemoteBackup(id, a.fileName, a.header.createdAt, a.sizeBytes, a.header.trigger, "1.0", "Test") }
    }

    override suspend fun download(id: String): String {
        failWith?.let { throw BackupException(it) }
        if (id !in uploaded) throw BackupException(BackupFailure.NOT_A_BACKUP)
        val path = "download-$id"
        engine.files += path
        return path
    }
}
