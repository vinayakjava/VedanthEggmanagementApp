package com.eggbusinessmanager.data.mapper

import com.eggbusinessmanager.data.db.entity.BusinessProfileEntity
import com.eggbusinessmanager.data.db.entity.OwnerAccountEntity
import com.eggbusinessmanager.data.db.entity.SecurityAnswerEntity
import com.eggbusinessmanager.domain.model.account.AccountCredentials
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.HashedSecret
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.model.account.LockoutState
import com.eggbusinessmanager.domain.model.account.NewAccount
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.model.account.StoredSecurityAnswer
import java.time.Instant

internal fun NewAccount.toEntity(): OwnerAccountEntity {
    val at = createdAt.toEpochMilli()
    return OwnerAccountEntity(
        mobile = mobile,
        email = email,
        passwordHash = password.hash,
        passwordSalt = password.salt,
        passwordIterations = password.iterations,
        recoveryPinHash = recoveryPin.hash,
        recoveryPinSalt = recoveryPin.salt,
        recoveryPinIterations = recoveryPin.iterations,
        lastSeenAt = at,
        createdAt = at,
        updatedAt = at,
    )
}

internal fun List<StoredSecurityAnswer>.toEntities(): List<SecurityAnswerEntity> = mapIndexed { index, item ->
    SecurityAnswerEntity(
        slot = index + 1,
        questionKey = item.question.name,
        answerHash = item.answer.hash,
        answerSalt = item.answer.salt,
        answerIterations = item.answer.iterations,
    )
}

internal fun BusinessProfile.toEntity(updatedAt: Instant) = BusinessProfileEntity(
    shopName = shopName,
    ownerName = ownerName,
    address = address,
    city = city,
    stateCode = state.name,
    pinCode = pinCode,
    updatedAt = updatedAt.toEpochMilli(),
)

/** Null when the stored state code is unknown (never expected; constants are never renamed). */
internal fun BusinessProfileEntity.toDomain(): BusinessProfile? {
    val state = IndianState.fromStored(stateCode) ?: return null
    return BusinessProfile(shopName, ownerName, address, city, state, pinCode)
}

internal fun OwnerAccountEntity.toCredentials(answers: List<SecurityAnswerEntity>) = AccountCredentials(
    mobile = mobile,
    email = email,
    password = HashedSecret(passwordHash, passwordSalt, passwordIterations),
    recoveryPin = HashedSecret(recoveryPinHash, recoveryPinSalt, recoveryPinIterations),
    securityAnswers = answers.mapNotNull { row ->
        SecurityQuestion.fromStored(row.questionKey)?.let { question ->
            StoredSecurityAnswer(question, HashedSecret(row.answerHash, row.answerSalt, row.answerIterations))
        }
    },
    lockout = LockoutState(
        failedAttempts = failedAttempts,
        lockedUntil = lockoutUntilAt?.let(Instant::ofEpochMilli),
        lastSeenAt = Instant.ofEpochMilli(lastSeenAt),
    ),
)
