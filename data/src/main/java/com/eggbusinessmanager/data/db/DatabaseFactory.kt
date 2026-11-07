package com.eggbusinessmanager.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Builds the encrypted Room database. The only place that wires SQLCipher into Room. */
object DatabaseFactory {

    /** AES-256 key length used by SQLCipher. */
    const val KEY_BYTES = 32

    @Volatile
    private var libraryLoaded = false

    /** SQLCipher's native library must be loaded before any encrypted database is opened. */
    fun loadNativeLibrary() {
        if (!libraryLoaded) {
            synchronized(this) {
                if (!libraryLoaded) {
                    System.loadLibrary("sqlcipher")
                    libraryLoaded = true
                }
            }
        }
    }

    /**
     * SQLCipher "raw key" syntax: the 32-byte key is used directly as the AES key, which skips the
     * passphrase KDF (the key is already random and is protected by the Android Keystore).
     */
    fun sqlCipherRawKey(key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Database key must be $KEY_BYTES bytes" }
        val hex = key.joinToString(separator = "") { "%02x".format(it) }
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    fun build(
        context: Context,
        key: ByteArray,
        fileName: String = EggBusinessDatabase.FILE_NAME,
    ): EggBusinessDatabase {
        loadNativeLibrary()
        return Room.databaseBuilder(context, EggBusinessDatabase::class.java, fileName)
            .openHelperFactory(SupportOpenHelperFactory(sqlCipherRawKey(key)))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(ForeignKeysCallback)
            .build()
    }

    /** Foreign keys are enforced on every connection (data-model.md conventions). */
    private object ForeignKeysCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query("PRAGMA foreign_keys = ON").close()
        }
    }
}
