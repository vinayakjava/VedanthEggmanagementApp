package com.eggbusinessmanager.domain.money

/**
 * An exact amount of Indian money in paise (research R3). Never a floating-point value. Arithmetic
 * throws [ArithmeticException] on overflow instead of silently wrapping.
 */
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(Math.addExact(paise, other.paise))

    operator fun minus(other: Money) = Money(Math.subtractExact(paise, other.paise))

    operator fun unaryMinus() = Money(Math.negateExact(paise))

    override fun compareTo(other: Money): Int = paise.compareTo(other.paise)

    val isZero: Boolean get() = paise == 0L
    val isPositive: Boolean get() = paise > 0L
    val isNegative: Boolean get() = paise < 0L

    fun abs(): Money = if (paise < 0) -this else this

    companion object {
        const val PAISE_PER_RUPEE = 100L

        val ZERO = Money(0)

        /** ₹99,99,99,999.99 — the largest amount any single entry may hold (spec § Validation Rules). */
        val MAX_ENTRY = Money(99_999_999_999L)

        fun rupees(rupees: Long, paise: Long = 0) = Money(Math.addExact(Math.multiplyExact(rupees, PAISE_PER_RUPEE), paise))
    }
}

fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)
