package com.eggbusinessmanager.data.backup

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Build
import com.eggbusinessmanager.data.backup.format.BackupCrypto
import com.eggbusinessmanager.data.backup.format.BackupFileFormat
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.data.security.DatabaseKeyProvider
import com.eggbusinessmanager.domain.backup.BackupArtifact
import com.eggbusinessmanager.domain.backup.BackupEngine
import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupFileName
import com.eggbusinessmanager.domain.backup.BackupHeader
import com.eggbusinessmanager.domain.backup.BackupSummary
import com.eggbusinessmanager.domain.backup.BackupTrigger
import com.eggbusinessmanager.domain.backup.CurrentDataSummary
import com.eggbusinessmanager.domain.backup.RestoreOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/**
 * Backups as encrypted SQLCipher snapshots (research R9, contracts/backup-format.md).
 *
 * Backup: on a separate connection, `sqlcipher_export` copies the live database — one consistent read —
 * into a new file encrypted with a fresh random key K. The file is never plaintext on disk. K is wrapped
 * with the passphrase-derived KEK, the header binds versions and the payload hash. The snapshot holds
 * everything in the database: business profile, account, customers, entries and payments, units,
 * varieties and settings.
 *
 * Restore staging validates in contract order and re-encrypts the payload with this phone's database key
 * into `staging.db`; the current data is untouched until [RestoreCoordinator] swaps it at the next start.
 */
