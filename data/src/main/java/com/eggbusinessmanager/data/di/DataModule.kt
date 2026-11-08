package com.eggbusinessmanager.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.eggbusinessmanager.data.db.DatabaseBootstrapper
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.RoomTransactionRunner
import com.eggbusinessmanager.data.repository.RoomAccountRepository
import com.eggbusinessmanager.data.repository.RoomAuditRepository
import com.eggbusinessmanager.data.repository.RoomBusinessProfileRepository
import com.eggbusinessmanager.data.repository.RoomCatalogRepository
import com.eggbusinessmanager.data.repository.RoomReportRepository
import com.eggbusinessmanager.data.backup.RoomBackupHistory
import com.eggbusinessmanager.data.backup.SafDeviceBackupStore
import com.eggbusinessmanager.data.backup.SnapshotBackupEngine
import com.eggbusinessmanager.data.backup.drive.GoogleDriveBackupStore
import com.eggbusinessmanager.data.export.FileReportExporter
import com.eggbusinessmanager.domain.backup.BackupEngine
import com.eggbusinessmanager.domain.backup.BackupHistory
import com.eggbusinessmanager.domain.backup.DeviceBackupStore
import com.eggbusinessmanager.domain.backup.DriveBackupStore
import com.eggbusinessmanager.domain.export.ReportExporter
import com.eggbusinessmanager.data.repository.RoomCustomerRepository
import com.eggbusinessmanager.data.repository.RoomLedgerRepository
import com.eggbusinessmanager.data.repository.RoomSettingsRepository
import com.eggbusinessmanager.data.security.BiometricKeyManager
import com.eggbusinessmanager.data.security.Pbkdf2SecretHasher
import com.eggbusinessmanager.data.security.DatabaseKeyProvider
import com.eggbusinessmanager.data.time.SystemClock
import com.eggbusinessmanager.data.time.UuidIdGenerator
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.AuditRepository
import com.eggbusinessmanager.domain.repository.BiometricUnlockStore
import com.eggbusinessmanager.domain.repository.BusinessProfileRepository
import com.eggbusinessmanager.domain.repository.CatalogRepository
import com.eggbusinessmanager.domain.repository.ReportRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.repository.SecretHasher
import com.eggbusinessmanager.domain.repository.IdGenerator
import com.eggbusinessmanager.domain.repository.SettingsRepository
import com.eggbusinessmanager.domain.repository.StartupRepository
import com.eggbusinessmanager.domain.repository.TransactionRunner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Created lazily the first time it is needed. Consumers inject `Provider<EggBusinessDatabase>` and
     * resolve it on the IO dispatcher, after [DatabaseBootstrapper] has prepared storage.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DatabaseKeyProvider,
    ): EggBusinessDatabase = DatabaseFactory.build(context, keyProvider.getOrCreateKey())

    /** Device-only preferences; stored under noBackupFilesDir so they are never part of any backup. */
    @Provides
    @Singleton
    fun provideDeviceDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(File(context.noBackupFilesDir, "datastore"), "device_state.preferences_pb") },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    abstract fun bindStartupRepository(impl: DatabaseBootstrapper): StartupRepository

    @Binds
    abstract fun bindSettingsRepository(impl: RoomSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds
    abstract fun bindAccountRepository(impl: RoomAccountRepository): AccountRepository

    @Binds
    abstract fun bindBusinessProfileRepository(impl: RoomBusinessProfileRepository): BusinessProfileRepository

    @Binds
    abstract fun bindSecretHasher(impl: Pbkdf2SecretHasher): SecretHasher

    @Binds
    abstract fun bindBiometricUnlockStore(impl: BiometricKeyManager): BiometricUnlockStore

    @Binds
    abstract fun bindClock(impl: SystemClock): Clock

    @Binds
    abstract fun bindIdGenerator(impl: UuidIdGenerator): IdGenerator
}

/** Business data: customers, the ledger and the audit trail. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BusinessDataBindingsModule {
    @Binds
    abstract fun bindCustomerRepository(impl: RoomCustomerRepository): CustomerRepository

    @Binds
    abstract fun bindLedgerRepository(impl: RoomLedgerRepository): LedgerRepository

    @Binds
    abstract fun bindAuditRepository(impl: RoomAuditRepository): AuditRepository

    @Binds
    abstract fun bindCatalogRepository(impl: RoomCatalogRepository): CatalogRepository

    @Binds
    abstract fun bindReportRepository(impl: RoomReportRepository): ReportRepository

    @Binds
    abstract fun bindReportExporter(impl: FileReportExporter): ReportExporter

    @Binds
    abstract fun bindBackupEngine(impl: SnapshotBackupEngine): BackupEngine

    @Binds
    abstract fun bindBackupHistory(impl: RoomBackupHistory): BackupHistory

    @Binds
    abstract fun bindDeviceBackupStore(impl: SafDeviceBackupStore): DeviceBackupStore

    @Binds
    abstract fun bindDriveBackupStore(impl: GoogleDriveBackupStore): DriveBackupStore
}
