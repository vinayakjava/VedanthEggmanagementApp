package com.eggbusinessmanager.data.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.RoomTransactionRunner
import com.eggbusinessmanager.data.repository.RoomAuditRepository
import com.eggbusinessmanager.data.repository.RoomCustomerRepository
import com.eggbusinessmanager.data.repository.RoomLedgerRepository
import com.eggbusinessmanager.data.security.DatabaseKeyProvider
import com.eggbusinessmanager.data.security.KeystoreKeyWrapper
import com.eggbusinessmanager.data.settings.DataStoreAutoBackupSettings
import com.eggbusinessmanager.domain.backup.AutoBackupOutcome
import com.eggbusinessmanager.domain.backup.AutoBackupSettings
import com.eggbusinessmanager.domain.backup.BackupArtifact
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupFrequency
import com.eggbusinessmanager.domain.backup.BackupLock
import com.eggbusinessmanager.domain.backup.BackupNotifier
import com.eggbusinessmanager.domain.backup.BackupStatus
import com.eggbusinessmanager.domain.backup.BackupTrigger
import com.eggbusinessmanager.domain.backup.DriveBackupStore
import com.eggbusinessmanager.domain.backup.RemoteBackup
import com.eggbusinessmanager.domain.backup.RunAutomaticBackupUseCase
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Provider

