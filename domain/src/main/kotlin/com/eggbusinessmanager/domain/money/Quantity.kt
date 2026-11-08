package com.eggbusinessmanager.domain.money

/** A quantity of units in thousandths (research R3): 1.5 trays = 1500. Never a floating-point value. */
@JvmInline
value class QuantityMilli(val value: Long) : Comparable<QuantityMilli> {
    override fun compareTo(other: QuantityMilli): Int = value.compareTo(other.value)

    companion object {
        const val SCALE = 1000L

        /** Decimal places a quantity may have. */
        const val DECIMALS = 3
    }
}

/** Parses typed quantities: digits with at most 3 decimals; commas and spaces are ignored. */
object QuantityParser {
    private val QUANTITY = Regex("""\d{1,9}(\.\d{0,3})?|\.\d{1,3}""")
    private val IGNORED = Regex("""[,\s]""")

    sealed interface Result {
        data class Valid(val quantity: QuantityMilli) : Result
        data object Blank : Result
        data object Invalid : Result
    }

    fun parse(text: String): Result {
        val cleaned = text.replace(IGNORED, "")
        return when {
            cleaned.isEmpty() -> Result.Blank
            !QUANTITY.matches(cleaned) -> Result.Invalid
            else -> Result.Valid(toMilli(cleaned))
        }
    }

    private fun toMilli(text: String): QuantityMilli {
        val whole = text.substringBefore('.').ifEmpty { "0" }.toLong()
        val fraction = text.substringAfter('.', missingDelimiterValue = "").padEnd(QuantityMilli.DECIMALS, '0').toLong()
        return QuantityMilli(whole * QuantityMilli.SCALE + fraction)
    }

    /** Text that parses back to [quantity]: "10", "7.5", "0.125". */
    fun toInput(quantity: QuantityMilli): String {
        val whole = quantity.value / QuantityMilli.SCALE
        val fraction = quantity.value % QuantityMilli.SCALE
        val decimals = fraction.toString().padStart(QuantityMilli.DECIMALS, '0').trimEnd('0')
        return if (fraction == 0L) whole.toString() else "$whole.$decimals"
    }
}

/**
 * The ONLY place where line totals and piece counts are computed (FR-021, BR-008).
 * total = quantity × price, rounded HALF_UP to the paisa only when the exact product has more than
 * 2 decimals; pieces = quantity × unit size, which must be a whole number of eggs.
 */
object TradeMath {

    /** Exact product in thousandths of a paisa, rounded half up to paise. Throws on overflow. */
    fun lineTotal(quantity: QuantityMilli, pricePerUnit: Money): Money {
        require(quantity.value >= 0 && pricePerUnit.paise >= 0) { "Quantity and price must not be negative" }
        val product = Math.multiplyExact(quantity.value, pricePerUnit.paise)
        return Money(Math.addExact(product, QuantityMilli.SCALE / 2) / QuantityMilli.SCALE)
    }

    /** Whole eggs for [quantity] of a unit holding [piecesPerUnit]; null when not a whole number. */
    fun pieces(quantity: QuantityMilli, piecesPerUnit: Int): Long? {
        val product = Math.multiplyExact(quantity.value, piecesPerUnit.toLong())
        return if (product % QuantityMilli.SCALE == 0L) product / QuantityMilli.SCALE else null
    }
}
