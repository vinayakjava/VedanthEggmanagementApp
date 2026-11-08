package com.eggbusinessmanager.domain.backup

import com.eggbusinessmanager.domain.backup.AutoBackupOutcome.SkipReason
import com.eggbusinessmanager.testing.FakeAutoBackupScheduler
import com.eggbusinessmanager.testing.FakeAutoBackupSettings
import com.eggbusinessmanager.testing.FakeBackupEngine
import com.eggbusinessmanager.testing.FakeBackupHistory
import com.eggbusinessmanager.testing.FakeBackupNotifier
import com.eggbusinessmanager.testing.FakeDriveAuthorizer
import com.eggbusinessmanager.testing.FakeDriveBackupStore
import com.eggbusinessmanager.testing.FixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoBackupTest {

    private val india = ZoneId.of("Asia/Kolkata")
    // Sunday 8 Nov 2026, 15:30 in India.
    private val clock = FixedClock(ZonedDateTime.of(2026, 11, 8, 15, 30, 0, 0, india).toInstant(), india)
    private fun local(y: Int, m: Int, d: Int, h: Int, min: Int = 0, zone: ZoneId = india) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant()

    private val on = AutoBackupSettings(enabled = true, preferredTime = LocalTime.of(2, 0), keepLast = 5)
    private val settings = FakeAutoBackupSettings(on)
    private val engine = FakeBackupEngine().apply { passphrase = "egg-secret-1" }
    private val history = FakeBackupHistory()
    private val drive = FakeDriveBackupStore(engine)
    private val notifier = FakeBackupNotifier()
    private val lock = BackupLock()
    private val run = RunAutomaticBackupUseCase(settings, engine, history, drive, notifier, lock, clock)

    // ---- Schedule ----

    @Test
    fun `daily slots are at the preferred time, today if still ahead, otherwise tomorrow`() {
        assertThat(BackupSchedule.next(on, clock.now(), india)).isEqualTo(local(2026, 11, 9, 2))
        val evening = on.copy(preferredTime = LocalTime.of(21, 15))
        assertThat(BackupSchedule.next(evening, clock.now(), india)).isEqualTo(local(2026, 11, 8, 21, 15))
        assertThat(BackupSchedule.previous(on, clock.now(), india)).isEqualTo(local(2026, 11, 8, 2))
        // Exactly at a slot: that slot is the current period, the next one is a day later.
        val atSlot = local(2026, 11, 9, 2)
        assertThat(BackupSchedule.previous(on, atSlot, india)).isEqualTo(atSlot)
        assertThat(BackupSchedule.next(on, atSlot, india)).isEqualTo(local(2026, 11, 10, 2))
    }

    @Test
    fun `weekly slots fall on the chosen weekday`() {
        val weekly = on.copy(frequency = BackupFrequency.WEEKLY, dayOfWeek = DayOfWeek.WEDNESDAY)
        assertThat(BackupSchedule.next(weekly, clock.now(), india)).isEqualTo(local(2026, 11, 11, 2))
        assertThat(BackupSchedule.previous(weekly, clock.now(), india)).isEqualTo(local(2026, 11, 4, 2))
        val sunday = weekly.copy(dayOfWeek = DayOfWeek.SUNDAY)
        assertThat(BackupSchedule.next(sunday, clock.now(), india)).isEqualTo(local(2026, 11, 15, 2))
        assertThat(BackupSchedule.previous(sunday, clock.now(), india)).isEqualTo(local(2026, 11, 8, 2))
    }

    @Test
    fun `monthly slots fall on the chosen day, across the year end`() {
        val monthly = on.copy(frequency = BackupFrequency.MONTHLY, dayOfMonth = 28)
        assertThat(BackupSchedule.next(monthly, clock.now(), india)).isEqualTo(local(2026, 11, 28, 2))
        assertThat(BackupSchedule.previous(monthly, clock.now(), india)).isEqualTo(local(2026, 10, 28, 2))
        val first = monthly.copy(dayOfMonth = 1)
        assertThat(BackupSchedule.next(first, local(2026, 12, 15, 9), india)).isEqualTo(local(2027, 1, 1, 2))
        assertThat(BackupSchedule.next(monthly, local(2027, 2, 28, 3), india)).isEqualTo(local(2027, 3, 28, 2))
    }

    @Test
    fun `slots keep the wall-clock time across daylight saving changes`() {
        val london = ZoneId.of("Europe/London")
        // Clocks go back on 25 Oct 2026: the slot stays at 02:00 local time either side.
        val before = local(2026, 10, 24, 12, zone = london)
        assertThat(BackupSchedule.next(on, before, london)).isEqualTo(local(2026, 10, 25, 2, zone = london))
        assertThat(BackupSchedule.next(on, local(2026, 10, 25, 12, zone = london), london)).isEqualTo(local(2026, 10, 26, 2, zone = london))
    }

    @Test
    fun `settings reject impossible days and retention values`() {
        assertThat(runCatching { on.copy(dayOfMonth = 31) }.isFailure).isTrue()
        assertThat(runCatching { on.copy(keepLast = 7) }.isFailure).isTrue()
    }

    // ---- Automatic run ----

    @Test
    fun `a successful run uploads, records an automatic backup and clears old failure notices`() = runTest {
        val outcome = run(attempt = 0)
        assertThat(outcome).isInstanceOf(AutoBackupOutcome.Done::class.java)
        assertThat(drive.uploaded.values.single().header.trigger).isEqualTo(BackupTrigger.AUTOMATIC)
        val record = history.records.value.single()
        assertThat(record.trigger).isEqualTo(BackupTrigger.AUTOMATIC)
        assertThat(record.status).isEqualTo(BackupStatus.SUCCESS)
        assertThat(engine.files).isEmpty()
        assertThat(notifier.cleared).isEqualTo(1)
    }

    @Test
    fun `a second run in the same period is skipped, the next period runs again`() = runTest {
        run(attempt = 0)
        clock.advanceBy(3 * 3600) // same day: still the 8 Nov period
        assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.Skipped(SkipReason.ALREADY_DONE))
        assertThat(drive.uploaded).hasSize(1)
        clock.instant = local(2026, 11, 9, 2, 5) // the 9 Nov slot has passed
        assertThat(run(attempt = 0)).isInstanceOf(AutoBackupOutcome.Done::class.java)
        assertThat(drive.uploaded).hasSize(2)
    }

    @Test
    fun `a manual backup in this period does not stop the automatic one`() = runTest {
        val manualId = history.start(BackupOperation.BACKUP, BackupDestination.DEVICE, BackupTrigger.MANUAL, clock.now())
        history.succeed(manualId, clock.now(), 1, "m", null)
        assertThat(run(attempt = 0)).isInstanceOf(AutoBackupOutcome.Done::class.java)
    }

    @Test
    fun `switched off means nothing runs`() = runTest {
        settings.state.value = on.copy(enabled = false)
        assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.Skipped(SkipReason.DISABLED))
        assertThat(drive.uploaded).isEmpty()
    }

    @Test
    fun `no network is retried quietly, then reported once after the last attempt`() = runTest {
        drive.failWith = BackupFailure.NO_NETWORK
        repeat(RunAutomaticBackupUseCase.MAX_ATTEMPTS - 1) { attempt ->
            assertThat(run(attempt)).isEqualTo(AutoBackupOutcome.RetryLater(BackupFailure.NO_NETWORK))
        }
        assertThat(history.records.value).isEmpty() // retries are not recorded
        assertThat(notifier.shown).isEmpty()
        assertThat(engine.files).isEmpty() // temporary snapshot removed every time

        assertThat(run(RunAutomaticBackupUseCase.MAX_ATTEMPTS - 1)).isEqualTo(AutoBackupOutcome.Failed(BackupFailure.NO_NETWORK))
        assertThat(history.records.value.single().failure).isEqualTo(BackupFailure.NO_NETWORK)
        assertThat(notifier.shown).containsExactly(BackupFailure.NO_NETWORK)
    }

    @Test
    fun `problems that need the owner are reported at once without retrying`() = runTest {
        drive.failWith = BackupFailure.AUTH_REQUIRED
        assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.Failed(BackupFailure.AUTH_REQUIRED))
        drive.failWith = BackupFailure.DRIVE_FULL
        assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.Failed(BackupFailure.DRIVE_FULL))
        engine.passphrase = null
        assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.Failed(BackupFailure.NO_PASSPHRASE))
        assertThat(notifier.shown)
            .containsExactly(BackupFailure.AUTH_REQUIRED, BackupFailure.DRIVE_FULL, BackupFailure.NO_PASSPHRASE).inOrder()
    }

    @Test
    fun `it never runs at the same time as another backup or a restore`() = runTest {
        lock.mutex.lock()
        assertThat(run(attempt = 0)).isEqualTo(AutoBackupOutcome.RetryLater(BackupFailure.BUSY))
        lock.mutex.unlock()
        assertThat(run(attempt = 0)).isInstanceOf(AutoBackupOutcome.Done::class.java)
        assertThat(lock.mutex.isLocked).isFalse()
    }

    @Test
    fun `retention keeps the newest automatic backups and never deletes manual ones`() = runTest {
        val manual = RunBackupUseCase(engine, history, com.eggbusinessmanager.testing.FakeDeviceBackupStore(engine), drive, lock, clock)
        manual(BackupDestination.GOOGLE_DRIVE)
        repeat(7) { day ->
            clock.instant = local(2026, 11, 9 + day, 3)
            assertThat(run(attempt = 0)).isInstanceOf(AutoBackupOutcome.Done::class.java)
        }
        val left = drive.uploaded.values.toList()
        assertThat(left.count { it.header.trigger == BackupTrigger.AUTOMATIC }).isEqualTo(5)
        assertThat(left.count { it.header.trigger == BackupTrigger.MANUAL }).isEqualTo(1)
        // The oldest automatic ones went; the newest five stayed.
        assertThat(left.filter { it.header.trigger == BackupTrigger.AUTOMATIC }.map { it.header.createdAt }.minOrNull())
            .isEqualTo(local(2026, 11, 11, 3))
    }

    @Test
    fun `a failing cleanup does not turn a successful backup into a failure`() = runTest {
        val flaky = object : DriveBackupStore by drive {
            override suspend fun list(): List<RemoteBackup> = throw BackupException(BackupFailure.NO_NETWORK)
        }
        val outcome = RunAutomaticBackupUseCase(settings, engine, history, flaky, notifier, lock, clock)(attempt = 0)
        assertThat(outcome).isInstanceOf(AutoBackupOutcome.Done::class.java)
    }

    // ---- Settings, scheduling and status ----

    @Test
    fun `switching on needs a passphrase and Google Drive, then schedules the next slot`() = runTest {
        val scheduler = FakeAutoBackupScheduler()
        val authorizer = FakeDriveAuthorizer(connected = false)
        val repo = FakeAutoBackupSettings()
        val update = UpdateAutoBackupSettingsUseCase(repo, scheduler, engine, authorizer, clock)

        assertThat(update(on)).isEqualTo(BackupFailure.AUTH_REQUIRED)
        assertThat(repo.state.value.enabled).isFalse()
        authorizer.connected.value = true
        engine.passphrase = null
        assertThat(update(on)).isEqualTo(BackupFailure.NO_PASSPHRASE)
        engine.passphrase = "egg-secret-1"

        assertThat(update(on)).isNull()
        assertThat(scheduler.scheduledAt).isEqualTo(local(2026, 11, 9, 2))
        assertThat(update(on.copy(frequency = BackupFrequency.WEEKLY, dayOfWeek = DayOfWeek.FRIDAY))).isNull()
        assertThat(scheduler.scheduledAt).isEqualTo(local(2026, 11, 13, 2)) // replaced, not added
        assertThat(update(on.copy(enabled = false))).isNull()
        assertThat(scheduler.scheduledAt).isNull()
        assertThat(scheduler.calls).containsExactly("schedule", "schedule", "cancel").inOrder()
    }

    @Test
    fun `after a run the next slot replaces it, at app start an existing one is kept`() = runTest {
        val scheduler = FakeAutoBackupScheduler()
        val next = ScheduleNextAutoBackupUseCase(settings, scheduler, clock)
        next(replace = false)
        assertThat(scheduler.calls).containsExactly("ensure")
        next(replace = true)
        assertThat(scheduler.scheduledAt).isEqualTo(local(2026, 11, 9, 2))
        settings.state.value = on.copy(enabled = false)
        next(replace = true)
        assertThat(scheduler.calls.last()).isEqualTo("cancel")
    }

    @Test
    fun `status shows the last success, the last automatic failure and the next slot`() = runTest {
        val status = ObserveAutoBackupStatusUseCase(settings, history, clock)
        assertThat(status().first().nextRun).isEqualTo(local(2026, 11, 9, 2))
        run(attempt = 0)
        clock.instant = local(2026, 11, 9, 2, 30)
        drive.failWith = BackupFailure.DRIVE_FULL
        run(attempt = 0)
        val s = status().first()
        assertThat(s.lastSuccess?.trigger).isEqualTo(BackupTrigger.AUTOMATIC)
        assertThat(s.lastAutomaticFailure?.failure).isEqualTo(BackupFailure.DRIVE_FULL)
        settings.state.value = on.copy(enabled = false)
        assertThat(status().first().nextRun).isNull()
    }
}
