package com.eggbusinessmanager.domain.model.account

/**
 * A password, recovery PIN or security answer in protected form (PBKDF2, research R8).
 * The plaintext is never stored. [iterations] is kept per record so the cost can be raised later.
 */
class HashedSecret(
    val hash: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is HashedSecret && iterations == other.iterations &&
            hash.contentEquals(other.hash) && salt.contentEquals(other.salt)

    override fun hashCode(): Int = 31 * (31 * hash.contentHashCode() + salt.contentHashCode()) + iterations

    /** Never print secret material, even hashed. */
    override fun toString(): String = "HashedSecret(iterations=$iterations)"
}
