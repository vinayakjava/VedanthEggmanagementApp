package com.eggbusinessmanager.data.backup

import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import java.io.File
import java.io.IOException
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/**
 * Swaps a staged backup into place at startup, before Room opens (research R10, FR-051). Every step is
 * recorded in a state file first, so a crash at any point is recoverable on the next start:
 *
 * STAGED → SWAPPING (current DB moved to `safety/`, staging moved into place) → VERIFYING (integrity,
 * schema version and required tables checked; this phone's backup history carried over) → done.
 *
 * Any failure, or finding SWAPPING/VERIFYING on start (the app died mid-way), moves the safety copy back:
 * the data is exactly as it was before the restore. The outcome is kept for the app to show once.
 */
class RestoreCoordinator(
    private val dbFile: File,
    private val workDir: File,
    private val now: () -> Long,
) {
    enum class State { STAGED, SWAPPING, VERIFYING }
    enum class Outcome { RESTORED, ROLLED_BACK }

    val stagingFile: File get() = File(workDir, "staging.db")
    private val safetyDir: File get() = File(workDir, "safety")
    private val stateFile: File get() = File(workDir, "state")
    private val outcomeFile: File get() = File(workDir, "outcome")

    fun state(): State? = read(stateFile)?.let { text -> State.entries.firstOrNull { it.name == text } }

    private fun read(file: File): String? = file.takeIf { it.exists() }?.readText()?.trim()

    /** The owner confirmed: the staged database replaces the current one at the next start. */
    fun markStaged() {
        check(stagingFile.exists()) { "Nothing is staged" }
        writeState(State.STAGED)
    }

    fun discard() {
        if (state() == State.STAGED || state() == null) {
            stagingFile.delete()
            stateFile.delete()
        }
    }

    /** The outcome of the last restore, returned once. */
    fun consumeOutcome(): Outcome? {
        val outcome = read(outcomeFile)?.let { text -> Outcome.entries.firstOrNull { it.name == text } }
        outcomeFile.delete()
        return outcome
    }

    /**
     * Runs at startup. Returns null when no restore is pending. [key] is the device database key;
     * [failAt] is a test hook to simulate a crash at a given state.
     */
    fun resume(key: ByteArray, failAt: State? = null): Outcome? = when (state()) {
        null -> null
        State.STAGED -> try {
            swap(failAt)
            verifyAndCarryOver(key, failAt)
            finish(Outcome.RESTORED)
        } catch (e: SimulatedCrash) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Any failure while swapping or verifying means: put the previous data back (FR-051).
            rollback(key)
        }
        // Found mid-way: the app stopped during the swap or the check. Put the old data back.
        State.SWAPPING, State.VERIFYING -> rollback(key)
    }

    private fun swap(failAt: State?) {
        writeState(State.SWAPPING)
        safetyDir.deleteRecursively()
        safetyDir.mkdirs()
        dbFiles(dbFile).forEach { file -> if (file.exists()) move(file, File(safetyDir, file.name)) }
        if (failAt == State.SWAPPING) throw SimulatedCrash()
        move(stagingFile, dbFile)
    }

    private fun verifyAndCarryOver(key: ByteArray, failAt: State?) {
        writeState(State.VERIFYING)
        if (failAt == State.VERIFYING) throw SimulatedCrash()
        open(dbFile, key).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok") { "Restored database failed integrity check" }
            }
            check(db.version in 1..EggBusinessDatabase.VERSION) { "Unsupported schema ${db.version}" }
            val tables = tableNames(db)
            check(REQUIRED_TABLES.all { it in tables }) { "Restored database is missing tables" }
            carryOverHistory(db, key)
        }
    }

    /** This phone's backup history replaces the history inside the backup; the restore is marked successful. */
    private fun carryOverHistory(db: CipherDatabase, key: ByteArray) {
        val old = File(safetyDir, dbFile.name)
        db.execSQL("DELETE FROM backup_record")
        if (old.exists()) {
            val rawKey = String(DatabaseFactory.sqlCipherRawKey(key), Charsets.US_ASCII)
            db.execSQL("ATTACH DATABASE '${old.path.replace("'", "''")}' AS previous KEY \"$rawKey\"")
            try {
                if ("backup_record" in tableNames(db, "previous")) {
                    db.execSQL("INSERT INTO backup_record SELECT * FROM previous.backup_record")
                }
            } finally {
                db.execSQL("DETACH DATABASE previous")
            }
        }
        db.execSQL(
            "UPDATE backup_record SET status = 'SUCCESS', finished_at = ${now()} WHERE operation = 'RESTORE' AND status = 'IN_PROGRESS'",
        )
    }

    private fun rollback(key: ByteArray): Outcome {
        val saved = safetyDir.listFiles().orEmpty()
        if (saved.any { it.name == dbFile.name }) {
            dbFiles(dbFile).forEach { it.delete() }
            saved.forEach { move(it, File(dbFile.parentFile, it.name)) }
        }
        stagingFile.delete()
        // The previous data is back: record that the restore did not happen.
        if (dbFile.exists()) {
            runCatching {
                open(dbFile, key).use { db ->
                    db.execSQL(
                        "UPDATE backup_record SET status = 'FAILED', failure_code = 'INTERRUPTED', finished_at = ${now()} " +
                            "WHERE operation = 'RESTORE' AND status = 'IN_PROGRESS'",
                    )
                }
            }
        }
        return finish(Outcome.ROLLED_BACK)
    }

    private fun finish(outcome: Outcome): Outcome {
        outcomeFile.writeText(outcome.name)
        stateFile.delete()
        return outcome
    }

    private fun writeState(state: State) {
        workDir.mkdirs()
        val temp = File(workDir, "state.tmp")
        temp.writeText(state.name)
        if (!temp.renameTo(stateFile)) {
            stateFile.delete()
            if (!temp.renameTo(stateFile)) throw IOException("Could not record restore state")
        }
    }

    /** Thrown only by the [resume] test hook to leave the state file as a real crash would. */
    class SimulatedCrash : RuntimeException("Simulated crash")

    internal companion object {
        val REQUIRED_TABLES = setOf("owner_account", "business_profile", "customer", "ledger_entry", "app_setting", "backup_record")

        fun dbFiles(db: File): List<File> = listOf("", "-wal", "-shm", "-journal").map { File(db.path + it) }

        fun open(file: File, key: ByteArray, readOnly: Boolean = false): CipherDatabase {
            DatabaseFactory.loadNativeLibrary()
            return CipherDatabase.openDatabase(
                file.path,
                DatabaseFactory.sqlCipherRawKey(key),
                null,
                // ATTACH creates its target only on a connection opened with CREATE (snapshot and staging files).
                if (readOnly) CipherDatabase.OPEN_READONLY else CipherDatabase.OPEN_READWRITE or CipherDatabase.CREATE_IF_NECESSARY,
                null,
                null,
            )
        }

        fun tableNames(db: CipherDatabase, schema: String = "main"): Set<String> =
            db.rawQuery("SELECT name FROM $schema.sqlite_master WHERE type = 'table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }

        fun move(from: File, to: File) {
            to.delete()
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                if (!from.delete()) throw IOException("Could not move ${from.name}")
            }
        }
    }
}
