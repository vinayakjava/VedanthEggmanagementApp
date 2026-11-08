package com.eggbusinessmanager.domain.backup

import com.eggbusinessmanager.domain.backup.BackupPassphraseRules.Problem
import com.eggbusinessmanager.domain.backup.StageRestoreUseCase.Source
import com.eggbusinessmanager.domain.usecase.auth.SignUpUseCase
import com.eggbusinessmanager.testing.FakeAccountRepository
import com.eggbusinessmanager.testing.FakeBackupEngine
import com.eggbusinessmanager.testing.FakeBackupHistory
import com.eggbusinessmanager.testing.FakeDeviceBackupStore
import com.eggbusinessmanager.testing.FakeDriveBackupStore
import com.eggbusinessmanager.testing.FakeSecretHasher
import com.eggbusinessmanager.testing.FakeSessionManager
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.RecordingLogger
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class BackupUseCasesTest {

    private val clock = FixedClock()
    private val engine = FakeBackupEngine().apply { passphrase = "egg-secret-1" }
    private val history = FakeBackupHistory()
    private val device = FakeDeviceBackupStore(engine)
    private val drive = FakeDriveBackupStore(engine)
    private val runBackup = RunBackupUseCase(engine, history, device, drive, BackupLock(), clock)
    private val stage = StageRestoreUseCase(engine, device, drive)
    private val confirm = ConfirmRestoreUseCase(engine, history, BackupLock(), clock)
    private val status = ObserveBackupStatusUseCase(history)

    // ---- Backup ----

    @Test
    fun `backup to the phone writes the encrypted file to the picked location, records it and removes the temp file`() = runTest {
        val result = runBackup(BackupDestination.DEVICE, "content://downloads/1") as BackupResult.Ok
        assertThat(device.written.keys).containsExactly("content://downloads/1")
        assertThat(engine.files).isEmpty() // temporary snapshot deleted
        assertThat(result.value.status).isEqualTo(BackupStatus.SUCCESS)
        assertThat(result.value.destination).isEqualTo(BackupDestination.DEVICE)
        assertThat(result.value.fileName).endsWith(".ebmbackup")
        assertThat(result.value.sizeBytes).isEqualTo(100)
    }

    @Test
    fun `backup to Google Drive uploads and keeps the Drive file id`() = runTest {
        val result = runBackup(BackupDestination.GOOGLE_DRIVE) as BackupResult.Ok
        assertThat(result.value.driveFileId).isEqualTo("drive-1")
        assertThat(drive.uploaded).hasSize(1)
        assertThat(engine.files).isEmpty()
    }

    @Test
    fun `failures are recorded with their reason and the temp file is still removed`() = runTest {
        drive.failWith = BackupFailure.AUTH_REQUIRED
        assertThat(runBackup(BackupDestination.GOOGLE_DRIVE)).isEqualTo(BackupResult.Failed(BackupFailure.AUTH_REQUIRED))
        assertThat(engine.files).isEmpty()
        val record = history.records.value.single()
        assertThat(record.status).isEqualTo(BackupStatus.FAILED)
        assertThat(record.failure).isEqualTo(BackupFailure.AUTH_REQUIRED)

        drive.failWith = BackupFailure.NO_NETWORK
        assertThat(runBackup(BackupDestination.GOOGLE_DRIVE)).isEqualTo(BackupResult.Failed(BackupFailure.NO_NETWORK))
        engine.failCreateWith = BackupFailure.LOW_STORAGE
        assertThat(runBackup(BackupDestination.DEVICE, "content://x")).isEqualTo(BackupResult.Failed(BackupFailure.LOW_STORAGE))
    }

    @Test
    fun `no backup without a passphrase or without a picked location`() = runTest {
        assertThat(runBackup(BackupDestination.DEVICE, null)).isEqualTo(BackupResult.Failed(BackupFailure.LOCATION_UNAVAILABLE))
        engine.passphrase = null
        assertThat(runBackup(BackupDestination.DEVICE, "content://x")).isEqualTo(BackupResult.Failed(BackupFailure.NO_PASSPHRASE))
        assertThat(history.records.value).hasSize(1) // the missing-location attempt only
    }

    @Test
    fun `last successful backup ignores failures, restores and safety copies`() = runTest {
        runBackup(BackupDestination.DEVICE, "content://a")
        clock.advanceBy(60)
        runBackup(BackupDestination.GOOGLE_DRIVE)
        clock.advanceBy(60)
        drive.failWith = BackupFailure.DRIVE_FULL
        runBackup(BackupDestination.GOOGLE_DRIVE)
        val s = status().first()
        assertThat(s.lastSuccess?.driveFileId).isEqualTo("drive-1")
        assertThat(s.history.map { it.status }).containsExactly(BackupStatus.FAILED, BackupStatus.SUCCESS, BackupStatus.SUCCESS).inOrder()
    }

    // ---- Passphrase ----

    @Test
    fun `passphrase rules`() = runTest {
        val accounts = FakeAccountRepository()
        val hasher = FakeSecretHasher()
        SignUpUseCase(accounts, hasher, clock, FakeSessionManager(), RecordingLogger())(SignupForms.valid)
        val set = SetBackupPassphraseUseCase(engine, accounts, hasher)

        assertThat(set("short", "short")).isEqualTo(Problem.TOO_SHORT)
        assertThat(set("x".repeat(65), "x".repeat(65))).isEqualTo(Problem.TOO_LONG)
        assertThat(set("long enough", "long enougj")).isEqualTo(Problem.MISMATCH)
        assertThat(set(SignupForms.PASSWORD, SignupForms.PASSWORD)).isEqualTo(Problem.SAME_AS_PASSWORD)
        assertThat(set("Ramesh-backup-26", "Ramesh-backup-26")).isNull()
        assertThat(engine.passphrase).isEqualTo("Ramesh-backup-26")
    }

    // ---- Restore ----

    @Test
    fun `staging validates the backup and changes nothing, and temporary copies are removed`() = runTest {
        runBackup(BackupDestination.GOOGLE_DRIVE)
        val preview = stage(Source.Drive("drive-1"), "egg-secret-1") as BackupResult.Ok
        assertThat(preview.value.backup.customers).isEqualTo(4)
        assertThat(preview.value.current.customers).isEqualTo(2)
        assertThat(engine.confirmed).isFalse()
        assertThat(engine.files).isEmpty()
        assertThat(stage(Source.Drive("missing"), "egg-secret-1")).isEqualTo(BackupResult.Failed(BackupFailure.NOT_A_BACKUP))
    }

    @Test
    fun `a wrong passphrase is reported and nothing is staged`() = runTest {
        engine.files += "private"
        engine.passphrases["private"] = "right-passphrase"
        assertThat(stage(Source.Safety("private"), "wrong-passphrase")).isEqualTo(BackupResult.Failed(BackupFailure.WRONG_PASSPHRASE))
        assertThat(engine.staged).isNull()
        assertThat(engine.files).contains("private") // safety backups are never deleted by staging
    }

    @Test
    fun `confirming makes a safety backup first, records it, then marks the restore`() = runTest {
        engine.files += "backup"
        stage(Source.Safety("backup"), "egg-secret-1")
        assertThat(confirm()).isEqualTo(BackupResult.Ok(Unit))
        assertThat(engine.events.takeLast(2)).containsExactly("create:SAFETY", "confirm").inOrder()
        val records = history.records.value
        assertThat(records.map { it.trigger to it.operation })
            .containsExactly(BackupTrigger.SAFETY to BackupOperation.BACKUP, BackupTrigger.MANUAL to BackupOperation.RESTORE).inOrder()
        assertThat(records.first().status).isEqualTo(BackupStatus.SUCCESS)
        assertThat(records.last().status).isEqualTo(BackupStatus.IN_PROGRESS) // finished by the next start
    }

    @Test
    fun `if the safety backup cannot be made the restore does not happen`() = runTest {
        engine.files += "backup"
        stage(Source.Safety("backup"), "egg-secret-1")
        engine.failCreateWith = BackupFailure.LOW_STORAGE
        assertThat(confirm()).isEqualTo(BackupResult.Failed(BackupFailure.LOW_STORAGE))
        assertThat(engine.confirmed).isFalse()
    }

    @Test
    fun `downloading a Drive backup saves it to the picked phone location`() = runTest {
        runBackup(BackupDestination.GOOGLE_DRIVE)
        val download = DownloadDriveBackupUseCase(drive, device, engine)
        assertThat(download("drive-1", "content://downloads/backup")).isEqualTo(BackupResult.Ok(Unit))
        assertThat(device.written).containsKey("content://downloads/backup")
        assertThat(engine.files).isEmpty()
        assertThat((ListDriveBackupsUseCase(drive)() as BackupResult.Ok).value.map { it.id }).containsExactly("drive-1")
    }

    @Test
    fun `backup file names follow the contract`() {
        val at = ZonedDateTime.of(2026, 11, 8, 9, 5, 0, 0, ZoneOffset.UTC)
        assertThat(BackupFileName.of("Patil Egg Centre", at)).isEqualTo("EggBusiness_Patil-Egg-Centre_20261108-0905.ebmbackup")
        assertThat(BackupFileName.of("Café Ândhra & Sons", at)).isEqualTo("EggBusiness_Cafe-Andhra-Sons_20261108-0905.ebmbackup")
        assertThat(BackupFileName.of("पाटील अंडी", at)).isEqualTo("EggBusiness_shop_20261108-0905.ebmbackup")
        assertThat(BackupFileName.of("A".repeat(40), at)).isEqualTo("EggBusiness_${"A".repeat(24)}_20261108-0905.ebmbackup")
    }
}
