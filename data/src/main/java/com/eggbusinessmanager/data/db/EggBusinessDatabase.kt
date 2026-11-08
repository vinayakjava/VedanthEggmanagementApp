package com.eggbusinessmanager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eggbusinessmanager.data.db.dao.AccountDao
import com.eggbusinessmanager.data.db.dao.AppSettingDao
import com.eggbusinessmanager.data.db.dao.AuditLogDao
import com.eggbusinessmanager.data.db.dao.CatalogDao
import com.eggbusinessmanager.data.db.dao.CustomerDao
import com.eggbusinessmanager.data.db.dao.LedgerEntryDao
import com.eggbusinessmanager.data.db.entity.AppSettingEntity
import com.eggbusinessmanager.data.db.entity.AuditLogEntity
import com.eggbusinessmanager.data.db.entity.CustomerEntity
import com.eggbusinessmanager.data.db.entity.EggVarietyEntity
import com.eggbusinessmanager.data.db.entity.MeasurementUnitEntity
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import com.eggbusinessmanager.data.db.entity.ReferenceSequenceEntity
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
        CustomerEntity::class,
        LedgerEntryEntity::class,
        AuditLogEntity::class,
        ReferenceSequenceEntity::class,
        MeasurementUnitEntity::class,
        EggVarietyEntity::class,
    ],
    version = EggBusinessDatabase.VERSION,
    exportSchema = true,
)
abstract class EggBusinessDatabase : RoomDatabase() {
    abstract fun appSettingDao(): AppSettingDao
    abstract fun accountDao(): AccountDao
    abstract fun customerDao(): CustomerDao
    abstract fun ledgerEntryDao(): LedgerEntryDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun catalogDao(): CatalogDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "egg_business.db"
    }
}
