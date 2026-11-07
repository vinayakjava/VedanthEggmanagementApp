package com.eggbusinessmanager.domain.validation

import java.text.Normalizer
import java.util.Locale

/**
 * Security answers are compared case-insensitively after trimming and collapsing spaces (FR-012),
 * plus Unicode NFKC so the same Hindi text typed on different keyboards matches (research R8).
 * The normalized form is what gets hashed.
 */
object SecurityAnswerNormalizer {
    private val WHITESPACE = Regex("\\s+")

    fun normalize(answer: String): String =
        Normalizer.normalize(answer, Normalizer.Form.NFKC)
            .trim()
            .replace(WHITESPACE, " ")
            .lowercase(Locale.ROOT)
}
