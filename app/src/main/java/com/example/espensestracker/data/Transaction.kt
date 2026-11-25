package com.example.espensestracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val TYPE_DEBIT = "DEBIT"
const val TYPE_CREDIT = "CREDIT"
const val TYPE_REFUND = "REFUND"
const val TYPE_REIMBURSEMENT = "REIMBURSEMENT"
const val SOURCE_MANUAL = "MANUAL"
const val SOURCE_SMS = "SMS"

/**
 * One money movement. Fields like rawSms / sender / smsHash stay null for manual
 * entries and get filled in Phase 2 when SMS parsing is added.
 * smsHash is unique (SQLite allows many NULLs), which gives us dedup later.
 */
@Entity(
    tableName = "transactions",
    indices = [Index(value = ["smsHash"], unique = true)]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val type: String = TYPE_DEBIT,
    val merchant: String? = null,
    val category: String = "Other",
    val account: String? = null,
    val refNo: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null,
    val source: String = SOURCE_MANUAL,
    val rawSms: String? = null,
    val sender: String? = null,
    val smsHash: String? = null,
    val taxDeductible: Boolean = false,
    val merchantKey: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val isTransfer: Boolean = false,
    val walletId: String? = null,
    val destinationWalletId: String? = null
) {
    @field:androidx.room.Ignore val amount: Double = amountPaise / 100.0
}

/** Financial direction is independent of the display category. */
fun Transaction.spendPaise(netCredits: Boolean = false): Long = when {
    isTransfer -> 0L
    type == TYPE_DEBIT -> amountPaise
    type == TYPE_REFUND || type == TYPE_REIMBURSEMENT -> -amountPaise
    type == TYPE_CREDIT && netCredits -> -amountPaise
    else -> 0L
}
fun Transaction.spend(netCredits: Boolean = false): Double = spendPaise(netCredits) / 100.0
fun Iterable<Transaction>.totalSpendPaise(netCredits: Boolean = false): Long = fold(0L) { total, t -> Math.addExact(total, t.spendPaise(netCredits)) }
fun Iterable<Transaction>.totalSpend(netCredits: Boolean = false): Double = totalSpendPaise(netCredits) / 100.0
fun Transaction.direction(): String = if (type == TYPE_REIMBURSEMENT) TYPE_CREDIT else type
