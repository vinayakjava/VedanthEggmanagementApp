package com.eggbusinessmanager.app.di

import com.eggbusinessmanager.app.BuildConfig
import com.eggbusinessmanager.app.feature.backup.GoogleDriveAuthorizer
import com.eggbusinessmanager.app.session.AppSessionManager
import com.eggbusinessmanager.data.logging.AndroidAppLogger
import com.eggbusinessmanager.domain.backup.DriveAuthorizer
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.repository.SessionManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Debug builds log everything; release builds log warnings and errors only. */
    @Provides
    @Singleton
    fun provideAppLogger(): AppLogger = AndroidAppLogger(
        minLevel = if (BuildConfig.DEBUG) AndroidAppLogger.Level.DEBUG else AndroidAppLogger.Level.WARN,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    /** The session lives in :app because it follows the Android process lifecycle. */
    @Binds
    abstract fun bindSessionManager(impl: AppSessionManager): SessionManager

    /** Google authorization needs Play services and an Activity for consent, so it lives in :app. */
    @Binds
    abstract fun bindDriveAuthorizer(impl: GoogleDriveAuthorizer): DriveAuthorizer
}
