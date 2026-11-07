package com.eggbusinessmanager.data.db

import android.content.Context
import android.database.sqlite.SQLiteFullException
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.data.security.DatabaseKeyProvider
import com.eggbusinessmanager.data.security.KeyUnavailableException
import com.eggbusinessmanager.data.settings.DeviceStateStore
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.model.StartupState
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.IdGenerator
import com.eggbusinessmanager.domain.repository.StartupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/**
 * Runs before any screen touches data (research R10, plan § APK Distribution and Upgrade Safety):
 * 1. obtains the Keystore-protected database key;
 * 2. refuses a database written by a newer app version (downgrade guard);
 * 3. snapshots the encrypted file before a schema migration (keeps the last 2);
 * 4. opens Room so creation/migrations happen here, off the main thread.
 *
 * The restore state machine (US9) plugs in before step 2.
 */
@Singleton
class DatabaseBootstrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyProvider: DatabaseKeyProvider,
    private val database: Provider<EggBusinessDatabase>,
    private val deviceStateStore: DeviceStateStore,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val logger: AppLogger,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StartupRepository {

    private val mutex = Mutex()
    private var result: StartupState? = null

    /** Runs once per process; only success is cached so "Try again" can re-run a failed start. */
    override suspend fun prepare(): StartupState = mutex.withLock {
        result ?: withContext(io) { runStartup() }.also { if (it == StartupState.Ready) result = it }
    }

    private suspend fun runStartup(): StartupState {
        if (context.filesDir.usableSpace < MIN_FREE_BYTES) {
            logger.warn(TAG) { "Not enough free storage to start safely" }
            return StartupState.Failed(StartupState.Failed.Reason.LOW_STORAGE)
        }
        return try {
            DatabaseFactory.loadNativeLibrary()
            checkExistingDatabase(keyProvider.getOrCreateKey()) ?: openDatabase()
        } catch (e: KeyUnavailableException) {
            logger.error(TAG, e) { "Database key unavailable" }
            StartupState.Failed(StartupState.Failed.Reason.KEY_UNAVAILABLE)
        } catch (e: SQLiteFullException) {
            logger.error(TAG, e) { "Storage full while opening database" }
            StartupState.Failed(StartupState.Failed.Reason.LOW_STORAGE)
        }
    }

    /**
     * Downgrade guard and pre-migration snapshot for an existing database file.
     * Returns a terminal state when startup must stop, or null to continue opening.
     */
    private fun checkExistingDatabase(key: ByteArray): StartupState? {
        val dbFile = context.getDatabasePath(EggBusinessDatabase.FILE_NAME)
        if (!dbFile.exists()) return null
        val storedVersion = readUserVersion(dbFile, key)
        return when {
            storedVersion > EggBusinessDatabase.VERSION -> {
                logger.warn(TAG) { "Database v$storedVersion is newer than supported v${EggBusinessDatabase.VERSION}" }
                StartupState.UpdateRequired(storedVersion, EggBusinessDatabase.VERSION)
            }
            storedVersion in 1 until EggBusinessDatabase.VERSION -> {
                snapshotBeforeMigration(dbFile, storedVersion)
                null
            }
            else -> null
        }
    }

    /** Opening the writable database creates the schema or runs migrations, here and only here. */
    private suspend fun openDatabase(): StartupState {
        database.get().openHelper.writableDatabase
        deviceStateStore.recordStartup(clock.now(), idGenerator::newId)
        return StartupState.Ready
    }

    private fun readUserVersion(dbFile: File, key: ByteArray): Int {
        val db = CipherDatabase.openDatabase(
            dbFile.path,
            DatabaseFactory.sqlCipherRawKey(key),
            null,
            CipherDatabase.OPEN_READONLY,
            null,
            null,
        )
        return try {
            db.version
        } finally {
            db.close()
        }
    }

    private fun snapshotBeforeMigration(dbFile: File, fromVersion: Int) {
        val dir = File(context.noBackupFilesDir, "premigration").apply { mkdirs() }
        val stamp = clock.now().toEpochMilli()
        // Copy the main file together with its WAL and SHM companions so the snapshot is consistent.
        listOf("", "-wal", "-shm").forEach { suffix ->
            val source = File(dbFile.path + suffix)
            if (source.exists()) source.copyTo(File(dir, "v$fromVersion-$stamp.db$suffix"), overwrite = true)
        }
        dir.listFiles { file -> file.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(SNAPSHOTS_TO_KEEP)
            ?.forEach { old -> listOf("", "-wal", "-shm").forEach { File(old.path + it).delete() } }
        logger.info(TAG) { "Pre-migration snapshot taken for schema v$fromVersion" }
    }

    private companion object {
        const val TAG = "DatabaseBootstrapper"
        const val MIN_FREE_BYTES = 10L * 1024 * 1024
        const val SNAPSHOTS_TO_KEEP = 2
    }
}
