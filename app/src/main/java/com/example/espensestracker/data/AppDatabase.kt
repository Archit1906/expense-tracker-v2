package com.example.espensestracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Transaction::class, UnparsedSms::class, MerchantRule::class, AccountBalance::class, IgnoredSms::class, Wallet::class, CustomCategory::class, MonthlyBudget::class],
    version = 6, exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun unparsedSmsDao(): UnparsedSmsDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun accountBalanceDao(): AccountBalanceDao
    abstract fun ignoredSmsDao(): IgnoredSmsDao

    abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `unparsed_sms` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sender` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `amount` REAL NOT NULL, " +
                        "`account` TEXT, `refNo` TEXT, `reason` TEXT NOT NULL, " +
                        "`smsHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_unparsed_sms_smsHash` ON `unparsed_sms` (`smsHash`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `merchantKey` TEXT")
                db.execSQL("ALTER TABLE `unparsed_sms` ADD COLUMN `merchantKey` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merchant_rules` (`merchantKey` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, `displayName` TEXT, PRIMARY KEY(`merchantKey`))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `account_balances` (`accountKey` TEXT NOT NULL, " +
                        "`bank` TEXT NOT NULL, `last4` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`accountKey`))"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE unparsed_sms ADD COLUMN type TEXT NOT NULL DEFAULT 'DEBIT'")
                db.execSQL("ALTER TABLE unparsed_sms ADD COLUMN explicitReview INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS ignored_sms (smsHash TEXT NOT NULL PRIMARY KEY, bank TEXT, account TEXT, refNo TEXT, type TEXT, amount REAL)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild legacy REAL columns as INTEGER paise. Existing IDs and SMS fingerprints are retained.
                db.execSQL("CREATE TABLE transactions_v6 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amountPaise INTEGER NOT NULL, type TEXT NOT NULL, merchant TEXT, category TEXT NOT NULL, account TEXT, refNo TEXT, timestamp INTEGER NOT NULL, note TEXT, source TEXT NOT NULL, rawSms TEXT, sender TEXT, smsHash TEXT, taxDeductible INTEGER NOT NULL, merchantKey TEXT, isTransfer INTEGER NOT NULL DEFAULT 0, walletId TEXT, destinationWalletId TEXT)")
                db.execSQL("INSERT INTO transactions_v6 SELECT id, CAST(ROUND(amount * 100) AS INTEGER), type, merchant, category, account, refNo, timestamp, note, source, rawSms, sender, smsHash, taxDeductible, merchantKey, CASE WHEN category = 'Transfers' THEN 1 ELSE 0 END, NULL, NULL FROM transactions")
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_v6 RENAME TO transactions")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_smsHash ON transactions(smsHash)")
                db.execSQL("CREATE TABLE unparsed_sms_v6 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, amountPaise INTEGER NOT NULL, account TEXT, refNo TEXT, reason TEXT NOT NULL, smsHash TEXT NOT NULL, merchantKey TEXT, dismissed INTEGER NOT NULL, type TEXT NOT NULL DEFAULT 'DEBIT', explicitReview INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO unparsed_sms_v6 SELECT id, sender, body, timestamp, CAST(ROUND(amount * 100) AS INTEGER), account, refNo, reason, smsHash, merchantKey, dismissed, type, explicitReview FROM unparsed_sms")
                db.execSQL("DROP TABLE unparsed_sms")
                db.execSQL("ALTER TABLE unparsed_sms_v6 RENAME TO unparsed_sms")
                db.execSQL("CREATE UNIQUE INDEX index_unparsed_sms_smsHash ON unparsed_sms(smsHash)")
                db.execSQL("CREATE TABLE account_balances_v6 (accountKey TEXT NOT NULL PRIMARY KEY, bank TEXT NOT NULL, last4 TEXT NOT NULL, kind TEXT NOT NULL, amountPaise INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
                db.execSQL("INSERT INTO account_balances_v6 SELECT accountKey, bank, last4, kind, CAST(ROUND(amount * 100) AS INTEGER), timestamp FROM account_balances")
                db.execSQL("DROP TABLE account_balances")
                db.execSQL("ALTER TABLE account_balances_v6 RENAME TO account_balances")
                db.execSQL("CREATE TABLE ignored_sms_v6 (smsHash TEXT NOT NULL PRIMARY KEY, bank TEXT, account TEXT, refNo TEXT, type TEXT, amountPaise INTEGER)")
                db.execSQL("INSERT INTO ignored_sms_v6 SELECT smsHash, bank, account, refNo, type, CASE WHEN amount IS NULL THEN NULL ELSE CAST(ROUND(amount * 100) AS INTEGER) END FROM ignored_sms")
                db.execSQL("DROP TABLE ignored_sms")
                db.execSQL("ALTER TABLE ignored_sms_v6 RENAME TO ignored_sms")
                db.execSQL("CREATE TABLE wallets (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, openingPaise INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE custom_categories (name TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("CREATE TABLE monthly_budgets (month TEXT NOT NULL, category TEXT NOT NULL, limitPaise INTEGER NOT NULL, PRIMARY KEY(month, category))")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "expenses.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
    }
}
