package com.eggbusinessmanager.domain.error

import java.time.Instant

/**
 * Expected, recoverable failures. Domain code never carries user-facing text; the presentation
 * layer maps each error to a localized message (see `ErrorMessages` in :app).
 */
sealed interface DomainError {
    /** Input failed validation; [fields] maps each invalid field to its rule code. */
    data class Validation(val fields: Map<Field, ValidationCode>) : DomainError {
        constructor(field: Field, code: ValidationCode) : this(mapOf(field to code))
    }

    /** Sign-in failed. Deliberately does not say whether the identifier or the password was wrong (FR-005). */
    data object InvalidCredentials : DomainError

    /** The password entered to confirm a sensitive action (unlock, change password, …) is wrong. */
    data object IncorrectPassword : DomainError

    /** Recovery by PIN failed. */
    data object IncorrectRecoveryPin : DomainError

    /** Recovery by security questions failed; never says which answer was wrong (FR-012). */
    data object IncorrectSecurityAnswers : DomainError

    /** Too many failed attempts; try again after [until] (FR-006). */
    data class Locked(val until: Instant) : DomainError

    /** Signup was attempted while an account already exists (FR-001). */
    data object AccountAlreadyExists : DomainError

    /** An account operation was attempted before any account exists. */
    data object NoAccount : DomainError

    data object NotFound : DomainError

    /** Another customer (active or deactivated) already has this mobile number (FR-016). */
    data class DuplicateMobile(val customerId: Long, val customerName: String) : DomainError

    /** The customer has ledger entries, so it can only be deactivated, never deleted (US2-6). */
    data object CustomerHasEntries : DomainError

    /** The entry was deleted (voided) or already replaced by a newer revision; it cannot change (FR-034). */
    data object EntryNotEditable : DomainError

    /** The Piece unit is the base of every conversion and cannot be renamed, resized or deactivated (FR-059). */
    data object UnitLocked : DomainError

    /** At least one active egg variety must always exist (FR-027a). */
    data object LastActiveVariety : DomainError

    /** The device does not have enough free storage to complete the operation. */
    data object LowStorage : DomainError

    /** Local data could not be opened, e.g. the encryption key is unavailable. */
    data object StorageUnavailable : DomainError

    /** Setting a password without the old one is only possible right after a restore (US9-9). */
    data object RestoreProofMissing : DomainError

    /** Anything unexpected. [cause] is for diagnostics only and is never shown to the user. */
    data class Unexpected(val cause: Throwable? = null) : DomainError
}
