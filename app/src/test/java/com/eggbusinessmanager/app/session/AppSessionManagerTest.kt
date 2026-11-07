package com.eggbusinessmanager.app.session

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.eggbusinessmanager.domain.model.ThemeMode
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.domain.repository.SettingsRepository
import com.eggbusinessmanager.testing.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSessionManagerTest {

    private val minutes = MutableStateFlow(SettingsRepository.DEFAULT_AUTO_LOCK_MINUTES)
    private val settings = object : SettingsRepository {
        override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override fun observeAutoLockMinutes(): Flow<Int> = minutes
    }
    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = error("not used")
    }

    private fun TestScope.session() =
        AppSessionManager(settings, RecordingLogger(), backgroundScope) { testScheduler.currentTime }

    private val minute = 60_000L

    @Test
    fun `a new process starts signed out`() = runTest {
        assertThat(session().state.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `locks after 5 idle minutes by default`() = runTest {
        val session = session()
        session.authenticated()
        runCurrent()

        advanceTimeBy(4 * minute)
        assertThat(session.state.value).isEqualTo(SessionState.Unlocked())

        advanceTimeBy(minute + AppSessionManager.CHECK_INTERVAL_MS)
        assertThat(session.state.value).isEqualTo(SessionState.Locked)
    }

    @Test
    fun `activity keeps the session unlocked`() = runTest {
        val session = session()
        session.authenticated()
        runCurrent()

        repeat(10) {
            advanceTimeBy(2 * minute)
            session.touch()
        }

        assertThat(session.state.value).isEqualTo(SessionState.Unlocked())
    }

    @Test
    fun `the auto-lock setting is honoured`() = runTest {
        minutes.value = 1
        val session = session()
        session.authenticated()
        runCurrent()

        advanceTimeBy(minute + AppSessionManager.CHECK_INTERVAL_MS)

        assertThat(session.state.value).isEqualTo(SessionState.Locked)
    }

    @Test
    fun `returning from background after the timeout locks, a short trip does not`() = runTest {
        val session = session()
        session.authenticated()
        runCurrent()

        session.onStop(owner)
        advanceTimeBy(minute)
        session.onStart(owner)
        assertThat(session.state.value).isEqualTo(SessionState.Unlocked())

        session.onStop(owner)
        advanceTimeBy(5 * minute)
        session.onStart(owner)
        assertThat(session.state.value).isEqualTo(SessionState.Locked)
    }

    @Test
    fun `unlocking again restarts the timer`() = runTest {
        val session = session()
        session.authenticated()
        runCurrent()
        session.lock()

        session.authenticated()
        advanceTimeBy(3 * minute)

        assertThat(session.state.value).isEqualTo(SessionState.Unlocked())
    }

    @Test
    fun `sign out wins over locking and is never undone by the timer`() = runTest {
        val session = session()
        session.authenticated()
        runCurrent()

        session.signOut()
        advanceTimeBy(30 * minute)
        session.lock()

        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `recovery review flag is set by recovery and cleared on acknowledgement`() = runTest {
        val session = session()
        session.authenticated(afterRecovery = true)
        assertThat(session.state.value).isEqualTo(SessionState.Unlocked(reviewRecovery = true))

        session.recoveryReviewed()

        assertThat(session.state.value).isEqualTo(SessionState.Unlocked(reviewRecovery = false))
    }

    @Test
    fun `a failing settings source falls back to the default timeout`() = runTest {
        val broken = object : SettingsRepository by settings {
            override fun observeAutoLockMinutes(): Flow<Int> = flow { error("db closed") }
        }
        val session = AppSessionManager(broken, RecordingLogger(), backgroundScope) { testScheduler.currentTime }
        session.authenticated()
        runCurrent()

        advanceTimeBy(5 * minute + AppSessionManager.CHECK_INTERVAL_MS)

        assertThat(session.state.value).isEqualTo(SessionState.Locked)
    }
}
