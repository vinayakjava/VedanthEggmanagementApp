package com.eggbusinessmanager.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.eggbusinessmanager.app.core.CrashHandler
import com.eggbusinessmanager.app.session.AppSessionManager
import com.eggbusinessmanager.domain.logging.AppLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EggBusinessApplication : Application() {

    @Inject
    lateinit var logger: AppLogger

    @Inject
    lateinit var sessionManager: AppSessionManager

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(logger)
        // Background auto-lock (FR-007): the session watches the whole app going to/from background.
        sessionManager.start(ProcessLifecycleOwner.get().lifecycle)
        logger.info(TAG) { "Started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }
    }

    private companion object {
        const val TAG = "App"
    }
}
