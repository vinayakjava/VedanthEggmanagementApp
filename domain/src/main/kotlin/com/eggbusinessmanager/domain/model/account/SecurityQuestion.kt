package com.eggbusinessmanager.domain.model.account

/**
 * Predefined recovery questions (FR-012: at least 10). The constant names are stored in the database
 * and must never be renamed or removed once released; the question text lives in :app resources.
 */
enum class SecurityQuestion {
    FIRST_SCHOOL,
    BIRTH_TOWN,
    MOTHER_NATIVE_VILLAGE,
    FIRST_TEACHER,
    CHILDHOOD_FRIEND,
    FAVOURITE_DISH,
    FIRST_VEHICLE,
    GRANDFATHER_NAME,
    FIRST_JOB_PLACE,
    CHILDHOOD_NICKNAME,
    FAVOURITE_FILM,
    FIRST_SHOP_LOCATION,
    ;

    companion object {
        /** Exactly this many questions are answered at signup and needed for recovery. */
        const val REQUIRED_COUNT = 3

        fun fromStored(key: String): SecurityQuestion? = entries.firstOrNull { it.name == key }
    }
}

/** One question/answer pair as the owner typed it (plaintext; validated and hashed before storage). */
data class SecurityAnswerInput(
    val question: SecurityQuestion?,
    val answer: String,
) {
    override fun toString(): String = "SecurityAnswerInput(question=$question, answer=***)"
}

/** A stored question with its protected answer. */
data class StoredSecurityAnswer(
    val question: SecurityQuestion,
    val answer: HashedSecret,
)
