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
import com.eggbusinessmanager.data.repository.RoomBusinessProfileRepository
import com.eggbusinessmanager.data.repository.RoomSettingsRepository
import com.eggbusinessmanager.data.security.BiometricKeyManager
import com.eggbusinessmanager.data.security.Pbkdf2SecretHasher
import com.eggbusinessmanager.data.security.DatabaseKeyProvider
import com.eggbusinessmanager.data.time.SystemClock
import com.eggbusinessmanager.data.time.UuidIdGenerator
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.BiometricUnlockStore
import com.eggbusinessmanager.domain.repository.BusinessProfileRepository
import com.eggbusinessmanager.domain.repository.Clock
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
