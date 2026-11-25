package com.example.espensestracker.data

import org.junit.Assert.*
import org.junit.Test

class FinancePhase2Test {
    @Test fun transferCategoryCannotChangeFinancialDirection() {
        assertEquals(200L, Transaction(amountPaise = 200, category = TRANSFER_CATEGORY).spendPaise())
        assertEquals(0L, Transaction(amountPaise = 200, category = "Shopping", isTransfer = true).spendPaise())
    }
    @Test fun linkedTransferMovesMoneyExactlyOnce() {
        val a = Wallet("a", "Bank", "Bank account", 100_000)
        val b = Wallet("b", "Cash", "Cash", 20_000)
        val rows = listOf(Transaction(amountPaise = 25_050, isTransfer = true, walletId = "a", destinationWalletId = "b"))
        assertEquals(74_950L, a.ledgerBalance(rows))
        assertEquals(45_050L, b.ledgerBalance(rows))
        assertEquals(120_000L, a.ledgerBalance(rows) + b.ledgerBalance(rows))
        assertEquals(0L, rows.totalSpendPaise())
    }
    @Test fun cashFlowSeparatesIncomeAndReimbursements() {
        val rows = listOf(Transaction(amountPaise = 150_000, type = TYPE_CREDIT), Transaction(amountPaise = 50_000), Transaction(amountPaise = 5000, type = TYPE_REFUND), Transaction(amountPaise = 10000, type = TYPE_REIMBURSEMENT), Transaction(amountPaise = 2000, type = TYPE_CREDIT, isTransfer = true))
        assertEquals(150_000L, rows.incomePaise())
        assertEquals(50_000L, rows.grossExpensePaise())
        assertEquals(5000L, rows.refundPaise())
        assertEquals(10000L, rows.reimbursementPaise())
        assertEquals(35000L, rows.totalSpendPaise())
        assertEquals(-115000L, rows.totalSpendPaise(true))
    }
    @Test fun cardPurchaseAndPaymentDoNotDoubleCount() {
        val card = Wallet("card", "Card", "Credit card", 0)
        val bank = Wallet("bank", "Bank", "Bank account", 100_000)
        val rows = listOf(Transaction(amountPaise = 20_000, walletId = "card"), Transaction(amountPaise = 20_000, isTransfer = true, walletId = "bank", destinationWalletId = "card"))
        assertEquals(0L, card.ledgerBalance(rows))
        assertEquals(80_000L, bank.ledgerBalance(rows))
        assertEquals(20_000L, rows.totalSpendPaise())
    }
    @Test fun repeatedPaiseAreExact() {
        assertEquals(10001L, List(10001) { Transaction(amountPaise = 1) }.totalSpendPaise())
    }
    @Test fun validMonthKeysOnly() {
        assertTrue(validMonthKey("2026-11"))
        assertFalse(validMonthKey("2026-13"))
        assertFalse(validMonthKey("2026-1"))
    }
}
