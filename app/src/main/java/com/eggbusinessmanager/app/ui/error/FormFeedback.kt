package com.eggbusinessmanager.app.ui.error

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.Field
import java.time.Instant

/** How a failed submit is shown: inline field errors, a banner message, or a lockout countdown. */
data class FormFeedback(
    val fieldErrors: Map<Field, UiText> = emptyMap(),
    val message: UiText? = null,
    val lockedUntil: Instant? = null,
) {
    companion object {
        val None = FormFeedback()

        fun from(error: DomainError): FormFeedback = when (error) {
            is DomainError.Validation -> FormFeedback(fieldErrors = ErrorMessages.forFields(error.fields))
            is DomainError.Locked -> FormFeedback(lockedUntil = error.until)
            else -> FormFeedback(message = ErrorMessages.forError(error))
        }
    }

    fun error(field: Field): UiText? = fieldErrors[field]

    /** Clears the inline error of a field the owner just edited. */
    fun without(field: Field): FormFeedback =
        if (field in fieldErrors) copy(fieldErrors = fieldErrors - field) else this
}
