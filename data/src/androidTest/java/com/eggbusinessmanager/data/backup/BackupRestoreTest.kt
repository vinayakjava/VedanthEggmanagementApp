package com.eggbusinessmanager.data.backup

import android.content.Context
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
import com.eggbusinessmanager.domain.backup.BackupDestination
import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupOperation
import com.eggbusinessmanager.domain.backup.BackupStatus
import com.eggbusinessmanager.domain.backup.BackupTrigger
import com.eggbusinessmanager.domain.backup.RestoreOutcome
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import javax.inject.Provider

/**
 * Manual backup and restore end to end on the real encrypted database (Phase 9): snapshot → encrypted
 * file → validation in contract order → staging → swap at "next start" with verification, history
 * carry-over and rollback on failure or crash. No step changes the data before the restore is confirmed.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyProvider = DatabaseKeyProvider(context, KeystoreKeyWrapper())
    private val keyStore = BackupKeyStore(context, KeystoreKeyWrapper())
    private lateinit var engine: SnapshotBackupEngine
    private lateinit var db: EggBusinessDatabase
    private lateinit var history: RoomBackupHistory
    private val clock = object : Clock {
        var instant: Instant = Instant.parse("2026-11-08T10:00:00Z")
        override fun now(): Instant = instant.also { instant = instant.plusSeconds(1) }
        override fun today(): LocalDate = LocalDate.of(2026, 11, 8)
    }
    private val passphrase = "Ramesh-backup-26"

    private val dbFile get() = context.getDatabasePath(EggBusinessDatabase.FILE_NAME)

    @Before
    fun freshPhone() {
        wipe()
        engine = SnapshotBackupEngine(context, keyProvider, keyStore, Dispatchers.IO).apply { kdfIterations = 1_000 }
        open()
        runBlocking { engine.setPassphrase(passphrase) }
    }

    @After
    fun cleanUp() {
        if (::db.isInitialized) db.close()
        wipe()
    }

    private fun wipe() {
        RestoreCoordinator.dbFiles(dbFile).forEach { it.delete() }
        listOf("keys", "backup", "restore", "safety-backups").forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        listOf("backup", "restore").forEach { File(context.cacheDir, it).deleteRecursively() }
    }

    private fun open() {
        DatabaseFactory.loadNativeLibrary()
        db = DatabaseFactory.build(context, keyProvider.getOrCreateKey(), EggBusinessDatabase.FILE_NAME)
        db.openHelper.writableDatabase
        history = RoomBackupHistory(Provider { db }, Dispatchers.IO)
    }

    /** What the app does: close the database and run the startup step that swaps a confirmed restore in. */
    private fun restart(failAt: RestoreCoordinator.State? = null): RestoreCoordinator.Outcome? {
        db.close()
        val outcome = try {
            engine.coordinator.resume(keyProvider.getOrCreateKey(), failAt)
        } catch (expected: RestoreCoordinator.SimulatedCrash) {
            null // the "process" died here; the next start must recover
        }
        open()
        return outcome
    }

    private suspend fun addCustomer(name: String) {
        val provider = Provider { db }
        val customers = RoomCustomerRepository(provider, Dispatchers.IO)
        val ledger = RoomLedgerRepository(provider, Dispatchers.IO)
        val audit = RoomAuditRepository(provider, Dispatchers.IO)
        val tx = RoomTransactionRunner(provider)
        val create = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
        val draft = CustomerDraft(name = name, openingAmount = "500", openingDate = LocalDate.of(2026, 11, 1))
        assertThat(create(draft)).isInstanceOf(DomainResult.Ok::class.java)
    }

    private suspend fun customerNames(): List<String> =
        RoomCustomerRepository(Provider { db }, Dispatchers.IO).search(CustomerQuery("", CustomerFilter.ACTIVE, CustomerSort.NAME)).first()
            .map { it.customer.name }

    private fun failure(block: suspend () -> Unit): BackupFailure =
        assertThrows(BackupException::class.java) { runBlocking { block() } }.failure

    @Test
    fun backupThenRestoreBringsBackExactlyTheBackedUpData() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        addCustomer("Sita Stores")
        val backup = engine.createBackup(BackupTrigger.MANUAL, clock.now())
        assertThat(backup.fileName).matches("EggBusiness_shop_\\d{8}-\\d{4}\\.ebmbackup")
        assertThat(File(backup.path).length()).isEqualTo(backup.sizeBytes)
        // The file is encrypted: no customer name appears in it.
        assertThat(String(File(backup.path).readBytes(), Charsets.ISO_8859_1)).doesNotContain("Ramesh")

        // Life goes on after the backup: a new customer and a backup recorded in this phone's history.
        addCustomer("Late Customer")
        val laterId = history.start(BackupOperation.BACKUP, BackupDestination.GOOGLE_DRIVE, BackupTrigger.MANUAL, clock.now())
        history.succeed(laterId, clock.now(), 10, "later.ebmbackup", "drive-9")

        // Staging shows the backup's contents and changes nothing yet.
        val summary = engine.stage(backup.path, passphrase)
        assertThat(summary.customers).isEqualTo(2)
        assertThat(engine.currentSummary().customers).isEqualTo(3)
        assertThat(customerNames()).contains("Late Customer")

        // Confirm, recording the restore as the use case does, then "restart".
        history.start(BackupOperation.RESTORE, BackupDestination.DEVICE, BackupTrigger.MANUAL, clock.now())
        engine.confirmStagedRestore()
        assertThat(restart()).isEqualTo(RestoreCoordinator.Outcome.RESTORED)
        assertThat(engine.consumeRestoreOutcome()).isEqualTo(RestoreOutcome.RESTORED)
        assertThat(engine.consumeRestoreOutcome()).isNull() // shown once

        assertThat(customerNames()).containsExactly("Ramesh Traders", "Sita Stores").inOrder()
        // This phone's history was carried over; the restore is recorded as successful.
        val records = history.observe().first()
        assertThat(records.map { it.driveFileId }).contains("drive-9")
        assertThat(records.single { it.operation == BackupOperation.RESTORE }.status).isEqualTo(BackupStatus.SUCCESS)
    }

    @Test
    fun aWrongPassphraseIsRejectedAndNothingIsStaged() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        val backup = engine.createBackup(BackupTrigger.MANUAL, clock.now())
        assertThat(failure { engine.stage(backup.path, "ramesh-backup-26") }).isEqualTo(BackupFailure.WRONG_PASSPHRASE)
        assertThat(engine.coordinator.stagingFile.exists()).isFalse()
    }

    @Test
    fun damagedTruncatedAndForeignFilesAreRejected() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        val backup = File(engine.createBackup(BackupTrigger.MANUAL, clock.now()).path)

        val damaged = backup.copyTo(File(context.cacheDir, "backup/damaged.ebmbackup"), overwrite = true)
        RandomAccessFile(damaged, "rw").use { raf ->
            raf.seek(damaged.length() - 100)
            raf.write(raf.read() xor 0xFF)
        }
        assertThat(failure { engine.stage(damaged.path, passphrase) }).isEqualTo(BackupFailure.CORRUPT)

        val shortened = backup.readBytes().copyOf((backup.length() - 4096).toInt())
        val truncated = File(context.cacheDir, "backup/truncated.ebmbackup").apply { writeBytes(shortened) }
        assertThat(failure { engine.stage(truncated.path, passphrase) }).isEqualTo(BackupFailure.CORRUPT)

        val pdf = File(context.cacheDir, "backup/report.pdf").apply { writeText("%PDF-1.7 not a backup") }
        assertThat(failure { engine.stage(pdf.path, passphrase) }).isEqualTo(BackupFailure.NOT_A_BACKUP)
        assertThat(engine.coordinator.stagingFile.exists()).isFalse()
    }

    @Test
    fun aBackupFromANewerAppVersionAsksForAnUpdate() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        db.openHelper.writableDatabase.execSQL("PRAGMA user_version = ${EggBusinessDatabase.VERSION + 1}")
        val backup = engine.createBackup(BackupTrigger.MANUAL, clock.now())
        db.openHelper.writableDatabase.execSQL("PRAGMA user_version = ${EggBusinessDatabase.VERSION}")
        assertThat(backup.header.schemaVersion).isEqualTo(EggBusinessDatabase.VERSION + 1)
        assertThat(failure { engine.stage(backup.path, passphrase) }).isEqualTo(BackupFailure.NEWER_VERSION)
    }

    @Test
    fun aCrashWhileSwappingOrVerifyingRollsBackToTheOriginalData() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        val backup = engine.createBackup(BackupTrigger.MANUAL, clock.now())
        addCustomer("Kept Customer")

        for (crashAt in listOf(RestoreCoordinator.State.SWAPPING, RestoreCoordinator.State.VERIFYING)) {
            history.start(BackupOperation.RESTORE, BackupDestination.DEVICE, BackupTrigger.MANUAL, clock.now())
            engine.stage(backup.path, passphrase)
            engine.confirmStagedRestore()
            assertThat(restart(failAt = crashAt)).isNull() // died mid-way
            assertThat(restart()).isEqualTo(RestoreCoordinator.Outcome.ROLLED_BACK) // next start recovers
            assertThat(customerNames()).containsExactly("Kept Customer", "Ramesh Traders").inOrder()
            assertThat(engine.consumeRestoreOutcome()).isEqualTo(RestoreOutcome.ROLLED_BACK)
        }
        val restores = history.observe().first().filter { it.operation == BackupOperation.RESTORE }
        assertThat(restores.map { it.failure }).containsExactly(BackupFailure.INTERRUPTED, BackupFailure.INTERRUPTED)
    }

    @Test
    fun aStagedDatabaseThatFailsVerificationIsRolledBack() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        engine.coordinator.stagingFile.apply { parentFile?.mkdirs() }.writeBytes(ByteArray(8192) { 7 })
        engine.confirmStagedRestore()
        assertThat(restart()).isEqualTo(RestoreCoordinator.Outcome.ROLLED_BACK)
        assertThat(customerNames()).containsExactly("Ramesh Traders")
    }

    @Test
    fun discardingAStagedRestoreLeavesEverythingAsItWas() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        val backup = engine.createBackup(BackupTrigger.MANUAL, clock.now())
        engine.stage(backup.path, passphrase)
        engine.discardStaged()
        assertThat(restart()).isNull()
        assertThat(customerNames()).containsExactly("Ramesh Traders")
    }

    @Test
    fun safetyBackupsStayOnThePhoneNewestFirstAndOnlyTheLastThreeAreKept() = runBlocking<Unit> {
        addCustomer("Ramesh Traders")
        repeat(4) {
            engine.createBackup(BackupTrigger.SAFETY, clock.now())
            Thread.sleep(20) // distinct modification times
        }
        val kept = engine.safetyBackups()
        assertThat(kept).hasSize(3)
        assertThat(kept.map { it.header.trigger }.toSet()).containsExactly(BackupTrigger.SAFETY)
        assertThat(kept.map { it.header.createdAt }).isInOrder(Comparator.reverseOrder<Instant>())
        // A safety backup is restorable with the current passphrase.
        assertThat(engine.stage(kept.first().path, passphrase).customers).isEqualTo(1)
    }

    @Test
    fun withoutAPassphraseNoBackupIsMade() = runBlocking<Unit> {
        File(context.noBackupFilesDir, "backup").deleteRecursively()
        assertThat(engine.hasPassphrase()).isFalse()
        assertThat(failure { engine.createBackup(BackupTrigger.MANUAL, clock.now()) }).isEqualTo(BackupFailure.NO_PASSPHRASE)
    }
}
