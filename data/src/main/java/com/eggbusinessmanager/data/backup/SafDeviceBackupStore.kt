package com.eggbusinessmanager.data.backup

import android.content.Context
import android.net.Uri
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.DeviceBackupStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Backups on the phone through the Storage Access Framework: the owner picks a folder and name to save
 * (CREATE_DOCUMENT) or a file to restore (OPEN_DOCUMENT). No storage permission is needed and the file can
 * live on internal storage, an SD card, a USB drive or a cloud folder.
 */
class SafDeviceBackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeviceBackupStore {

    override suspend fun write(artifactPath: String, uri: String): Unit = withContext(io) {
        try {
            val out = context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
                ?: throw BackupException(BackupFailure.LOCATION_UNAVAILABLE)
            out.use { target -> File(artifactPath).inputStream().use { it.copyTo(target) } }
            Unit
        } catch (e: IOException) {
            throw BackupException(BackupFailure.LOCATION_UNAVAILABLE, "Could not write the backup file", e)
        } catch (e: SecurityException) {
            throw BackupException(BackupFailure.LOCATION_UNAVAILABLE, "No access to the location", e)
        }
    }

    override suspend fun read(uri: String): String = withContext(io) {
        val dir = File(context.cacheDir, "restore").apply { mkdirs() }
        val copy = File(dir, "incoming-${UUID.randomUUID()}.ebmbackup")
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(uri))
                ?: throw BackupException(BackupFailure.LOCATION_UNAVAILABLE)
            input.use { source -> copy.outputStream().use { source.copyTo(it) } }
            copy.path
        } catch (e: IOException) {
            copy.delete()
            throw BackupException(BackupFailure.LOCATION_UNAVAILABLE, "Could not read the backup file", e)
        } catch (e: SecurityException) {
            copy.delete()
            throw BackupException(BackupFailure.LOCATION_UNAVAILABLE, "No access to the file", e)
        }
    }
}
