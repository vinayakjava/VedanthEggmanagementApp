package com.eggbusinessmanager.app.backup

import androidx.work.ListenableWorker
import com.eggbusinessmanager.app.feature.backup.auto.AutoBackupWorker
import com.eggbusinessmanager.domain.backup.AutoBackupOutcome
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How each automatic backup outcome is reported to WorkManager (retry handling). */
class AutoBackupWorkerResultTest {

    @Test
    fun `temporary problems ask WorkManager to retry with backoff`() {
        listOf(BackupFailure.NO_NETWORK, BackupFailure.BUSY, BackupFailure.LOW_STORAGE, BackupFailure.UNKNOWN).forEach {
            assertThat(AutoBackupWorker.resultFor(AutoBackupOutcome.RetryLater(it))).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        }
    }

    @Test
    fun `done, skipped and final failures end the run so the next slot is scheduled`() {
        listOf(
            AutoBackupOutcome.Done(1),
            AutoBackupOutcome.Skipped(AutoBackupOutcome.SkipReason.ALREADY_DONE),
            AutoBackupOutcome.Skipped(AutoBackupOutcome.SkipReason.DISABLED),
            AutoBackupOutcome.Failed(BackupFailure.AUTH_REQUIRED),
            AutoBackupOutcome.Failed(BackupFailure.NO_NETWORK),
        ).forEach { assertThat(AutoBackupWorker.resultFor(it)).isInstanceOf(ListenableWorker.Result.Success::class.java) }
    }
}
