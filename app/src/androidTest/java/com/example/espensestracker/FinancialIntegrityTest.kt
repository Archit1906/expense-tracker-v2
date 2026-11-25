package com.example.espensestracker

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.espensestracker.data.*
import com.example.espensestracker.sms.SmsProcessor
import com.example.espensestracker.backup.Backup
import com.example.espensestracker.backup.BackupData
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialIntegrityTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sender = "VM-HDFCBK"
    private val ts = 1_700_000_000_000L

    @Test fun distinctSameAmountPaymentsAndConcurrentExactDuplicate() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val p = SmsProcessor(ctx, db)
            val a = "Rs.50 debited from A/c XX1234 to swiggy@upi"
            val b = "Rs.50 debited from A/c XX1234 to zomato@upi"
            coroutineScope { List(8) { async { p.process(sender, a, ts) } }.forEach { it.await() } }
            p.process(sender, b, ts + 60_000)
            assertEquals(2, db.transactionDao().getAllOnce().size)
            val first = db.transactionDao().getAllOnce().first { it.rawSms == a }
            p.delete(first)
            p.process(sender, a, ts)
            assertEquals(1, db.transactionDao().getAllOnce().size)
        } finally { db.close() }
    }

    @Test fun queueUsesReferenceDedupAndRetainsCreditType() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val p = SmsProcessor(ctx, db)
            p.process(sender, "Rs.500 debited from A/c XX1234 to swiggy@upi. Ref 123456789012", ts)
            p.process(sender, "Rs.500 debited from A/c XX1234 to merchant123@upi. Ref 123456789012", ts + 5000)
            val queued = db.unparsedSmsDao().pendingAll().single()
            p.assign(queued, "Shopping", "Shop")
            assertEquals(1, db.transactionDao().getAllOnce().size)
            p.process("XX-FAKEID", "Rs.500 credited to bank A/c XX1234.", ts + 10000)
            val credit = db.unparsedSmsDao().pendingAll().single()
            p.assign(credit, "Other", "Confirmed sender")
            assertEquals(TYPE_CREDIT, db.transactionDao().getAllOnce().first().type)
        } finally { db.close() }
    }

    @Test fun transfersAndHistoricalCategoriesSurviveRules() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val p = SmsProcessor(ctx, db)
            p.process(sender, "Rs.50 debited from A/c XX1234 to swiggy@upi", ts)
            p.remember("swiggy@upi", "Shopping", "Swiggy")
            assertEquals("Food & Dining", db.transactionDao().getAllOnce().single().category)
            p.process(sender, "Rs.5000 debited from A/c XX1234 to swiggy@upi towards credit card bill", ts + 5000)
            assertEquals(TRANSFER_CATEGORY, db.transactionDao().getAllOnce().first().category)
        } finally { db.close() }
    }

    @Test fun olderBalanceCannotOverwriteNewerBalance() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val p = SmsProcessor(ctx, db)
            p.process(sender, "A/c XX1234 Avl Bal Rs.900", ts + 1000)
            p.process(sender, "A/c XX1234 Avl Bal Rs.100", ts)
            assertEquals(900.0, db.accountBalanceDao().getAllOnce().single().amount, 0.0)
        } finally { db.close() }
    }

    @Test fun backupRoundTripAndValidation() {
        val data = BackupData(listOf(Transaction(amountPaise = com.example.espensestracker.util.paise(10.25), timestamp = ts)), emptyList(), emptyList(), emptyList(), 1000, false, listOf(IgnoredSms("hash")))
        val restored = Backup.parse(Backup.toBytes(data, "test-password"), "test-password")
        assertEquals(data.transactions, restored.transactions)
        assertEquals(data.ignored, restored.ignored)
        for (bad in listOf("{}", "{\"format\":99}", "{\"format\":2,\"transactions\":[]}")) {
            try { Backup.parse(bad.toByteArray(), ""); fail("invalid backup accepted") } catch (_: Exception) { }
        }
    }

    @Test fun phase2WalletBudgetsAndBackupRoundTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val f = db.financeDao()
            val bank = Wallet("bank", "Bank", "Bank account", 100_000)
            val cash = Wallet("cash", "Cash", "Cash", 0)
            f.saveWallet(bank); f.saveWallet(cash)
            f.insertCategory(CustomCategory("College"))
            f.saveBudget(MonthlyBudget("2026-11", "College", 50_000))
            val tx = Transaction(amountPaise = 10_025, isTransfer = true, walletId = bank.id, destinationWalletId = cash.id, timestamp = ts)
            db.transactionDao().insertIfNew(tx)
            val d = BackupData(db.transactionDao().getAllOnce(), emptyList(), emptyList(), emptyList(), 1000, false, wallets = f.allWallets(), categories = f.allCategories(), budgets = f.allBudgets())
            val restored = Backup.parse(Backup.toBytes(d, "pw"), "pw")
            assertEquals(d.transactions, restored.transactions)
            assertEquals(d.wallets, restored.wallets)
            assertEquals(d.categories, restored.categories)
            assertEquals(d.budgets, restored.budgets)
            assertEquals(89_975L, bank.ledgerBalance(restored.transactions))
            assertEquals(10_025L, cash.ledgerBalance(restored.transactions))
        } finally { db.close() }
    }

    @Test fun migration4To6RetainsLegacyRows() = runBlocking {
        val name = "migration-4-5-test.db"
        ctx.deleteDatabase(name)
        ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount REAL NOT NULL, type TEXT NOT NULL, merchant TEXT, category TEXT NOT NULL, account TEXT, refNo TEXT, timestamp INTEGER NOT NULL, note TEXT, source TEXT NOT NULL, rawSms TEXT, sender TEXT, smsHash TEXT, taxDeductible INTEGER NOT NULL, merchantKey TEXT)")
            old.execSQL("CREATE UNIQUE INDEX index_transactions_smsHash ON transactions(smsHash)")
            old.execSQL("CREATE TABLE unparsed_sms (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, amount REAL NOT NULL, account TEXT, refNo TEXT, reason TEXT NOT NULL, smsHash TEXT NOT NULL, dismissed INTEGER NOT NULL, merchantKey TEXT)")
            old.execSQL("CREATE UNIQUE INDEX index_unparsed_sms_smsHash ON unparsed_sms(smsHash)")
            old.execSQL("CREATE TABLE merchant_rules (merchantKey TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL, displayName TEXT)")
            old.execSQL("CREATE TABLE account_balances (accountKey TEXT NOT NULL PRIMARY KEY, bank TEXT NOT NULL, last4 TEXT NOT NULL, kind TEXT NOT NULL, amount REAL NOT NULL, timestamp INTEGER NOT NULL)")
            old.execSQL("INSERT INTO transactions (amount,type,category,timestamp,source,taxDeductible) VALUES (10.25,'DEBIT','Other',1700000000000,'MANUAL',0)")
            old.execSQL("INSERT INTO unparsed_sms (sender,body,timestamp,amount,reason,smsHash,dismissed) VALUES ('BANK','sample',1700000000000,12.5,'Review','legacy',0)")
            old.version = 4
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).build()
        try {
            assertEquals(1025L, db.transactionDao().getAllOnce().single().amountPaise)
            assertEquals(1250L, db.unparsedSmsDao().pendingAll().single().amountPaise)
            assertEquals(TYPE_DEBIT, db.unparsedSmsDao().pendingAll().single().type)
            assertTrue(db.ignoredSmsDao().all().isEmpty())
        } finally { db.close(); ctx.deleteDatabase(name) }
    }
}
