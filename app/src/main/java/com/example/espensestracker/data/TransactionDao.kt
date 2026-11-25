package com.example.espensestracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    /** Returns the new row id, or -1 if it was ignored as a duplicate (same smsHash). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(transaction: Transaction): Long

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Transaction>>

    @Query(
        "SELECT * FROM transactions " +
            "WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC"
    )
    fun getByMonth(from: Long, to: Long): Flow<List<Transaction>>

    @Query("SELECT COUNT(*) FROM transactions WHERE smsHash = :hash")
    suspend fun countHash(hash: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE sender = :sender AND rawSms = :body AND timestamp BETWEEN :from AND :to")
    suspend fun similarMessage(sender: String, body: String, from: Long, to: Long): Int

    @Query("SELECT * FROM transactions WHERE refNo = :ref AND amountPaise = :amountPaise AND (type = :type OR (type = 'REIMBURSEMENT' AND :type = 'CREDIT'))")
    suspend fun matchingRef(ref: String, amountPaise: Long, type: String): List<Transaction>

    @Query("UPDATE transactions SET merchantKey = :key WHERE id = :id")
    suspend fun setKey(id: Long, key: String)

    @Query("SELECT * FROM transactions WHERE merchantKey IS NULL")
    suspend fun missingKeys(): List<Transaction>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE rawSms IS NOT NULL AND category = 'Other'")
    suspend fun otherSms(): List<Transaction>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<Transaction>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT amountPaise FROM transactions WHERE merchantKey = :key AND type = 'DEBIT' AND isTransfer = 0 AND timestamp < :before ORDER BY timestamp DESC LIMIT 30")
    suspend fun merchantAmounts(key: String, before: Long): List<Long>

    @Query("SELECT amountPaise FROM transactions WHERE type = 'DEBIT' AND isTransfer = 0 AND timestamp >= :since AND timestamp < :before ORDER BY timestamp DESC LIMIT 200")
    suspend fun recentAmounts(since: Long, before: Long): List<Long>

    @Update
    suspend fun update(transaction: Transaction)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    @Delete
    suspend fun delete(transaction: Transaction)
}
