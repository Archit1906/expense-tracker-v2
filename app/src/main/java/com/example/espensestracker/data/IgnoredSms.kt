package com.example.espensestracker.data

import androidx.room.*

@Entity(tableName = "ignored_sms")
data class IgnoredSms(
    @PrimaryKey val smsHash: String,
    val bank: String? = null,
    val account: String? = null,
    val refNo: String? = null,
    val type: String? = null,
    val amountPaise: Long? = null
) {
    @field:androidx.room.Ignore val amount: Double? = amountPaise?.let { it / 100.0 }
}

@Dao
interface IgnoredSmsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: IgnoredSms)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(values: List<IgnoredSms>)
    @Query("SELECT COUNT(*) FROM ignored_sms WHERE smsHash = :hash") suspend fun count(hash: String): Int
    @Query("SELECT COUNT(*) FROM ignored_sms WHERE bank = :bank AND account = :account AND refNo = :ref AND type = :type AND amountPaise = :amountPaise")
    suspend fun countReference(bank: String, account: String, ref: String, type: String, amountPaise: Long): Int
    @Query("SELECT * FROM ignored_sms") suspend fun all(): List<IgnoredSms>
    @Query("DELETE FROM ignored_sms") suspend fun clear()
}
