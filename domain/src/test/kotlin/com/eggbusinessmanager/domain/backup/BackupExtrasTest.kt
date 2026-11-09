package com.eggbusinessmanager.domain.backup

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.usecase.auth.AuthHarness
import com.eggbusinessmanager.domain.usecase.auth.PasswordResetter
import com.eggbusinessmanager.domain.usecase.auth.SetPasswordAfterRestoreUseCase
import com.eggbusinessmanager.testing.FakeAutoBackupScheduler
import com.eggbusinessmanager.testing.FakeAutoBackupSettings
import com.eggbusinessmanager.testing.FakeBackupEngine
import com.eggbusinessmanager.testing.FakeBackupHistory
import com.eggbusinessmanager.testing.FakeBackupNotifier
import com.eggbusinessmanager.testing.FakeDriveAuthorizer
import com.eggbusinessmanager.testing.FakeDriveBackupStore
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** FR-057 (delete Drive backups), FR-055 (missed automatic backups), US9-9 (new password after a restore). */
class BackupExtrasTest {

    private val india = ZoneId.of("Asia/Kolkata")
    // Sunday 8 Nov 2026, 15:30 in India; daily slots at 02:00.
    private val clock = FixedClock(ZonedDateTime.of(2026, 11, 8, 15, 30, 0, 0, india).toInstant(), india)
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, india).toInstant()

    private val engine = FakeBackupEngine().apply { passphrase = "egg-secret-1" }
    private val history = FakeBackupHistory()
    private val drive = FakeDriveBackupStore(engine)
    private val notifier = FakeBackupNotifier()

    private suspend fun driveBackup(trigger: BackupTrigger = BackupTrigger.MANUAL, day: Int = 8): String {
        val made = engine.createBackup(trigger, at(day, 2))
        val id = drive.upload(made)
        val record = history.start(BackupOperation.BACKUP, BackupDestination.GOOGLE_DRIVE, trigger, at(day, 2))
        history.succeed(record, at(day, 2), made.sizeBytes, made.fileName, id)
        return id
    }

    // ---- FR-057: delete one Drive backup ----

    @Test
    fun `deleting a Drive backup removes the file and keeps the history entry without its link`() = runTest {
        val keep = driveBackup(day = 7)
        val gone = driveBackup(day = 8)

        assertThat(DeleteDriveBackupUseCase(drive, history)(gone)).isEqualTo(BackupResult.Ok(Unit))

        assertThat(drive.list().map { it.id }).containsExactly(keep)
        val records = history.observe().first()
        assertThat(records).hasSize(2)
        assertThat(records.map { it.driveFileId }).containsExactly(keep, null)
        assertThat(records.all { it.status == BackupStatus.SUCCESS }).isTrue()
    }

    @Test
    fun `a failed Drive delete changes nothing`() = runTest {
        val id = driveBackup()
        drive.failWith = BackupFailure.NO_NETWORK
        assertThat(DeleteDriveBackupUseCase(drive, history)(id)).isEqualTo(BackupResult.Failed(BackupFailure.NO_NETWORK))
        assertThat(history.observe().first().single().driveFileId).isEqualTo(id)
    }

    // ---- FR-055: missed automatic backups ----

    private val daily = AutoBackupSettings(enabled = true, preferredTime = LocalTime.of(2, 0), enabledSince = at(1, 12))

    private suspend fun missed(settings: AutoBackupSettings) =
        CheckMissedBackupsUseCase(FakeAutoBackupSettings(settings), history, notifier, clock)()

    @Test
    fun `three daily slots without a successful automatic backup notify the owner`() = runTest {
        driveBackup(BackupTrigger.AUTOMATIC, day = 5) // the 6th, 7th and 8th at 02:00 were missed
        assertThat(missed(daily)).isEqualTo(3)
        assertThat(notifier.missed).containsExactly(3)
    }

    @Test
    fun `two missed slots, a recent success, or switching on recently do not notify`() = runTest {
        driveBackup(BackupTrigger.AUTOMATIC, day = 6) // only the 7th and 8th missed
        assertThat(missed(daily)).isEqualTo(2)
        driveBackup(BackupTrigger.AUTOMATIC, day = 8)
        assertThat(missed(daily)).isEqualTo(0)
        // A manual backup does not count as an automatic one, but switching on yesterday leaves one slot.
        assertThat(missed(daily.copy(enabledSince = at(7, 12)))).isEqualTo(0)
        assertThat(missed(daily.copy(enabled = false))).isEqualTo(0)
        assertThat(notifier.missed).isEmpty()
    }

    @Test
    fun `switching automatic backup on records when, and switching off forgets it`() = runTest {
        val settings = FakeAutoBackupSettings()
        val update = UpdateAutoBackupSettingsUseCase(settings, FakeAutoBackupScheduler(), engine, FakeDriveAuthorizer(), clock)
        update(AutoBackupSettings(enabled = true))
        assertThat(settings.state.value.enabledSince).isEqualTo(clock.now())
        clock.advanceBy(3_600)
        update(settings.state.value.copy(keepLast = 20)) // a later change keeps the original time
        assertThat(settings.state.value.enabledSince).isEqualTo(clock.now().minusSeconds(3_600))
        update(settings.state.value.copy(enabled = false))
        assertThat(settings.state.value.enabledSince).isNull()
    }

    @Test
    fun `retention deletes old automatic backups and their download links`() = runTest {
        val settings = FakeAutoBackupSettings(daily.copy(keepLast = 5))
        val ids = (1..6).map { driveBackup(BackupTrigger.AUTOMATIC, day = it) }
        val run = RunAutomaticBackupUseCase(settings, engine, history, drive, notifier, BackupLock(), clock)
        run(attempt = 0)
        val links = history.observe().first().mapNotNull { it.driveFileId }
        assertThat(links).doesNotContain(ids[0])
        assertThat(links).doesNotContain(ids[1])
        assertThat(drive.list()).hasSize(5)
    }

    // ---- US9-9: new password after a restore ----

    @Test
    fun `after a restore the owner may set a new password once, without the old one`() = runTest {
        val auth = AuthHarness()
        auth.signUp(SignupForms.valid)
        val proof = RestoreProof()
        val setPassword = SetPasswordAfterRestoreUseCase(PasswordResetter(auth.accounts, auth.gate, auth.hasher, auth.session), proof)

        // No restore in this app session: not allowed.
        assertThat(setPassword("newpass99", "newpass99")).isEqualTo(DomainResult.Err(DomainError.RestoreProofMissing))

        engine.outcome = RestoreOutcome.RESTORED
        ConsumeRestoreOutcomeUseCase(engine, proof)()
        assertThat(proof.isGranted).isTrue()
        // A typing mistake keeps the chance open.
        assertThat(setPassword("newpass99", "different1")).isInstanceOf(DomainResult.Err::class.java)
        assertThat(setPassword("newpass99", "newpass99")).isEqualTo(DomainResult.Ok(Unit))
        assertThat(auth.signIn(SignupForms.MOBILE, "newpass99")).isEqualTo(DomainResult.Ok(Unit))
        // Used up.
        assertThat(setPassword("another99", "another99")).isEqualTo(DomainResult.Err(DomainError.RestoreProofMissing))
    }

    @Test
    fun `a rolled-back restore proves nothing`() = runTest {
        val proof = RestoreProof()
        engine.outcome = RestoreOutcome.ROLLED_BACK
        ConsumeRestoreOutcomeUseCase(engine, proof)()
        assertThat(proof.isGranted).isFalse()
    }
}
