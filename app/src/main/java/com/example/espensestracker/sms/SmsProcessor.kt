package com.example.espensestracker.sms

import android.content.Context
import android.provider.Telephony
import androidx.room.withTransaction
import com.example.espensestracker.data.direction
import com.example.espensestracker.data.IgnoredSms
import com.example.espensestracker.data.TRANSFER_CATEGORY
import com.example.espensestracker.data.AccountBalance
import com.example.espensestracker.data.AppDatabase
import com.example.espensestracker.data.MerchantRule
import com.example.espensestracker.data.SOURCE_SMS
import com.example.espensestracker.data.Transaction
import com.example.espensestracker.data.UnparsedSms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Glue between the parser and the database: parse -> learned rules -> dedup -> store. */
class SmsProcessor(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {
    private val ctx = context.applicationContext
    private val txDao = db.transactionDao()
    private val unDao = db.unparsedSmsDao()
    private val ruleDao = db.merchantRuleDao()
    private val balDao = db.accountBalanceDao()

    private val ignored = db.ignoredSmsDao()

    /** Serialize deduplication, queue changes and insertion in one Room transaction. */
    suspend fun process(sender: String, body: String, timestamp: Long, onNewTx: (suspend (Transaction) -> Unit)? = null): Boolean {
        var inserted: Transaction? = null
        val changed = db.withTransaction {
            SmsParser.balanceOf(sender, body, timestamp)?.let { saveBalance(it) }
            when (val r = SmsParser.parse(sender, body, timestamp)) {
                is ParseResult.Ok -> {
                    val t = withRule(r.tx)
                    val hash = requireNotNull(t.smsHash)
                    if (unDao.byHash(hash) != null) false else {
                        if (t.refNo == null && !blocked(hash) && txDao.similarMessage(sender, body, timestamp - 120_000, timestamp + 120_000) > 0) {
                            unDao.insert(UnparsedSms(sender = sender, body = body, timestamp = timestamp, amountPaise = t.amountPaise,
                                account = t.account, refNo = null, reason = "Possible duplicate — compare with existing transaction", smsHash = hash,
                                merchantKey = t.merchantKey, type = t.type, explicitReview = true)) > 0
                        } else {
                            inserted = store(t)
                            inserted != null
                        }
                    }
                }
                is ParseResult.Review -> {
                    val s = r.sms
                    if (blocked(s.smsHash) || unDao.byHash(s.smsHash) != null) false else {
                        val possibleDuplicate = s.refNo == null && txDao.similarMessage(sender, body, timestamp - 120_000, timestamp + 120_000) > 0
                        val rule = if (s.explicitReview || possibleDuplicate) null else s.merchantKey?.let { ruleDao.get(it) }
                        if (rule != null && rule.category != TRANSFER_CATEGORY) {
                            inserted = store(toTx(s, rule.category, rule.displayName))
                            inserted != null
                        } else unDao.insert(if (possibleDuplicate) s.copy(explicitReview = true, reason = "Possible duplicate — compare with existing transaction") else s) > 0
                    }
                }
                ParseResult.Ignore -> false
            }
        }
        inserted?.let { onNewTx?.invoke(it) }
        return changed
    }

    /** Learned categories must never convert ordinary payments into transfers, or vice versa. */
    private suspend fun withRule(t: Transaction): Transaction {
        if (t.isTransfer) return t
        val rule = t.merchantKey?.let { ruleDao.get(it) } ?: return t
        if (rule.category == TRANSFER_CATEGORY) return t
        return t.copy(category = rule.category, merchant = rule.displayName ?: t.merchant)
    }

    private suspend fun blocked(hash: String): Boolean =
        ignored.count(hash) > 0 || txDao.countHash(hash) > 0 || unDao.byHash(hash)?.dismissed == true

    /** Only deduplicate strong references scoped to a known bank, account and direction. */
    private suspend fun store(t: Transaction): Transaction? {
        val hash = requireNotNull(t.smsHash)
        if (blocked(hash)) return null
        val sender = t.sender.orEmpty()
        if (t.refNo != null && t.account != null && SmsParser.knownSender(sender)) {
            val bank = SmsParser.bankName(sender)
            if (ignored.countReference(bank, t.account, t.refNo, t.direction(), t.amountPaise) > 0) {
                ignored.insert(IgnoredSms(hash))
                return null
            }
            if (txDao.matchingRef(t.refNo, t.amountPaise, t.direction()).any {
                it.account == t.account && SmsParser.knownSender(it.sender.orEmpty()) && SmsParser.bankName(it.sender.orEmpty()) == bank
            }) {
                ignored.insert(IgnoredSms(hash))
                return null
            }
        }
        val id = txDao.insertIfNew(t)
        return if (id > 0) t.copy(id = id) else null
    }

    private suspend fun saveBalance(b: AccountBalance) {
        // Call only inside a Room transaction.
        val old = balDao.timestampOf(b.accountKey)
        if (old == null || b.timestamp >= old) balDao.upsert(b)
    }

    /** Reads the SMS inbox (last [days] days) through the same pipeline as live messages. */
    suspend fun importInbox(days: Int = 180): Int = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - days * 86_400_000L
        var stored = 0
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                val timestamp = c.getLong(3).takeIf { it > 0 } ?: c.getLong(2)
                if (process(address, body, timestamp)) stored++
            }
        } ?: error("Unable to read SMS inbox")
        stored
    }

    private fun toTx(s: UnparsedSms, category: String, merchant: String?) = Transaction(
        amountPaise = s.amountPaise, type = s.type, merchant = merchant, category = category,
        account = s.account, refNo = s.refNo, timestamp = s.timestamp, source = SOURCE_SMS,
        rawSms = s.body, sender = s.sender, smsHash = s.smsHash, merchantKey = s.merchantKey, isTransfer = category == TRANSFER_CATEGORY
    )

    /** Confirm exactly this queued item; learning is opt-in and only affects future imports. */
    suspend fun assign(s: UnparsedSms, category: String, merchant: String?, learn: Boolean = false, type: String = s.type) = db.withTransaction {
        val current = unDao.byHash(s.smsHash) ?: return@withTransaction
        if (current.dismissed) return@withTransaction
        val name = merchant?.trim()?.ifBlank { null }
        store(toTx(current, category, name).copy(type = type, isTransfer = category == TRANSFER_CATEGORY && type in setOf(com.example.espensestracker.data.TYPE_DEBIT, com.example.espensestracker.data.TYPE_CREDIT)))
        unDao.delete(current)
        if (learn && !current.explicitReview) current.merchantKey?.let { remember(it, category, name) }
    }

    /** Never rewrite previous user decisions or auto-approve queued messages. */
    suspend fun remember(key: String, category: String, merchant: String?) {
        if (category != TRANSFER_CATEGORY) ruleDao.upsert(MerchantRule(key, category, merchant))
    }

    suspend fun delete(t: Transaction) = db.withTransaction {
        t.smsHash?.let { hash ->
            val sender = t.sender.orEmpty()
            val original = t.rawSms?.let { SmsParser.parse(sender, it, t.timestamp) }
            val parsed = (original as? ParseResult.Ok)?.tx
            val reviewed = (original as? ParseResult.Review)?.sms
            val bank = SmsParser.bankName(sender).takeIf { SmsParser.knownSender(sender) }
            ignored.insert(IgnoredSms(hash, bank, parsed?.account ?: reviewed?.account ?: t.account,
                parsed?.refNo ?: reviewed?.refNo ?: t.refNo, parsed?.type ?: reviewed?.type ?: t.direction(),
                parsed?.amountPaise ?: reviewed?.amountPaise ?: t.amountPaise))
        }
        daoDelete(t)
    }

    private suspend fun daoDelete(t: Transaction) = txDao.delete(t)

    /** One-time: give Phase 1/2 rows a merchantKey (re-parsing the stored raw SMS) so rules can match them. */
    suspend fun backfillKeys() = withContext(Dispatchers.IO) {
        for (t in txDao.getAllOnce()) {
            val fromSms = t.rawSms?.let { body ->
                when (val r = SmsParser.parse(t.sender.orEmpty(), body, t.timestamp)) {
                    is ParseResult.Ok -> r.tx.merchantKey
                    is ParseResult.Review -> r.sms.merchantKey
                    ParseResult.Ignore -> null
                }
            }
            val key = fromSms ?: t.merchantKey ?: SmsParser.keyOf(t.merchant)
            if (key != null && key != t.merchantKey) txDao.setKey(t.id, key)
        }
        for (s in unDao.getAllOnce()) {
            val key = when (val r = SmsParser.parse(s.sender, s.body, s.timestamp)) {
                is ParseResult.Review -> r.sms.merchantKey
                is ParseResult.Ok -> r.tx.merchantKey
                ParseResult.Ignore -> null
            }
            if (key != null && key != s.merchantKey) unDao.setKey(s.id, key)
        }
    }

    /** Re-check pending messages only. Explicit-review messages require a human decision. */
    suspend fun reparse(): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (s in unDao.pendingAll()) {
            db.withTransaction {
                val current = unDao.byHash(s.smsHash) ?: return@withTransaction
                if (current.dismissed || current.explicitReview) return@withTransaction
                val t = when (val r = SmsParser.parse(s.sender, s.body, s.timestamp)) {
                    is ParseResult.Ok -> withRule(r.tx)
                    is ParseResult.Review -> if (r.sms.explicitReview) null else r.sms.merchantKey?.let { ruleDao.get(it) }
                        ?.takeIf { it.category != TRANSFER_CATEGORY }?.let { toTx(r.sms, it.category, it.displayName) }
                    ParseResult.Ignore -> null
                }
                if (t != null) {
                    if (store(t) != null) n++
                    if (current.smsHash != t.smsHash) ignored.insert(IgnoredSms(current.smsHash))
                    unDao.delete(current)
                }
            }
        }
        n
    }

    /** One-time: read balances out of SMS already stored (the inbox scan covers the rest). */
    suspend fun backfillBalances() = withContext(Dispatchers.IO) {
        for (t in txDao.getAllOnce().sortedBy { it.timestamp }) {
            val body = t.rawSms ?: continue
            SmsParser.balanceOf(t.sender.orEmpty(), body, t.timestamp)?.let { db.withTransaction { saveBalance(it) } }
        }
        for (s in unDao.pendingAll()) SmsParser.balanceOf(s.sender, s.body, s.timestamp)?.let { db.withTransaction { saveBalance(it) } }
    }

    suspend fun dismiss(s: UnparsedSms) = db.withTransaction {
        unDao.byHash(s.smsHash)?.let { ignored.insert(IgnoredSms(it.smsHash)); unDao.dismiss(it.id) }
    }
    suspend fun dismissAll() = db.withTransaction {
        ignored.insertAll(unDao.pendingAll().map { IgnoredSms(it.smsHash) })
        unDao.dismissAll()
    }
}
