package com.eggbusinessmanager.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.data.mapper.toCredentials
import com.eggbusinessmanager.data.mapper.toDomain
import com.eggbusinessmanager.data.mapper.toEntities
import com.eggbusinessmanager.data.mapper.toEntity
import com.eggbusinessmanager.domain.model.account.AccountCredentials
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.HashedSecret
import com.eggbusinessmanager.domain.model.account.LockoutState
import com.eggbusinessmanager.domain.model.account.NewAccount
import com.eggbusinessmanager.domain.model.account.StoredSecurityAnswer
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.BusinessProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider

/**
 * Owner account, security answers and business profile in the encrypted database. Multi-row writes
 * are transactional (Constitution II). The database is resolved lazily on the IO dispatcher.
 */
class RoomAccountRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AccountRepository {

    private val dao get() = database.get().accountDao()

    override fun observeAccountExists(): Flow<Boolean> =
        flow { emitAll(dao.observeExists()) }.distinctUntilChanged().flowOn(io)

    override suspend fun accountExists(): Boolean = withContext(io) { dao.exists() }

    override suspend fun create(account: NewAccount): Boolean = withContext(io) {
        val db = database.get()
        try {
            db.withTransaction {
                if (dao.exists()) return@withTransaction false
                dao.insertAccount(account.toEntity())
                dao.insertSecurityAnswers(account.securityAnswers.toEntities())
                dao.insertProfile(account.profile.toEntity(account.createdAt))
                true
            }
        } catch (_: SQLiteConstraintException) {
            false // a concurrent signup won; nothing from this attempt was committed
        }
    }

    override suspend fun credentials(): AccountCredentials? = withContext(io) {
        database.get().withTransaction {
            dao.account()?.toCredentials(dao.securityAnswers())
        }
    }

    override suspend fun updateLockout(state: LockoutState) = withContext(io) {
        dao.updateLockout(state.failedAttempts, state.lockedUntil?.toEpochMilli(), state.lastSeenAt.toEpochMilli())
    }

    override suspend fun updatePassword(password: HashedSecret, at: Instant) = withContext(io) {
        dao.updatePassword(password.hash, password.salt, password.iterations, at.toEpochMilli())
    }

    override suspend fun updateRecoveryPin(pin: HashedSecret, at: Instant) = withContext(io) {
        dao.updateRecoveryPin(pin.hash, pin.salt, pin.iterations, at.toEpochMilli())
    }

    override suspend fun replaceSecurityAnswers(answers: List<StoredSecurityAnswer>, at: Instant) = withContext(io) {
        database.get().withTransaction {
            dao.deleteSecurityAnswers()
            dao.insertSecurityAnswers(answers.toEntities())
            dao.touchUpdatedAt(at.toEpochMilli())
        }
    }
}

class RoomBusinessProfileRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BusinessProfileRepository {
    override fun observe(): Flow<BusinessProfile?> =
        flow { emitAll(database.get().accountDao().observeProfile()) }
            .map { it?.toDomain() }
            .distinctUntilChanged()
            .flowOn(io)
}
