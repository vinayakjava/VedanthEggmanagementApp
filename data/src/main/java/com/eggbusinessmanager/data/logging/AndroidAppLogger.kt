package com.eggbusinessmanager.data.logging

import android.util.Log
import com.eggbusinessmanager.domain.logging.AppLogger

/**
 * [AppLogger] backed by Logcat. Release builds use [Level.WARN] as the minimum, and R8 removes
 * `Log.v/d/i` calls entirely (app/proguard-rules.pro). No crash-reporting SDK is used (Constitution X).
 */
class AndroidAppLogger(private val minLevel: Level) : AppLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    override fun debug(tag: String, message: () -> String) {
        if (enabled(Level.DEBUG)) Log.d(prefixed(tag), message())
    }

    override fun info(tag: String, message: () -> String) {
        if (enabled(Level.INFO)) Log.i(prefixed(tag), message())
    }

    override fun warn(tag: String, throwable: Throwable?, message: () -> String) {
        if (enabled(Level.WARN)) Log.w(prefixed(tag), message(), throwable)
    }

    override fun error(tag: String, throwable: Throwable?, message: () -> String) {
        if (enabled(Level.ERROR)) Log.e(prefixed(tag), message(), throwable)
    }

    private fun enabled(level: Level) = level.ordinal >= minLevel.ordinal

    private fun prefixed(tag: String) = "EBM/$tag".take(MAX_TAG_LENGTH)

    private companion object {
        const val MAX_TAG_LENGTH = 23
    }
}
