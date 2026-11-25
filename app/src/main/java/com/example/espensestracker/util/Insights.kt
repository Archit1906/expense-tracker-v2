package com.example.espensestracker.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class MonthRange(val start: Long, val end: Long, val label: String, val days: Int)

/** offset 0 = this month, -1 = last month, ... */
fun monthRange(offset: Int): MonthRange {
    val c = Calendar.getInstance().apply { add(Calendar.MONTH, offset); set(Calendar.DAY_OF_MONTH, 1) }
    val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val start = startOfDay(c.timeInMillis)
    val end = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.MONTH, 1) }.timeInMillis - 1
    return MonthRange(start, end, SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(start)), days)
}

fun dayOfMonth(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)

/** Calendar-based cutoff handles short months and DST changes. */
fun endOfFirstDays(range: MonthRange, days: Int): Long = minOf(range.end,
    Calendar.getInstance().apply { timeInMillis = range.start; add(Calendar.DAY_OF_MONTH, days.coerceAtLeast(1)) }.timeInMillis - 1)

/** Comparable month-to-date cutoff including only the elapsed portion of the last day. */
fun comparableMonthEnd(range: MonthRange, now: Long): Long {
    val current = Calendar.getInstance().apply { timeInMillis = now }
    val day = current.get(Calendar.DAY_OF_MONTH)
    if (day > range.days) return range.end
    return minOf(range.end, Calendar.getInstance().apply {
        timeInMillis = range.start
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, current.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, current.get(Calendar.MINUTE))
        set(Calendar.SECOND, current.get(Calendar.SECOND))
        set(Calendar.MILLISECOND, current.get(Calendar.MILLISECOND))
    }.timeInMillis)
}
