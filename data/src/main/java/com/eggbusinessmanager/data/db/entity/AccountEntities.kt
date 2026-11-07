@file:Suppress("ArrayInDataClass") // Room entities are compared by id, never by value.

package com.eggbusinessmanager.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** The single owner account (data-model.md § owner_account). Always id = 1. */
@Entity(
    tableName = "owner_account",
    indices = [
        Index(value = ["mobile"], unique = true),
        Index(value = ["email"], unique = true),
    ],
)
data class OwnerAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Long = SINGLETON_ID,
    @ColumnInfo(name = "mobile") val mobile: String,
    @ColumnInfo(name = "email") val email: String?,
    @ColumnInfo(name = "password_hash") val passwordHash: ByteArray,
    @ColumnInfo(name = "password_salt") val passwordSalt: ByteArray,
    @ColumnInfo(name = "password_iterations") val passwordIterations: Int,
    @ColumnInfo(name = "recovery_pin_hash") val recoveryPinHash: ByteArray,
    @ColumnInfo(name = "recovery_pin_salt") val recoveryPinSalt: ByteArray,
    @ColumnInfo(name = "recovery_pin_iterations") val recoveryPinIterations: Int,
    @ColumnInfo(name = "failed_attempts", defaultValue = "0") val failedAttempts: Int = 0,
    @ColumnInfo(name = "lockout_until_at") val lockoutUntilAt: Long? = null,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "must_change_password", defaultValue = "0") val mustChangePassword: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

/** One of the 3 security answers (data-model.md § security_answer). */
@Entity(
    tableName = "security_answer",
    foreignKeys = [
        ForeignKey(
            entity = OwnerAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["account_id", "slot"], unique = true),
        Index(value = ["account_id", "question_key"], unique = true),
    ],
)
data class SecurityAnswerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long = OwnerAccountEntity.SINGLETON_ID,
    @ColumnInfo(name = "slot") val slot: Int,
    @ColumnInfo(name = "question_key") val questionKey: String,
    @ColumnInfo(name = "answer_hash") val answerHash: ByteArray,
    @ColumnInfo(name = "answer_salt") val answerSalt: ByteArray,
    @ColumnInfo(name = "answer_iterations") val answerIterations: Int,
)

/** Shop details (data-model.md § business_profile). Always id = 1. */
@Entity(tableName = "business_profile")
data class BusinessProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Long = OwnerAccountEntity.SINGLETON_ID,
    @ColumnInfo(name = "shop_name") val shopName: String,
    @ColumnInfo(name = "owner_name") val ownerName: String,
    @ColumnInfo(name = "address") val address: String?,
    @ColumnInfo(name = "city") val city: String,
    @ColumnInfo(name = "state_code") val stateCode: String,
    @ColumnInfo(name = "pin_code") val pinCode: String,
    @ColumnInfo(name = "phone") val phone: String? = null,
    @ColumnInfo(name = "gst_number") val gstNumber: String? = null,
    @ColumnInfo(name = "statement_footer") val statementFooter: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
