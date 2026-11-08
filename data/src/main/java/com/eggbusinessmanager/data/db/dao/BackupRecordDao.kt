package com.eggbusinessmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.eggbusinessmanager.data.db.entity.BackupRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupRecordDao {
    @Query("SELECT * FROM backup_record ORDER BY started_at DESC, id DESC")
    fun observeAll(): Flow<List<BackupRecordEntity>>

    @Insert
    suspend fun insert(record: BackupRecordEntity): Long

    @Query(
        "UPDATE backup_record SET status = 'SUCCESS', finished_at = :at, size_bytes = :sizeBytes, file_name = :fileName, " +
            "drive_file_id = :driveFileId WHERE id = :id",
    )
    suspend fun succeed(id: Long, at: Long, sizeBytes: Long?, fileName: String?, driveFileId: String?)

    @Query("UPDATE backup_record SET status = 'FAILED', finished_at = :at, failure_code = :failure WHERE id = :id")
    suspend fun fail(id: Long, at: Long, failure: String)
}
