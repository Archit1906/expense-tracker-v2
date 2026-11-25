package com.example.espensestracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

const val OVERALL_BUDGET = "__overall__"
val WALLET_TYPES = listOf("Bank account", "Credit card", "Cash", "UPI wallet")

@Entity(tableName = "wallets")
data class Wallet(@PrimaryKey val id: String, val name: String, val kind: String, val openingPaise: Long)

@Entity(tableName = "custom_categories")
data class CustomCategory(@PrimaryKey val name: String)

@Entity(tableName = "monthly_budgets", primaryKeys = ["month", "category"])
data class MonthlyBudget(val month: String, val category: String, val limitPaise: Long)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM wallets ORDER BY name") fun wallets(): Flow<List<Wallet>>
    @Query("SELECT * FROM wallets") suspend fun allWallets(): List<Wallet>
    @Query("SELECT * FROM wallets WHERE id = :id") suspend fun wallet(id: String): Wallet?
    @Upsert suspend fun saveWallet(wallet: Wallet)
    @Insert suspend fun insertWallets(wallets: List<Wallet>)
    @Query("DELETE FROM wallets") suspend fun clearWallets()
    @Query("SELECT * FROM custom_categories ORDER BY name") fun categories(): Flow<List<CustomCategory>>
    @Query("SELECT * FROM custom_categories") suspend fun allCategories(): List<CustomCategory>
    @Insert suspend fun insertCategory(category: CustomCategory)
    @Insert suspend fun insertCategories(categories: List<CustomCategory>)
    @Query("DELETE FROM custom_categories") suspend fun clearCategories()
    @Query("SELECT * FROM monthly_budgets ORDER BY month DESC, category") fun budgets(): Flow<List<MonthlyBudget>>
    @Query("SELECT * FROM monthly_budgets") suspend fun allBudgets(): List<MonthlyBudget>
    @Upsert suspend fun saveBudget(budget: MonthlyBudget)
    @Insert suspend fun insertBudgets(budgets: List<MonthlyBudget>)
    @Query("DELETE FROM monthly_budgets WHERE month = :month AND category = :category") suspend fun deleteBudget(month: String, category: String)
    @Query("DELETE FROM monthly_budgets") suspend fun clearBudgets()
}

/** Opening balance is the balance BEFORE all linked transactions, not an SMS snapshot. */
fun Wallet.ledgerBalance(transactions: Iterable<Transaction>): Long = transactions.fold(openingPaise) { total, t ->
    val delta = when {
        t.destinationWalletId == id && t.isTransfer && t.type == TYPE_DEBIT -> t.amountPaise
        t.walletId == id -> if (t.type == TYPE_DEBIT) -t.amountPaise else t.amountPaise
        else -> 0L
    }
    Math.addExact(total, delta)
}

fun Iterable<Transaction>.incomePaise(): Long = filter { !it.isTransfer && it.type == TYPE_CREDIT }.sumOf { it.amountPaise }
fun Iterable<Transaction>.grossExpensePaise(): Long = filter { !it.isTransfer && it.type == TYPE_DEBIT }.sumOf { it.amountPaise }
fun Iterable<Transaction>.refundPaise(): Long = filter { !it.isTransfer && it.type == TYPE_REFUND }.sumOf { it.amountPaise }
fun Iterable<Transaction>.reimbursementPaise(): Long = filter { !it.isTransfer && it.type == TYPE_REIMBURSEMENT }.sumOf { it.amountPaise }
fun monthKey(timestamp: Long): String = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(timestamp))
fun validMonthKey(month: String): Boolean = Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month)
