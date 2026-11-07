package com.eggbusinessmanager.domain.validation

import java.text.Normalizer
import java.util.Locale

/**
 * The `*_normalized` search form (data-model.md § Conventions): trimmed, whitespace collapsed, NFKC,
 * lower case with `Locale.ROOT`. Names are compared and searched in this form.
 */
object SearchKey {
    private val WHITESPACE = Regex("\\s+")

    fun of(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    /** Digits only, for matching typed numbers against stored mobile numbers. */
    fun digits(text: String): String = text.filter(Char::isDigit)
}
