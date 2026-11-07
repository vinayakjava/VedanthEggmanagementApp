package com.eggbusinessmanager.domain.money

/**
 * Parses an amount typed by the owner: digits with an optional decimal point and at most 2 decimals.
 * Commas and spaces (Indian grouping such as "1,50,000.50") and a leading ₹ are ignored.
 */
object MoneyParser {
    private val AMOUNT = Regex("""\d{1,12}(\.\d{0,2})?|\.\d{1,2}""")
    private val IGNORED = Regex("""[,\s₹]""")

    sealed interface Result {
        data class Valid(val money: Money) : Result
        data object Blank : Result
        data object Invalid : Result
        data object TooLarge : Result
    }

    fun parse(text: String): Result {
        val cleaned = text.replace(IGNORED, "")
        return when {
            cleaned.isEmpty() -> Result.Blank
            !AMOUNT.matches(cleaned) -> Result.Invalid
            else -> toMoney(cleaned).let { if (it > Money.MAX_ENTRY) Result.TooLarge else Result.Valid(it) }
        }
    }

    private fun toMoney(amount: String): Money {
        val whole = amount.substringBefore('.')
        val fraction = amount.substringAfter('.', missingDelimiterValue = "")
        return Money.rupees(whole.ifEmpty { "0" }.toLong(), fraction.padEnd(2, '0').toLong())
    }

    /** Text that parses back to [money], for pre-filling inputs ("1500", "1500.5" → "1500.50"). */
    fun toInput(money: Money): String {
        val rupees = money.paise / Money.PAISE_PER_RUPEE
        val paise = money.paise % Money.PAISE_PER_RUPEE
        return if (paise == 0L) rupees.toString() else "%d.%02d".format(rupees, paise)
    }
}
