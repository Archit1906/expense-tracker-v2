package com.example.espensestracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpendTest {
    @Test fun spendRulesWithoutNettingCredits() {
        assertEquals(100.0, Transaction(amountPaise = com.example.espensestracker.util.paise(100.0)).spend(false), 0.0)
        assertEquals(-40.0, Transaction(amountPaise = com.example.espensestracker.util.paise(40.0), type = TYPE_REFUND).spend(false), 0.0)
        assertEquals(0.0, Transaction(amountPaise = com.example.espensestracker.util.paise(500.0), category = TRANSFER_CATEGORY, isTransfer = true).spend(false), 0.0)
        assertEquals(0.0, Transaction(amountPaise = com.example.espensestracker.util.paise(500.0), type = TYPE_CREDIT).spend(false), 0.0)
    }

    @Test fun spendRulesWithNettingCredits() {
        assertEquals(100.0, Transaction(amountPaise = com.example.espensestracker.util.paise(100.0)).spend(true), 0.0)
        assertEquals(-500.0, Transaction(amountPaise = com.example.espensestracker.util.paise(500.0), type = TYPE_CREDIT).spend(true), 0.0)
        assertEquals(0.0, Transaction(amountPaise = com.example.espensestracker.util.paise(500.0), type = TYPE_CREDIT, category = TRANSFER_CATEGORY, isTransfer = true).spend(true), 0.0)
    }
}
