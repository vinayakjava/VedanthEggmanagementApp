package com.eggbusinessmanager.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Backup and restore history (data-model.md § backup_record). Device-operational: during a restore the
 * phone's own rows are carried into the restored database, so the history describes this phone.
 */
@Entity(tableName = "backup_record", indices = [Index(value = ["started_at"])])
data class BackupRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "operation") val operation: String,
    @ColumnInfo(name = "destination") val destination: String,
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long? = null,
    @ColumnInfo(name = "file_name") val fileName: String? = null,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String? = null,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "failure_code") val failureCode: String? = null,
    @ColumnInfo(name = "failure_detail") val failureDetail: String? = null,
)
