package com.eggbusinessmanager.data.backup

import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.entity.BackupRecordEntity
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.backup.BackupDestination
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupHistory
import com.eggbusinessmanager.domain.backup.BackupOperation
import com.eggbusinessmanager.domain.backup.BackupRecord
import com.eggbusinessmanager.domain.backup.BackupStatus
import com.eggbusinessmanager.domain.backup.BackupTrigger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider

class RoomBackupHistory @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupHistory {

    private val dao get() = database.get().backupRecordDao()

    override fun observe(): Flow<List<BackupRecord>> =
        flow { emitAll(dao.observeAll()) }.map { rows -> rows.map { it.toDomain() } }.flowOn(io)

    override suspend fun start(operation: BackupOperation, destination: BackupDestination, trigger: BackupTrigger, at: Instant): Long =
        withContext(io) {
            dao.insert(
                BackupRecordEntity(
                    operation = operation.name,
                    destination = destination.name,
                    trigger = trigger.name,
                    status = BackupStatus.IN_PROGRESS.name,
                    startedAt = at.toEpochMilli(),
                    schemaVersion = EggBusinessDatabase.VERSION,
                ),
            )
        }

    override suspend fun succeed(id: Long, at: Instant, sizeBytes: Long?, fileName: String?, driveFileId: String?) =
        withContext(io) { dao.succeed(id, at.toEpochMilli(), sizeBytes, fileName, driveFileId) }

    override suspend fun fail(id: Long, at: Instant, failure: BackupFailure) =
        withContext(io) { dao.fail(id, at.toEpochMilli(), failure.name) }
}

internal fun BackupRecordEntity.toDomain() = BackupRecord(
    id = id,
    operation = BackupOperation.valueOf(operation),
    destination = BackupDestination.valueOf(destination),
    trigger = BackupTrigger.entries.firstOrNull { it.name == trigger } ?: BackupTrigger.MANUAL,
    status = BackupStatus.valueOf(status),
    startedAt = Instant.ofEpochMilli(startedAt),
    finishedAt = finishedAt?.let(Instant::ofEpochMilli),
    sizeBytes = sizeBytes,
    fileName = fileName,
    driveFileId = driveFileId,
    schemaVersion = schemaVersion,
    failure = failureCode?.let { code -> BackupFailure.entries.firstOrNull { it.name == code } ?: BackupFailure.UNKNOWN },
)