/**
 * Automatic backups on the real encrypted database (Phase 10): a scheduled run produces a real encrypted
 * backup, retention removes only old automatic ones, and the automatic backup restores the data exactly.
 * Google Drive is replaced by a folder so the test needs no account; the Drive client is tested separately.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticBackupRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyProvider = DatabaseKeyProvider(context, KeystoreKeyWrapper())
    private lateinit var engine: SnapshotBackupEngine
    private lateinit var db: EggBusinessDatabase
    private lateinit var history: RoomBackupHistory
    private val driveDir get() = File(context.cacheDir, "fake-drive")
    private val passphrase = "Ramesh-backup-26"
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = object : Clock {
        var instant: Instant = LocalDate.of(2026, 11, 8).atTime(3, 0).atZone(zone).toInstant()
        override fun now(): Instant = instant
        override fun today(): LocalDate = instant.atZone(zone).toLocalDate()
        override fun zone(): ZoneId = zone
    }

    /** Google Drive stand-in: uploaded backups are copied into a folder. */
    private val drive = object : DriveBackupStore {
        val files = linkedMapOf<String, Pair<File, BackupArtifact>>()
        override suspend fun upload(artifact: BackupArtifact): String {
            val id = "f${files.size + 1}-${artifact.header.createdAt.toEpochMilli()}"
            files[id] = File(artifact.path).copyTo(File(driveDir.apply { mkdirs() }, id), overwrite = true) to artifact
            return id
        }
        override suspend fun list() = files.map { (id, v) ->
            val (file, artifact) = v
            RemoteBackup(id, artifact.fileName, artifact.header.createdAt, file.length(), artifact.header.trigger, null, null)
        }
        override suspend fun download(id: String): String =
            files.getValue(id).first.copyTo(File(context.cacheDir, "restore/$id"), overwrite = true).path
        override suspend fun delete(id: String) {
            files.remove(id)?.first?.delete()
        }
    }
    private val notified = mutableListOf<BackupFailure>()
    private val notifier = object : BackupNotifier {
        override fun notifyFailure(failure: BackupFailure) {
            notified += failure
        }
        override fun clear() = Unit
    }

    @Before
    fun fresh() {
        wipe()
        val keys = BackupKeyStore(context, KeystoreKeyWrapper())
        engine = SnapshotBackupEngine(context, keyProvider, keys, Dispatchers.IO).apply { kdfIterations = 1_000 }
        open()
        runBlocking { engine.setPassphrase(passphrase) }
    }

    @After
    fun cleanUp() {
        if (::db.isInitialized) db.close()
        wipe()
    }

    private fun wipe() {
        RestoreCoordinator.dbFiles(context.getDatabasePath(EggBusinessDatabase.FILE_NAME)).forEach { it.delete() }
        listOf("keys", "backup", "restore", "safety-backups").forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        listOf("backup", "restore", "fake-drive", "auto-settings").forEach { File(context.cacheDir, it).deleteRecursively() }
    }

    private fun open() {
        DatabaseFactory.loadNativeLibrary()
        db = DatabaseFactory.build(context, keyProvider.getOrCreateKey(), EggBusinessDatabase.FILE_NAME)
        db.openHelper.writableDatabase
        history = RoomBackupHistory(Provider { db }, Dispatchers.IO)
    }

    private fun settingsStore() = DataStoreAutoBackupSettings(
        PreferenceDataStoreFactory.create { File(context.cacheDir, "auto-settings/${System.nanoTime()}.preferences_pb") },
    )

    private suspend fun addCustomer(name: String) {
        val p = Provider { db }
        val customers = RoomCustomerRepository(p, Dispatchers.IO)
        val tx = RoomTransactionRunner(p)
        val ledger = RoomLedgerRepository(p, Dispatchers.IO)
        val audit = RoomAuditRepository(p, Dispatchers.IO)
        val create = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
        create(CustomerDraft(name = name))
    }

    private suspend fun names() = RoomCustomerRepository(Provider { db }, Dispatchers.IO)
        .search(CustomerQuery("", CustomerFilter.ACTIVE, CustomerSort.NAME)).first().map { it.customer.name }

    @Test
    fun settingsAreStoredOnThePhoneAndReadBackExactly() = runBlocking<Unit> {
        val store = settingsStore()
        assertThat(store.observe().first()).isEqualTo(AutoBackupSettings())
        val custom = AutoBackupSettings(
            enabled = true, frequency = BackupFrequency.WEEKLY, preferredTime = LocalTime.of(23, 45), dayOfWeek = DayOfWeek.FRIDAY,
            dayOfMonth = 15, keepLast = 20, wifiOnly = true, onlyWhileCharging = true,
        )
        store.save(custom)
        assertThat(store.observe().first()).isEqualTo(custom)
    }

    @Test
    fun anAutomaticBackupRestoresTheDataAndOldAutomaticBackupsAreRemoved() = runBlocking<Unit> {
        val store = settingsStore().apply { save(AutoBackupSettings(enabled = true, keepLast = 5)) }
        val run = RunAutomaticBackupUseCase(store, engine, history, drive, notifier, BackupLock(), clock)
        addCustomer("Ramesh Traders")

        // Seven nightly runs: each is a new period, so each makes a backup; only the newest five are kept.
        repeat(7) { night ->
            clock.instant = LocalDate.of(2026, 11, 8 + night).atTime(3, 0).atZone(zone).toInstant()
            assertThat(run(attempt = 0)).isInstanceOf(AutoBackupOutcome.Done::class.java)
            // A second run in the same night is a duplicate and is skipped.
            assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.Skipped(AutoBackupOutcome.SkipReason.ALREADY_DONE))
        }
        assertThat(drive.files).hasSize(5)
        assertThat(driveDir.listFiles()!!.size).isEqualTo(5)
        val records = history.observe().first()
        assertThat(records.filter { it.trigger == BackupTrigger.AUTOMATIC && it.status == BackupStatus.SUCCESS }).hasSize(7)
        assertThat(notified).isEmpty()

        // Data changes after the last automatic backup; restoring that backup brings back its contents.
        addCustomer("Added Later")
        val newest = drive.files.keys.last()
        val path = drive.download(newest)
        assertThat(engine.stage(path, passphrase).customers).isEqualTo(1)
        engine.confirmStagedRestore()
        db.close()
        assertThat(engine.coordinator.resume(keyProvider.getOrCreateKey())).isEqualTo(RestoreCoordinator.Outcome.RESTORED)
        open()
        assertThat(names()).containsExactly("Ramesh Traders")
        // The phone's backup history survives the restore.
        assertThat(history.observe().first().count { it.trigger == BackupTrigger.AUTOMATIC }).isEqualTo(7)
    }

    @Test
    fun withoutAPassphraseTheRunFailsOnceAndTellsTheOwner() = runBlocking<Unit> {
        File(context.noBackupFilesDir, "backup").deleteRecursively()
        val store = settingsStore().apply { save(AutoBackupSettings(enabled = true)) }
        val run = RunAutomaticBackupUseCase(store, engine, history, drive, notifier, BackupLock(), clock)
        assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.Failed(BackupFailure.NO_PASSPHRASE))
        assertThat(notified).containsExactly(BackupFailure.NO_PASSPHRASE)
        assertThat(history.observe().first().single().failure).isEqualTo(BackupFailure.NO_PASSPHRASE)
        assertThat(drive.files).isEmpty()
    }
}
