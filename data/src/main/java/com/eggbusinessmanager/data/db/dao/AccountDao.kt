package com.eggbusinessmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eggbusinessmanager.data.db.entity.BusinessProfileEntity
import com.eggbusinessmanager.data.db.entity.OwnerAccountEntity
import com.eggbusinessmanager.data.db.entity.SecurityAnswerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT EXISTS(SELECT 1 FROM owner_account)")
    fun observeExists(): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM owner_account)")
    suspend fun exists(): Boolean

    @Query("SELECT * FROM owner_account WHERE id = 1")
    suspend fun account(): OwnerAccountEntity?

    @Query("SELECT * FROM security_answer WHERE account_id = 1 ORDER BY slot")
    suspend fun securityAnswers(): List<SecurityAnswerEntity>

    /** Aborts (throws) if an account already exists: the singleton primary key and unique columns. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: OwnerAccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSecurityAnswers(answers: List<SecurityAnswerEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: BusinessProfileEntity)

    @Query("DELETE FROM security_answer WHERE account_id = 1")
    suspend fun deleteSecurityAnswers()

    @Query(
        "UPDATE owner_account SET failed_attempts = :failedAttempts, lockout_until_at = :lockoutUntilAt, " +
            "last_seen_at = :lastSeenAt WHERE id = 1",
    )
    suspend fun updateLockout(failedAttempts: Int, lockoutUntilAt: Long?, lastSeenAt: Long)

    @Query(
        "UPDATE owner_account SET password_hash = :hash, password_salt = :salt, password_iterations = :iterations, " +
            "must_change_password = 0, updated_at = :at WHERE id = 1",
    )
    suspend fun updatePassword(hash: ByteArray, salt: ByteArray, iterations: Int, at: Long)

    @Query(
        "UPDATE owner_account SET recovery_pin_hash = :hash, recovery_pin_salt = :salt, " +
            "recovery_pin_iterations = :iterations, updated_at = :at WHERE id = 1",
    )
    suspend fun updateRecoveryPin(hash: ByteArray, salt: ByteArray, iterations: Int, at: Long)

    @Query("UPDATE owner_account SET updated_at = :at WHERE id = 1")
    suspend fun touchUpdatedAt(at: Long)

    @Query("UPDATE owner_account SET mobile = :mobile, email = :email, updated_at = :at WHERE id = 1")
    suspend fun updateContact(mobile: String, email: String?, at: Long)

    @Query(
        "UPDATE business_profile SET shop_name = :shopName, owner_name = :ownerName, address = :address, city = :city, " +
            "state_code = :stateCode, pin_code = :pinCode, updated_at = :at WHERE id = 1",
    )
    suspend fun updateProfile(
        shopName: String,
        ownerName: String,
        address: String?,
        city: String,
        stateCode: String,
        pinCode: String,
        at: Long,
    )

    @Query("SELECT * FROM business_profile WHERE id = 1")
    fun observeProfile(): Flow<BusinessProfileEntity?>
}
