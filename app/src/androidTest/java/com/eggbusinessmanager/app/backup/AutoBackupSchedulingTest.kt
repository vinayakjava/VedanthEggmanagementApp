package com.eggbusinessmanager.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.eggbusinessmanager.app.feature.backup.auto.AutoBackupWorker
import com.eggbusinessmanager.app.feature.backup.auto.WorkManagerAutoBackupScheduler
import com.eggbusinessmanager.domain.backup.AutoBackupSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * Automatic backup scheduling on the real WorkManager (Phase 10): one unique job, the right delay and
 * constraints, replaced on change, kept at app start, cancelled when switched off. The worker itself runs
 * against the app's real dependencies.
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupSchedulingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler = WorkManagerAutoBackupScheduler(context)
    private val workManager = WorkManager.getInstance(context)
    private val on = AutoBackupSettings(enabled = true)

    @Before
    @After
    fun clear() {
        scheduler.cancel()
        workManager.pruneWork().result.get()
    }

    private fun pending(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(WorkManagerAutoBackupScheduler.WORK_NAME).get()
        .filter { !it.state.isFinished }

    private fun assertDelay(info: WorkInfo, expected: Duration) {
        assertThat(info.initialDelayMillis).isAtLeast(expected.minusMinutes(2).toMillis())
        assertThat(info.initialDelayMillis).isAtMost(expected.toMillis())
    }

    @Test
    fun oneJobWaitsForTheSlotWithNetworkAndBatteryConditions() {
        scheduler.schedule(on, Instant.now().plus(Duration.ofHours(3)))
        val info = pending().single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.tags).contains(WorkManagerAutoBackupScheduler.WORK_TAG)
        assertDelay(info, Duration.ofHours(3))
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(info.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(info.constraints.requiresStorageNotLow()).isTrue()
        assertThat(info.constraints.requiresCharging()).isFalse()
    }

    @Test
    fun changingTheSettingsReplacesTheJobInsteadOfAddingOne() {
        scheduler.schedule(on, Instant.now().plus(Duration.ofHours(3)))
        scheduler.schedule(on.copy(wifiOnly = true, onlyWhileCharging = true), Instant.now().plus(Duration.ofHours(5)))
        val info = pending().single()
        assertDelay(info, Duration.ofHours(5))
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(info.constraints.requiresCharging()).isTrue()
    }

    @Test
    fun atAppStartAnExistingJobIsKept() {
        scheduler.schedule(on, Instant.now().plus(Duration.ofHours(5)))
        val id = pending().single().id
        scheduler.ensureScheduled(on, Instant.now().plus(Duration.ofHours(1)))
        val info = pending().single()
        assertThat(info.id).isEqualTo(id)
        assertDelay(info, Duration.ofHours(5))
    }

    @Test
    fun switchingOffCancelsThePendingJob() {
        scheduler.schedule(on, Instant.now().plus(Duration.ofHours(3)))
        scheduler.cancel()
        assertThat(pending()).isEmpty()
        assertThat(workManager.getWorkInfosForUniqueWork(WorkManagerAutoBackupScheduler.WORK_NAME).get().map { it.state })
            .containsExactly(WorkInfo.State.CANCELLED)
    }

    @Test
    fun aSlotInThePastRunsWithoutDelay() {
        scheduler.schedule(on.copy(onlyWhileCharging = true), Instant.now().minus(Duration.ofHours(1)))
        assertThat(pending().single().initialDelayMillis).isEqualTo(0L)
    }

    @Test
    fun theWorkerRunsWithTheAppAndFinishesWhenAutomaticBackupIsOff() {
        // Default settings: automatic backup is off, so the run is skipped and nothing is rescheduled.
        val worker = TestListenableWorkerBuilder<AutoBackupWorker>(context).setRunAttemptCount(0).build()
        val result = runBlocking { worker.doWork() }
        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(pending()).isEmpty()
    }
}
