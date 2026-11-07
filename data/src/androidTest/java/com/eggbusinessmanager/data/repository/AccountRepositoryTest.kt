package com.eggbusinessmanager.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.HashedSecret
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.model.account.LockoutState
import com.eggbusinessmanager.domain.model.account.NewAccount
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.model.account.StoredSecurityAnswer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Provider

/** Real SQLCipher database: singleton account, atomic signup, lockout persistence across reopen. */
@RunWith(AndroidJUnit4::class)
class AccountRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "account-repo-test.db"
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private lateinit var database: EggBusinessDatabase
    private lateinit var repository: RoomAccountRepository
    private lateinit var profiles: RoomBusinessProfileRepository
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    @Before
    fun setUp() {
        context.deleteDatabase(fileName)
        open()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(fileName)
    }

    private fun open() {
        database = DatabaseFactory.build(context, key, fileName)
        repository = RoomAccountRepository(Provider { database }, Dispatchers.IO)
        profiles = RoomBusinessProfileRepository(Provider { database }, Dispatchers.IO)
    }

    private fun secret(tag: Int) = HashedSecret(ByteArray(32) { tag.toByte() }, ByteArray(16) { (tag + 1).toByte() }, 600_000)

    private fun newAccount(mobile: String = "9876543210") = NewAccount(
        mobile = mobile,
        email = "owner@example.com",
        password = secret(1),
        recoveryPin = secret(2),
        securityAnswers = listOf(SecurityQuestion.FIRST_SCHOOL, SecurityQuestion.BIRTH_TOWN, SecurityQuestion.FAVOURITE_DISH)
            .mapIndexed { i, q -> StoredSecurityAnswer(q, secret(10 + i)) },
        profile = BusinessProfile("Patil Egg Centre", "Ramesh Patil", null, "Nashik", IndianState.MAHARASHTRA, "422001"),
        createdAt = now,
    )

    @Test
    fun createStoresAccountAnswersAndProfile() = runTest {
        assertThat(repository.accountExists()).isFalse()

        assertThat(repository.create(newAccount())).isTrue()

        assertThat(repository.observeAccountExists().first()).isTrue()
        val credentials = checkNotNull(repository.credentials())
        assertThat(credentials.mobile).isEqualTo("9876543210")
        assertThat(credentials.password).isEqualTo(secret(1))
        assertThat(credentials.recoveryPin).isEqualTo(secret(2))
        assertThat(credentials.securityAnswers.map { it.question })
            .containsExactly(SecurityQuestion.FIRST_SCHOOL, SecurityQuestion.BIRTH_TOWN, SecurityQuestion.FAVOURITE_DISH).inOrder()
        assertThat(credentials.lockout).isEqualTo(LockoutState(0, null, now))
        assertThat(profiles.observe().first()?.state).isEqualTo(IndianState.MAHARASHTRA)
        assertThat(profiles.observe().first()?.pinCode).isEqualTo("422001")
    }

    @Test
    fun onlyOneAccountPerInstallation() = runTest {
        repository.create(newAccount())

        assertThat(repository.create(newAccount(mobile = "9123456789"))).isFalse()
        assertThat(repository.credentials()?.mobile).isEqualTo("9876543210")
    }

    @Test
    fun lockoutSurvivesReopeningTheDatabase() = runTest {
        repository.create(newAccount())
        val lock = LockoutState(failedAttempts = 5, lockedUntil = now.plusSeconds(30), lastSeenAt = now)
        repository.updateLockout(lock)

        database.close()
        open()

        assertThat(repository.credentials()?.lockout).isEqualTo(lock)
    }

    @Test
    fun passwordPinAndAnswersCanBeReplaced() = runTest {
        repository.create(newAccount())
        val later = now.plusSeconds(60)

        repository.updatePassword(secret(20), later)
        repository.updateRecoveryPin(secret(21), later)
        repository.replaceSecurityAnswers(
            listOf(SecurityQuestion.FIRST_VEHICLE, SecurityQuestion.BIRTH_TOWN, SecurityQuestion.FAVOURITE_FILM)
                .mapIndexed { i, q -> StoredSecurityAnswer(q, secret(30 + i)) },
            later,
        )

        val credentials = checkNotNull(repository.credentials())
        assertThat(credentials.password).isEqualTo(secret(20))
        assertThat(credentials.recoveryPin).isEqualTo(secret(21))
        assertThat(credentials.securityAnswers.map { it.question })
            .containsExactly(SecurityQuestion.FIRST_VEHICLE, SecurityQuestion.BIRTH_TOWN, SecurityQuestion.FAVOURITE_FILM).inOrder()
    }
}
