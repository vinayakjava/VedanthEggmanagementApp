package com.eggbusinessmanager.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.data.db.entity.AppSettingEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "encrypted-db-test.db"
    private lateinit var database: EggBusinessDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(fileName)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        database = DatabaseFactory.build(context, key, fileName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(fileName)
    }

    @Test
    fun databaseFileIsNotPlaintextSqlite() = runTest {
        database.appSettingDao().upsert(AppSettingEntity("theme", "DARK"))
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()

        val header = context.getDatabasePath(fileName).inputStream().use { input ->
            ByteArray(16).also { input.read(it) }
        }
        // Every plaintext SQLite file starts with "SQLite format 3\u0000".
        assertThat(String(header, Charsets.US_ASCII)).isNotEqualTo("SQLite format 3\u0000")
    }

    @Test
    fun settingsRoundTrip() = runTest {
        val dao = database.appSettingDao()
        dao.upsert(AppSettingEntity("theme", "LIGHT"))
        dao.upsert(AppSettingEntity("theme", "DARK"))

        assertThat(dao.get("theme")).isEqualTo("DARK")
        assertThat(dao.observe("theme").first()).isEqualTo("DARK")
    }

    @Test
    fun foreignKeysAreEnforced() {
        val cursor = database.openHelper.writableDatabase.query("PRAGMA foreign_keys")
        cursor.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
    }
}
