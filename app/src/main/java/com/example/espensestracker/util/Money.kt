package com.example.espensestracker.util

import java.math.BigDecimal
import java.math.RoundingMode

// Bound individual amounts so conversion to paise is exact and safely within Long range.
const val MAX_MONEY = 1_000_000_000_000.0
fun parseMoney(text: String): Double? {
    if (!Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(text)) return null
    return text.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it <= BigDecimal.valueOf(MAX_MONEY) }?.toDouble()
}
fun validMoney(value: Double, signed: Boolean = false): Boolean =
    value.isFinite() && (if (signed) kotlin.math.abs(value) <= MAX_MONEY else value > 0 && value <= MAX_MONEY) &&
        BigDecimal.valueOf(value).stripTrailingZeros().scale() <= 2

fun paise(value: Double): Long = BigDecimal.valueOf(value).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
