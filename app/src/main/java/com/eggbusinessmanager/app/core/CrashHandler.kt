package com.eggbusinessmanager.app.core

import com.eggbusinessmanager.domain.logging.AppLogger

/**
 * Last-resort handler: records the crash locally (no crash-reporting SDK — Constitution X) and then
 * hands over to the platform handler. Data stays safe because every write is transactional.
 */
class CrashHandler(
    private val logger: AppLogger,
    private val next: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { logger.error(TAG, throwable) { "Uncaught exception on thread ${thread.name}" } }
        next?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val TAG = "Crash"

        fun install(logger: AppLogger) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous !is CrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(logger, previous))
            }
        }
    }
}
