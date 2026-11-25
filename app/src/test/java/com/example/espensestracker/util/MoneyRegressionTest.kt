package com.example.espensestracker.util

import com.example.espensestracker.data.*
import org.junit.Assert.*
import org.junit.Test

class MoneyRegressionTest {
    @Test fun validPaiseAmountsOnly() {
        assertEquals(10.01, parseMoney("10.01")!!, 0.0)
        for (input in listOf("-1", "0", "NaN", "Infinity", "1.001", "1e3", "1..0", "1000000000001")) assertNull(parseMoney(input))
    }
    @Test fun incomeDoesNotConsumeOrReduceBudget() {
        val txs = listOf(Transaction(amountPaise = com.example.espensestracker.util.paise(8000.0)), Transaction(amountPaise = com.example.espensestracker.util.paise(15000.0), type = TYPE_CREDIT))
        assertEquals(8000.0, txs.totalSpend(), 0.0)
        assertEquals(-7000.0, txs.totalSpend(true), 0.0)
    }
    @Test fun paiseTotalsDoNotAccumulateFloatingPointError() {
        assertEquals(0.3, listOf(Transaction(amountPaise = com.example.espensestracker.util.paise(0.1)), Transaction(amountPaise = com.example.espensestracker.util.paise(0.2))).totalSpend(), 0.0)
    }
    @Test fun spreadsheetFormulaFieldsAreNeutralized() {
        assertEquals("'=1+1", csvEscape("=1+1"))
        assertEquals("'  +1", csvEscape("  +1"))
        assertEquals("'@SUM(A1)", csvEscape("@SUM(A1)"))
    }
}
