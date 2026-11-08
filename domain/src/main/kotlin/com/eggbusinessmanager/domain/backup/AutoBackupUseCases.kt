package com.eggbusinessmanager.domain.backup

import com.eggbusinessmanager.domain.repository.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject

/** What one automatic run did; the worker maps it to WorkManager's success or retry. */
sealed interface AutoBackupOutcome {
    data class Done(val sizeBytes: Long) : AutoBackupOutcome

    /** Nothing to do: switched off, or this period already has a backup (duplicate prevention). */
    data class Skipped(val reason: SkipReason) : AutoBackupOutcome

    /** A temporary problem (no network, another backup running); WorkManager retries with backoff. */
    data class RetryLater(val failure: BackupFailure) : AutoBackupOutcome

    /** Gave up: recorded in the history and reported in a notification. */
    data class Failed(val failure: BackupFailure) : AutoBackupOutcome

    enum class SkipReason { DISABLED, ALREADY_DONE }
}

/**
 * One automatic backup run (Phase 10), started by WorkManager at or after the scheduled slot.
 *
 * - Duplicate prevention: a successful automatic backup since the start of the current period means
 *   this run is skipped; the shared [BackupLock] keeps it from overlapping a manual backup or a restore.
 * - Retry: temporary failures are retried by WorkManager with exponential backoff up to [MAX_ATTEMPTS];
 *   only the final outcome is recorded, so the history is not flooded with retries.
 * - Retention: after a success, automatic backups beyond `keepLast` are deleted from Drive. Manual and
 *   safety backups are never deleted.
 */
class RunAutomaticBackupUseCase @Inject constructor(
    private val settings: AutoBackupSettingsRepository,
    private val engine: BackupEngine,
    private val history: BackupHistory,
    private val drive: DriveBackupStore,
    private val notifier: BackupNotifier,
    private val lock: BackupLock,
    private val clock: Clock,
) {
    /** [attempt] is WorkManager's run attempt count, starting at 0. */
    suspend operator fun invoke(attempt: Int): AutoBackupOutcome {
        val current = settings.observe().first()
        return when {
            !current.enabled -> AutoBackupOutcome.Skipped(AutoBackupOutcome.SkipReason.DISABLED)
            !lock.mutex.tryLock() -> AutoBackupOutcome.RetryLater(BackupFailure.BUSY)
            else -> try {
                if (alreadyDone(current)) AutoBackupOutcome.Skipped(AutoBackupOutcome.SkipReason.ALREADY_DONE) else run(current, attempt)
            } finally {
                lock.mutex.unlock()
            }
        }
    }

    private suspend fun alreadyDone(settings: AutoBackupSettings): Boolean {
        val periodStart = BackupSchedule.previous(settings, clock.now(), clock.zone())
        return history.observe().first().any {
            it.trigger == BackupTrigger.AUTOMATIC && it.status == BackupStatus.SUCCESS && !it.startedAt.isBefore(periodStart)
        }
    }

    private suspend fun run(settings: AutoBackupSettings, attempt: Int): AutoBackupOutcome {
        val startedAt = clock.now()
        if (!engine.hasPassphrase()) return finalFailure(BackupFailure.NO_PASSPHRASE, startedAt)
        var artifact: BackupArtifact? = null
        val result = backupResult {
            val made = engine.createBackup(BackupTrigger.AUTOMATIC, clock.now()).also { artifact = it }
            made to drive.upload(made)
        }
        artifact?.let { engine.delete(it.path) }
        return when (result) {
            is BackupResult.Ok -> {
                val (made, driveId) = result.value
                val id = history.start(BackupOperation.BACKUP, settings.destination, BackupTrigger.AUTOMATIC, startedAt)
                history.succeed(id, clock.now(), made.sizeBytes, made.fileName, driveId)
                applyRetention(settings.keepLast)
                notifier.clear()
                AutoBackupOutcome.Done(made.sizeBytes)
            }
            is BackupResult.Failed -> if (result.failure in TRANSIENT && attempt + 1 < MAX_ATTEMPTS) {
                AutoBackupOutcome.RetryLater(result.failure)
            } else {
                finalFailure(result.failure, startedAt)
            }
        }
    }

    private suspend fun finalFailure(failure: BackupFailure, startedAt: Instant): AutoBackupOutcome {
        val id = history.start(BackupOperation.BACKUP, BackupDestination.GOOGLE_DRIVE, BackupTrigger.AUTOMATIC, startedAt)
        history.fail(id, clock.now(), failure)
        notifier.notifyFailure(failure)
        return AutoBackupOutcome.Failed(failure)
    }

    /** Best effort: a failed cleanup never turns a successful backup into a failure. */
    private suspend fun applyRetention(keepLast: Int) {
        val old = (drive.listOrNull() ?: return)
            .filter { it.trigger == BackupTrigger.AUTOMATIC }
            .sortedByDescending { it.createdAt }
            .drop(keepLast)
        old.forEach { backup -> backupResult { drive.delete(backup.id) } }
    }

    private suspend fun DriveBackupStore.listOrNull(): List<RemoteBackup>? = (backupResult { list() } as? BackupResult.Ok)?.value

    companion object {
        /** First run plus three retries. */
        const val MAX_ATTEMPTS = 4

        /** Problems that may go away on their own: worth retrying later. */
        val TRANSIENT = setOf(BackupFailure.NO_NETWORK, BackupFailure.UNKNOWN, BackupFailure.LOW_STORAGE, BackupFailure.BUSY)
    }
}

