package com.eggbusinessmanager.domain.model.account

import java.time.Instant

/** Shop details shown on documents and in the app header (data-model.md § business_profile). */
data class BusinessProfile(
    val shopName: String,
    val ownerName: String,
    val address: String?,
    val city: String,
    val state: IndianState,
    val pinCode: String,
)

/** Everything the owner types on the signup screens, before validation. */
data class SignupForm(
    val ownerName: String = "",
    val mobile: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val shopName: String = "",
    val address: String = "",
    val city: String = "",
    val state: IndianState? = null,
    val pinCode: String = "",
    val recoveryPin: String = "",
    val confirmRecoveryPin: String = "",
    val securityAnswers: List<SecurityAnswerInput> = List(SecurityQuestion.REQUIRED_COUNT) { SecurityAnswerInput(null, "") },
) {
    /** Secrets must never reach logs or crash reports. */
    override fun toString(): String = "SignupForm(mobile=***, shopName=$shopName)"
}

/** A validated signup, normalized and ready to be hashed and stored. */
data class ValidSignup(
    val mobile: String,
    val email: String?,
    val password: String,
    val recoveryPin: String,
    val profile: BusinessProfile,
    val securityAnswers: List<Pair<SecurityQuestion, String>>,
) {
    override fun toString(): String = "ValidSignup(shopName=${profile.shopName})"
}

/** What the repository stores at signup: identifiers, protected secrets and the shop profile. */
data class NewAccount(
    val mobile: String,
    val email: String?,
    val password: HashedSecret,
    val recoveryPin: HashedSecret,
    val securityAnswers: List<StoredSecurityAnswer>,
    val profile: BusinessProfile,
    val createdAt: Instant,
)

/** Failed-attempt state shared by sign-in, unlock, password change and recovery (FR-006). */
data class LockoutState(
    val failedAttempts: Int,
    val lockedUntil: Instant?,
    /** Latest time seen; guards against the device clock being moved backwards (research R8). */
    val lastSeenAt: Instant,
)

/** The stored credentials needed to verify the owner. */
data class AccountCredentials(
    val mobile: String,
    val email: String?,
    val password: HashedSecret,
    val recoveryPin: HashedSecret,
    val securityAnswers: List<StoredSecurityAnswer>,
    val lockout: LockoutState,
)
