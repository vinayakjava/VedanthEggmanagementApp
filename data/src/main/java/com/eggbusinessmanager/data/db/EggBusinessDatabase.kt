package com.eggbusinessmanager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eggbusinessmanager.data.db.dao.AccountDao
import com.eggbusinessmanager.data.db.dao.AppSettingDao
import com.eggbusinessmanager.data.db.entity.AppSettingEntity
import com.eggbusinessmanager.data.db.entity.BusinessProfileEntity
import com.eggbusinessmanager.data.db.entity.OwnerAccountEntity
import com.eggbusinessmanager.data.db.entity.SecurityAnswerEntity

/**
 * The single source of truth for all business data (Constitution I), encrypted with SQLCipher.
 *
 * Schema rules (Constitution III):
 * - every version is exported to data/schemas and committed;
 * - every version change ships an explicit Migration plus a MigrationTestHelper test;
 * - destructive fallback is forbidden (checked by scripts/check-no-destructive-migration.ps1).
 *
 * Version 1 is still being assembled before the first release; business tables are added by later
 * phases and v1 is frozen when the first APK is given to the owner for real data.
 */
@Database(
    entities = [
        AppSettingEntity::class,
        OwnerAccountEntity::class,
        SecurityAnswerEntity::class,
        BusinessProfileEntity::class,
    ],
    version = EggBusinessDatabase.VERSION,
    exportSchema = true,
)
abstract class EggBusinessDatabase : RoomDatabase() {
    abstract fun appSettingDao(): AppSettingDao
    abstract fun accountDao(): AccountDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "egg_business.db"
    }
}
