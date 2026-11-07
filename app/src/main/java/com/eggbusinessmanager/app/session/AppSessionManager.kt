package com.eggbusinessmanager.app.session

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.repository.SessionManager
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory session (research R8): starts SignedOut on every process start, and once unlocked locks
 * itself after the `auto_lock_minutes` setting of inactivity (no touches) or time in background
 * (FR-007, US1-8). Uses the monotonic elapsed-realtime clock, so changing the wall clock cannot
 * keep the app unlocked.
 */
@Singleton
class AppSessionManager internal constructor(
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
) : SessionManager, DefaultLifecycleObserver {

    @Inject
    constructor(settings: SettingsRepository, logger: AppLogger) :
        this(settings, logger, CoroutineScope(SupervisorJob() + Dispatchers.Default), SystemClock::elapsedRealtime)

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile private var lastActivityMs = 0L

    @Volatile private var backgroundedAtMs: Long? = null

    @Volatile private var timeoutMs = SettingsRepository.DEFAULT_AUTO_LOCK_MINUTES * MS_PER_MINUTE

    private var settingsJob: Job? = null
    private var watchdog: Job? = null

    /** Watches app foreground/background; call once with `ProcessLifecycleOwner.get().lifecycle`. */
    fun start(processLifecycle: Lifecycle) {
        processLifecycle.addObserver(this)
    }

    override fun authenticated(afterRecovery: Boolean) {
        lastActivityMs = elapsedMs()
        _state.value = SessionState.Unlocked(reviewRecovery = afterRecovery)
        startTimers()
    }

    override fun lock() {
        _state.update { current ->
            if (current is SessionState.Unlocked) {
                logger.info(TAG) { "Auto-lock" }
                SessionState.Locked
            } else {
                current
            }
        }
    }

    override fun signOut() {
        _state.value = SessionState.SignedOut
        watchdog?.cancel()
        watchdog = null
    }

    override fun touch() {
        lastActivityMs = elapsedMs()
    }

    override fun recoveryReviewed() {
        _state.update { current -> (current as? SessionState.Unlocked)?.copy(reviewRecovery = false) ?: current }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAtMs = elapsedMs()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAtMs ?: return
        backgroundedAtMs = null
        if (elapsedMs() - since >= timeoutMs) lock() else touch()
    }

    private fun startTimers() {
        if (settingsJob == null) {
            // Read only after authentication, when encrypted storage is known to be ready.
            settingsJob = scope.launch {
                settings.observeAutoLockMinutes()
                    .catch { logger.warn(TAG, it) { "Auto-lock setting unavailable; using default" } }
                    .collect { minutes -> timeoutMs = minutes * MS_PER_MINUTE }
            }
        }
        if (watchdog?.isActive != true) {
            watchdog = scope.launch {
                while (isActive) {
                    delay(CHECK_INTERVAL_MS)
                    if (_state.value is SessionState.Unlocked && elapsedMs() - lastActivityMs >= timeoutMs) lock()
                }
            }
        }
    }

    internal companion object {
        const val TAG = "Session"
        const val MS_PER_MINUTE = 60_000L
        const val CHECK_INTERVAL_MS = 5_000L
    }
}
