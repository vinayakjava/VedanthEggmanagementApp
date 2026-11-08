package com.eggbusinessmanager.data.backup.drive

import android.content.Context
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.backup.BackupArtifact
import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupHeader
import com.eggbusinessmanager.domain.backup.BackupTrigger
import com.eggbusinessmanager.domain.backup.DriveAuthorizer
import com.eggbusinessmanager.domain.backup.DriveBackupStore
import com.eggbusinessmanager.domain.backup.RemoteBackup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Backups in Google Drive's hidden app folder, using a token from [DriveAuthorizer]. */
@Singleton
class GoogleDriveBackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authorizer: DriveAuthorizer,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DriveBackupStore {

    private val client by lazy {
        DriveClient(
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                // DriveClient resumes uploads itself from what Drive confirmed; no blind replays.
                .retryOnConnectionFailure(false)
                .build(),
        )
    }

    override suspend fun upload(artifact: BackupArtifact): String {
        val token = authorizer.accessToken()
        val h = artifact.header
        // appProperties: each value ≤ 124 bytes; no business data.
        val properties = mapOf(
            "ebmFormat" to h.formatVersion.toString(),
            "createdAt" to h.createdAt.toString(),
            "trigger" to h.trigger.name,
            "appVersion" to h.appVersionName.take(MAX_PROPERTY),
            "schemaVersion" to h.schemaVersion.toString(),
            "device" to h.deviceModel.take(MAX_PROPERTY),
            "sizeBytes" to artifact.sizeBytes.toString(),
        )
        return withContext(io) { client.upload(token, File(artifact.path), artifact.fileName, properties) }
    }

    override suspend fun list(): List<RemoteBackup> {
        val token = authorizer.accessToken()
        return withContext(io) { client.list(token).map { it.toRemote() } }
    }

    override suspend fun download(id: String): String {
        val token = authorizer.accessToken()
        return withContext(io) {
            val dir = File(context.cacheDir, "restore").apply { mkdirs() }
            val target = File(dir, "drive-${UUID.randomUUID()}.${BackupHeader.EXTENSION}")
            try {
                client.download(token, id, target)
                target.path
            } catch (e: BackupException) {
                target.delete()
                throw e
            }
        }
    }

    private fun DriveClient.DriveFile.toRemote(): RemoteBackup {
        val created = (appProperties["createdAt"] ?: createdTime)?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                null
            }
        } ?: throw BackupException(BackupFailure.UNKNOWN, "Drive file without a date")
        return RemoteBackup(
            id = id,
            name = name,
            createdAt = created,
            sizeBytes = size?.toLongOrNull() ?: appProperties["sizeBytes"]?.toLongOrNull() ?: 0,
            trigger = BackupTrigger.entries.firstOrNull { it.name == appProperties["trigger"] } ?: BackupTrigger.MANUAL,
            appVersion = appProperties["appVersion"],
            device = appProperties["device"],
        )
    }

    private companion object {
        const val TIMEOUT_S = 60L
        const val MAX_PROPERTY = 100
    }
}
