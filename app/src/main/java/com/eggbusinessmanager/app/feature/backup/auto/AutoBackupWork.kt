package com.eggbusinessmanager.app.feature.backup.auto

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eggbusinessmanager.domain.backup.AutoBackupOutcome
import com.eggbusinessmanager.domain.backup.AutoBackupScheduler
import com.eggbusinessmanager.domain.backup.AutoBackupSettings
import com.eggbusinessmanager.domain.backup.RunAutomaticBackupUseCase
import com.eggbusinessmanager.domain.backup.ScheduleNextAutoBackupUseCase
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.model.StartupState
import com.eggbusinessmanager.domain.repository.StartupRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules automatic backups with WorkManager (Phase 10). One unique one-time job holds the next slot;
 * [schedule] replaces it (never two pending runs), [ensureScheduled] keeps it. The slot is only the earliest
 * start: WorkManager also waits for a network (Wi‑Fi only if chosen), enough battery (or charging if chosen),
 * and Android's battery management (Doze, App Standby) may delay it further — so no exact time is promised.
 */
@Singleton
class WorkManagerAutoBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoBackupScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    override fun schedule(settings: AutoBackupSettings, at: Instant) {
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request(settings, at))
    }

    override fun ensureScheduled(settings: AutoBackupSettings, at: Instant) {
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request(settings, at))
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    internal fun request(settings: AutoBackupSettings, at: Instant) = OneTimeWorkRequestBuilder<AutoBackupWorker>()
        .setInitialDelay(Duration.between(Instant.now(), at).coerceAtLeast(Duration.ZERO).toMillis(), TimeUnit.MILLISECONDS)
        .setConstraints(constraints(settings))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
        .addTag(WORK_TAG)
        .build()

    internal fun constraints(settings: AutoBackupSettings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(settings.onlyWhileCharging)
        .setRequiresStorageNotLow(true)
        .build()

    companion object {
        const val WORK_NAME = "automatic-backup"
        const val WORK_TAG = "backup"
        const val BACKOFF_MINUTES = 15L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoBackupEntryPoint {
    fun startup(): StartupRepository
    fun runAutomaticBackup(): RunAutomaticBackupUseCase
    fun scheduleNext(): ScheduleNextAutoBackupUseCase
    fun logger(): AppLogger
}

/**
 * Runs one automatic backup. Opens the database the normal way first (a pending restore is swapped in or
 * rolled back by the bootstrapper), then maps the outcome: temporary problems → retry with backoff;
 * everything else → done, with the next slot scheduled. Failures were already recorded and notified.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, AutoBackupEntryPoint::class.java)
        if (deps.startup().prepare() != StartupState.Ready) return Result.retry()
        val outcome = deps.runAutomaticBackup()(runAttemptCount)
        deps.logger().info(TAG) { "Automatic backup attempt $runAttemptCount: $outcome" }
        if (outcome !is AutoBackupOutcome.RetryLater) deps.scheduleNext()(replace = true)
        return resultFor(outcome)
    }

    companion object {
        private const val TAG = "AutoBackupWorker"

        /** Temporary problems are retried by WorkManager; everything else ends this run. */
        fun resultFor(outcome: AutoBackupOutcome): Result = when (outcome) {
            is AutoBackupOutcome.RetryLater -> Result.retry()
            else -> Result.success()
        }
    }
}