@Singleton
class SnapshotBackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyProvider: DatabaseKeyProvider,
    private val keyStore: BackupKeyStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupEngine {

    private val dbFile: File get() = context.getDatabasePath(EggBusinessDatabase.FILE_NAME)
    private val tempDir: File get() = File(context.cacheDir, "backup").apply { mkdirs() }
    private val safetyDir: File get() = File(context.noBackupFilesDir, "safety-backups").apply { mkdirs() }

    /** Iterations for new passphrases; tests lower it to stay fast. */
    internal var kdfIterations = BackupCrypto.ITERATIONS

    val coordinator: RestoreCoordinator by lazy {
        RestoreCoordinator(dbFile, File(context.noBackupFilesDir, "restore"), System::currentTimeMillis)
    }

    override suspend fun hasPassphrase(): Boolean = withContext(io) { keyStore.hasKek() }

    override suspend fun setPassphrase(passphrase: String) = withContext(io) { keyStore.set(passphrase, kdfIterations) }

    override suspend fun createBackup(trigger: BackupTrigger, at: Instant): BackupArtifact = withContext(io) {
        val kek = keyStore.get() ?: throw BackupException(BackupFailure.NO_PASSPHRASE)
        val dir = if (trigger == BackupTrigger.SAFETY) safetyDir else tempDir
        requireSpace(dir, dbFile.length() * 2)
        val payload = File(dir, "payload-${UUID.randomUUID()}.db")
        val key = BackupCrypto.randomBytes(BackupCrypto.KEY_BYTES)
        try {
            val deviceKey = keyProvider.getOrCreateKey()
            val (schema, shopName) = RestoreCoordinator.open(dbFile, deviceKey).use { db ->
                export(db, payload, key)
                db.version to db.stringOrNull("SELECT shop_name FROM business_profile LIMIT 1")
            }
            val header = BackupHeader(
                formatVersion = BackupHeader.FORMAT_VERSION,
                createdAt = at,
                appVersionName = appVersionName(),
                appVersionCode = appVersionCode(),
                schemaVersion = schema,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                trigger = trigger,
                kdfIterations = kek.iterations,
                salt = kek.salt,
                payloadLength = payload.length(),
                payloadSha256 = payload.inputStream().use { BackupCrypto.sha256Hex(it) },
            )
            val headerBytes = BackupFileFormat.encodeHeader(header)
            val (nonce, wrapped) = BackupCrypto.wrap(kek.key, key, headerBytes)
            val name = BackupFileName.of(shopName, at.atZone(ZoneId.systemDefault()))
            // Safety copies get a prefix so they are never mistaken for a backup the owner made, and the exact
            // time so two restores in the same minute never overwrite each other's safety copy.
            val file = File(dir, if (trigger == BackupTrigger.SAFETY) "safety-${at.toEpochMilli()}-$name" else name)
            file.outputStream().buffered().use { BackupFileFormat.write(it, headerBytes, nonce, wrapped, payload) }
            if (trigger == BackupTrigger.SAFETY) pruneSafetyBackups()
            BackupArtifact(file.path, name, file.length(), header)
        } catch (e: SQLiteException) {
            throw BackupException(BackupFailure.UNKNOWN, "Snapshot failed", e)
        } catch (e: IOException) {
            throw BackupException(if (dir.usableSpace < MIN_FREE) BackupFailure.LOW_STORAGE else BackupFailure.UNKNOWN, "Writing failed", e)
        } finally {
            key.fill(0)
            kek.key.fill(0)
            payload.delete()
        }
    }

    /** `ATTACH` the target with raw key [key] and `sqlcipher_export` into it: encrypted from the first byte. */
    private fun export(source: CipherDatabase, target: File, key: ByteArray) {
        target.delete()
        source.execSQL("ATTACH DATABASE '${target.path.replace("'", "''")}' AS snapshot KEY \"${rawKey(key)}\"")
        try {
            source.rawQuery("SELECT sqlcipher_export('snapshot')", null).use { it.moveToFirst() }
            source.execSQL("PRAGMA snapshot.user_version = ${source.version}")
        } finally {
            source.execSQL("DETACH DATABASE snapshot")
        }
    }

    override suspend fun stage(path: String, passphrase: String): BackupSummary = withContext(io) {
        val file = File(path)
        // 1. Our file, a known format version.
        val opened = BackupFileFormat.open(file)
        val header = opened.header
        // 2. The passphrase unlocks the payload key (the header is authenticated too).
        val kek = BackupCrypto.deriveKek(passphrase, header.salt, header.kdfIterations)
        val key = try {
            BackupCrypto.unwrap(kek, opened.nonce, opened.wrappedKey, opened.headerBytes)
        } finally {
            kek.fill(0)
        }
        val payload = File(tempDir, "restore-${UUID.randomUUID()}.db")
        try {
            // 3. Length and SHA-256 of the payload.
            val actualLength = file.length() - opened.payloadOffset
            if (actualLength != header.payloadLength) throw BackupException(BackupFailure.CORRUPT, "Length mismatch")
            requireSpace(tempDir, actualLength * STAGING_SPACE_FACTOR)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(opened.payloadOffset)
                payload.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var left = actualLength
                    while (left > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
            }
            val hash = payload.inputStream().use { BackupCrypto.sha256Hex(it) }
            if (hash != header.payloadSha256) throw BackupException(BackupFailure.CORRUPT, "Hash mismatch")
            // 4–6. Opens with K, passes integrity, known schema; re-encrypted for this phone into staging.
            val staging = coordinator.stagingFile.apply { parentFile?.mkdirs() }
            openPayload(payload, key).use { db ->
                checkPayload(db)
                export(db, staging, keyProvider.getOrCreateKey())
            }
            summary(staging, header)
        } catch (e: SQLiteException) {
            coordinator.stagingFile.delete()
            throw BackupException(BackupFailure.CORRUPT, "Payload unreadable", e)
        } catch (e: BackupException) {
            coordinator.stagingFile.delete()
            throw e
        } finally {
            key.fill(0)
            payload.delete()
        }
    }

    private fun openPayload(payload: File, key: ByteArray): CipherDatabase = try {
        RestoreCoordinator.open(payload, key)
    } catch (e: SQLiteException) {
        throw BackupException(BackupFailure.CORRUPT, "Payload does not open", e)
    }

    /** Steps 4–5: integrity, a known schema version and all required tables. */
    private fun checkPayload(db: CipherDatabase) {
        val intact = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
        val failure = when {
            !intact -> BackupFailure.CORRUPT
            db.version > EggBusinessDatabase.VERSION -> BackupFailure.NEWER_VERSION
            db.version < 1 || !RestoreCoordinator.tableNames(db).containsAll(RestoreCoordinator.REQUIRED_TABLES) -> BackupFailure.CORRUPT
            else -> null
        }
        if (failure != null) throw BackupException(failure, "Payload check failed")
    }

    private fun summary(staging: File, header: BackupHeader): BackupSummary =
        RestoreCoordinator.open(staging, keyProvider.getOrCreateKey(), readOnly = true).use { db ->
            BackupSummary(
                createdAt = header.createdAt,
                shopName = db.stringOrNull("SELECT shop_name FROM business_profile LIMIT 1"),
                ownerName = db.stringOrNull("SELECT owner_name FROM business_profile LIMIT 1"),
                customers = db.count("SELECT COUNT(*) FROM customer"),
                entries = db.count(ACTIVE_ENTRIES),
                lastEntryDate = db.longOrNull(LAST_ENTRY)?.let(LocalDate::ofEpochDay),
                schemaVersion = db.version,
                deviceModel = header.deviceModel,
            )
        }

    override suspend fun currentSummary(): CurrentDataSummary = withContext(io) {
        if (!dbFile.exists()) return@withContext CurrentDataSummary(null, 0, 0, null)
        RestoreCoordinator.open(dbFile, keyProvider.getOrCreateKey()).use { db ->
            CurrentDataSummary(
                shopName = db.stringOrNull("SELECT shop_name FROM business_profile LIMIT 1"),
                customers = db.count("SELECT COUNT(*) FROM customer"),
                entries = db.count(ACTIVE_ENTRIES),
                lastEntryDate = db.longOrNull(LAST_ENTRY)?.let(LocalDate::ofEpochDay),
            )
        }
    }

    override suspend fun confirmStagedRestore() = withContext(io) { coordinator.markStaged() }

    override suspend fun discardStaged() = withContext(io) { coordinator.discard() }

    override suspend fun consumeRestoreOutcome(): RestoreOutcome? = withContext(io) {
        when (coordinator.consumeOutcome()) {
            RestoreCoordinator.Outcome.RESTORED -> RestoreOutcome.RESTORED
            RestoreCoordinator.Outcome.ROLLED_BACK -> RestoreOutcome.ROLLED_BACK
            null -> null
        }
    }

    override suspend fun safetyBackups(): List<BackupArtifact> = withContext(io) {
        safetyDir.listFiles { f -> f.name.endsWith(".${BackupHeader.EXTENSION}") }.orEmpty()
            .mapNotNull { f -> runCatching { BackupArtifact(f.path, f.name, f.length(), BackupFileFormat.open(f).header) }.getOrNull() }
            .sortedByDescending { it.header.createdAt }
    }

    /** Only files inside the app's own backup folders are ever deleted. */
    override fun delete(path: String) {
        val file = File(path).canonicalFile
        val allowed = listOf(tempDir, File(context.cacheDir, "restore")).map { it.canonicalPath }
        if (allowed.any { file.path.startsWith(it) }) file.delete()
    }

    private fun pruneSafetyBackups() {
        safetyDir.listFiles { f -> f.name.endsWith(".${BackupHeader.EXTENSION}") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(SAFETY_TO_KEEP)
            .forEach { it.delete() }
    }

    private fun requireSpace(dir: File, needed: Long) {
        if (dir.usableSpace < needed + MIN_FREE) throw BackupException(BackupFailure.LOW_STORAGE)
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    private fun appVersionCode(): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    private fun rawKey(key: ByteArray) = String(DatabaseFactory.sqlCipherRawKey(key), Charsets.US_ASCII)

    private fun CipherDatabase.stringOrNull(sql: String): String? =
        rawQuery(sql, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun CipherDatabase.longOrNull(sql: String): Long? =
        rawQuery(sql, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private fun CipherDatabase.count(sql: String): Int = rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private companion object {
        const val MIN_FREE = 10L * 1024 * 1024
        const val SAFETY_TO_KEEP = 3

        /** Payload copy, staging copy and headroom. */
        const val STAGING_SPACE_FACTOR = 3
        const val ACTIVE_ENTRIES = "SELECT COUNT(*) FROM ledger_entry WHERE status = 'ACTIVE' AND entry_type <> 'OPENING_BALANCE'"
        const val LAST_ENTRY = "SELECT MAX(entry_date) FROM ledger_entry WHERE status = 'ACTIVE' AND entry_type <> 'OPENING_BALANCE'"
    }
}