/** Status for the screen: settings, the last successful backup, the last automatic failure and the next slot. */
data class AutoBackupStatus(
    val settings: AutoBackupSettings,
    val lastSuccess: BackupRecord?,
    /** The latest automatic attempt, when it failed (failure reporting). */
    val lastAutomaticFailure: BackupRecord?,
    /** The earliest time the next automatic backup may start; null when switched off. */
    val nextRun: Instant?,
)

class ObserveAutoBackupStatusUseCase @Inject constructor(
    private val settings: AutoBackupSettingsRepository,
    private val history: BackupHistory,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<AutoBackupStatus> = combine(settings.observe(), history.observe()) { s, records ->
        val sorted = records.sortedByDescending { it.startedAt }
        val lastAutomatic = sorted.firstOrNull { it.trigger == BackupTrigger.AUTOMATIC && it.status != BackupStatus.IN_PROGRESS }
        AutoBackupStatus(
            settings = s,
            lastSuccess = sorted.firstOrNull { it.operation == BackupOperation.BACKUP && it.status == BackupStatus.SUCCESS },
            lastAutomaticFailure = lastAutomatic?.takeIf { it.status == BackupStatus.FAILED },
            nextRun = if (s.enabled) BackupSchedule.next(s, clock.now(), clock.zone()) else null,
        )
    }
}

/**
 * Saves the settings and (re)schedules: switching on needs a backup passphrase and a connected Google
 * Drive; switching off cancels the pending run.
 */
class UpdateAutoBackupSettingsUseCase @Inject constructor(
    private val settings: AutoBackupSettingsRepository,
    private val scheduler: AutoBackupScheduler,
    private val engine: BackupEngine,
    private val authorizer: DriveAuthorizer,
    private val clock: Clock,
) {
    suspend operator fun invoke(new: AutoBackupSettings): BackupFailure? {
        val problem = when {
            !new.enabled -> null
            !engine.hasPassphrase() -> BackupFailure.NO_PASSPHRASE
            !authorizer.observeConnected().first() -> BackupFailure.AUTH_REQUIRED
            else -> null
        }
        if (problem == null) {
            settings.save(new)
            if (new.enabled) scheduler.schedule(new, BackupSchedule.next(new, clock.now(), clock.zone())) else scheduler.cancel()
        }
        return problem
    }
}

/**
 * After every run, and at app start: makes sure the next slot is scheduled. [replace] is used by the
 * worker (the finished run is replaced by the next one); at app start an existing run is kept.
 */
class ScheduleNextAutoBackupUseCase @Inject constructor(
    private val settings: AutoBackupSettingsRepository,
    private val scheduler: AutoBackupScheduler,
    private val clock: Clock,
) {
    suspend operator fun invoke(replace: Boolean) {
        val current = settings.observe().first()
        if (!current.enabled) {
            scheduler.cancel()
            return
        }
        val next = BackupSchedule.next(current, clock.now(), clock.zone())
        if (replace) scheduler.schedule(current, next) else scheduler.ensureScheduled(current, next)
    }
}
