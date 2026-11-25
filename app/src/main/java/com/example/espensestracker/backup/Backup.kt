package com.example.espensestracker.backup

import com.example.espensestracker.data.*
import com.example.espensestracker.util.validMoney
import com.example.espensestracker.util.paise
import com.example.espensestracker.util.MAX_MONEY
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import java.security.NoSuchAlgorithmException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupData(
    val transactions: List<Transaction>,
    val unparsed: List<UnparsedSms>,
    val rules: List<MerchantRule>,
    val balances: List<AccountBalance>,
    val budget: Long,
    val netCredits: Boolean,
    val ignored: List<IgnoredSms> = emptyList(),
    val wallets: List<Wallet> = emptyList(),
    val categories: List<CustomCategory> = emptyList(),
    val budgets: List<MonthlyBudget> = emptyList()
)

/** JSON backup, optionally encrypted with a password (PBKDF2 -> AES-256-GCM). */
object Backup {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val MAGIC = "ETB1".toByteArray()

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    /** Throws a GeneralSecurityException (AEADBadTagException) on a wrong password. */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        if (!isEncrypted(data) || data.size < 48) throw java.security.GeneralSecurityException("Invalid encrypted backup")
        val salt = data.copyOfRange(4, 20)
        val iv = data.copyOfRange(20, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(data, 32, data.size - 32)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } catch (_: NoSuchAlgorithmException) {
            // Older Android providers lack this factory; use the same PBKDF2-HMAC-SHA256 algorithm.
            SecretKeySpec(pbkdf2Fallback(password, salt), "AES")
        } finally { spec.clearPassword() }
    }

    internal fun pbkdf2Fallback(password: String, salt: ByteArray): ByteArray {
        val secret = password.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (secret.isEmpty()) byteArrayOf(0) else secret, "HmacSHA256"))
        secret.fill(0)
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(119_999) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }

    fun toBytes(data: BackupData, password: String): ByteArray {
        val plain = toJson(data).toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES - 48) { "Backup exceeds the size limit" }
        return if (password.isEmpty()) plain else encrypt(plain, password)
    }

    fun parse(bytes: ByteArray, password: String): BackupData {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Invalid backup size" }
        val plain = if (isEncrypted(bytes)) decrypt(bytes, password) else bytes
        return fromJson(String(plain, Charsets.UTF_8))
    }

    private fun toJson(d: BackupData): String = JSONObject()
        .put("format", 3).put("budget", d.budget).put("netCredits", d.netCredits)
        .put("transactions", JSONArray(d.transactions.map { t ->
            JSONObject().put("id", t.id).put("amountPaise", t.amountPaise).put("type", t.type).put("merchant", t.merchant)
                .put("category", t.category).put("account", t.account).put("refNo", t.refNo)
                .put("timestamp", t.timestamp).put("note", t.note).put("source", t.source)
                .put("rawSms", t.rawSms).put("sender", t.sender).put("smsHash", t.smsHash)
                .put("taxDeductible", t.taxDeductible).put("merchantKey", t.merchantKey)
                .put("isTransfer", t.isTransfer).put("walletId", t.walletId).put("destinationWalletId", t.destinationWalletId)
        }))
        .put("unparsed", JSONArray(d.unparsed.map { s ->
            JSONObject().put("id", s.id).put("sender", s.sender).put("body", s.body).put("timestamp", s.timestamp)
                .put("amountPaise", s.amountPaise).put("account", s.account).put("refNo", s.refNo).put("reason", s.reason)
                .put("smsHash", s.smsHash).put("merchantKey", s.merchantKey).put("dismissed", s.dismissed)
                .put("type", s.type).put("explicitReview", s.explicitReview)
        }))
        .put("rules", JSONArray(d.rules.map { r ->
            JSONObject().put("merchantKey", r.merchantKey).put("category", r.category).put("displayName", r.displayName)
        }))
        .put("balances", JSONArray(d.balances.map { b ->
            JSONObject().put("accountKey", b.accountKey).put("bank", b.bank).put("last4", b.last4)
                .put("kind", b.kind).put("amountPaise", b.amountPaise).put("timestamp", b.timestamp)
        }))
        .put("ignored", JSONArray(d.ignored.map { JSONObject().put("smsHash", it.smsHash).put("bank", it.bank).put("account", it.account)
            .put("refNo", it.refNo).put("type", it.type).put("amountPaise", it.amountPaise) }))
        .put("wallets", JSONArray(d.wallets.map { JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("openingPaise", it.openingPaise) }))
        .put("categories", JSONArray(d.categories.map { JSONObject().put("name", it.name) }))
        .put("budgets", JSONArray(d.budgets.map { JSONObject().put("month", it.month).put("category", it.category).put("limitPaise", it.limitPaise) }))
        .toString()

    private fun JSONObject.exactLong(k: String): Long = get(k).toString().toBigDecimal().longValueExact()
    private fun JSONObject.money(version: Int, signed: Boolean = false): Long {
        if (version >= 3) return exactLong("amountPaise").also { require(it in (if (signed) -paise(MAX_MONEY) else 1L)..paise(MAX_MONEY)) { "Invalid amount" } }
        val v = getDouble("amount")
        require(validMoney(v, signed)) { "Invalid legacy amount" }
        return paise(v)
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> =
        (0 until length()).map { f(getJSONObject(it)) }

    private fun fromJson(s: String): BackupData {
        val o = JSONObject(s)
        val version = o.getInt("format")
        require(version in 1..3) { "Unsupported backup format" }
        val result = BackupData(
            transactions = o.getJSONArray("transactions").mapObjects { j ->
                Transaction(
                    id = j.getLong("id"), amountPaise = j.money(version), type = j.getString("type"),
                    merchant = j.str("merchant"), category = j.getString("category"), account = j.str("account"),
                    refNo = j.str("refNo"), timestamp = j.getLong("timestamp"), note = j.str("note"),
                    source = j.getString("source"), rawSms = j.str("rawSms"), sender = j.str("sender"),
                    smsHash = j.str("smsHash"), taxDeductible = j.optBoolean("taxDeductible"),
                    merchantKey = j.str("merchantKey"),
                    isTransfer = if (version >= 3) j.getBoolean("isTransfer") else j.getString("category") == TRANSFER_CATEGORY,
                    walletId = if (version >= 3) j.str("walletId") else null,
                    destinationWalletId = if (version >= 3) j.str("destinationWalletId") else null
                )
            },
            unparsed = o.getJSONArray("unparsed").mapObjects { j ->
                UnparsedSms(
                    id = j.getLong("id"), sender = j.getString("sender"), body = j.getString("body"),
                    timestamp = j.getLong("timestamp"), amountPaise = j.money(version), account = j.str("account"),
                    refNo = j.str("refNo"), reason = j.getString("reason"), smsHash = j.getString("smsHash"),
                    merchantKey = j.str("merchantKey"), dismissed = j.optBoolean("dismissed"),
                    type = j.optString("type", TYPE_DEBIT), explicitReview = j.optBoolean("explicitReview")
                )
            },
            rules = o.getJSONArray("rules").mapObjects { j ->
                MerchantRule(j.getString("merchantKey"), j.getString("category"), j.str("displayName"))
            },
            balances = o.getJSONArray("balances").mapObjects { j ->
                AccountBalance(
                    j.getString("accountKey"), j.getString("bank"), j.getString("last4"),
                    j.getString("kind"), j.money(version, signed = true), j.getLong("timestamp")
                )
            },
            budget = o.getLong("budget"), netCredits = o.getBoolean("netCredits"),
            ignored = if (version >= 2) o.getJSONArray("ignored").mapObjects { j -> IgnoredSms(j.getString("smsHash"), j.str("bank"), j.str("account"), j.str("refNo"), j.str("type"), if (j.str(if (version >= 3) "amountPaise" else "amount") != null) j.money(version) else null) } else emptyList(),
            wallets = if (version >= 3) o.getJSONArray("wallets").mapObjects { Wallet(it.getString("id"), it.getString("name"), it.getString("kind"), it.exactLong("openingPaise")) } else emptyList(),
            categories = if (version >= 3) o.getJSONArray("categories").mapObjects { CustomCategory(it.getString("name")) } else emptyList(),
            budgets = if (version >= 3) o.getJSONArray("budgets").mapObjects { MonthlyBudget(it.getString("month"), it.getString("category"), it.exactLong("limitPaise")) } else emptyList()
        )
        val types = setOf(TYPE_DEBIT, TYPE_CREDIT, TYPE_REFUND, TYPE_REIMBURSEMENT)
        require(result.budget > 0 && result.budget <= MAX_MONEY) { "Invalid budget" }
        require(result.transactions.all { it.id >= 0 && validMoney(it.amount) && it.type in types && it.source in setOf(SOURCE_SMS, SOURCE_MANUAL) && it.timestamp > 0 && it.category.isNotBlank() }) { "Invalid transaction data" }
        require(result.unparsed.all { it.id >= 0 && validMoney(it.amount) && it.type in types && it.timestamp > 0 && it.smsHash.isNotBlank() }) { "Invalid review data" }
        require(result.balances.all { validMoney(it.amount, signed = true) && it.kind in setOf("LIMIT", "BALANCE") && it.accountKey.isNotBlank() && it.timestamp > 0 }) { "Invalid balance data" }
        require(result.rules.all { it.merchantKey.isNotBlank() && it.category.isNotBlank() }) { "Invalid merchant rules" }
        require(result.ignored.all { it.smsHash.isNotBlank() && (it.amountPaise == null || it.amountPaise in 1L..paise(MAX_MONEY)) && (it.type == null || it.type in types) }) { "Invalid ignored SMS data" }
        fun <T> unique(values: List<T>) = values.size == values.toSet().size
        require(unique(result.transactions.map { it.id }.filter { it != 0L }) && unique(result.transactions.mapNotNull { it.smsHash }) &&
            unique(result.unparsed.map { it.id }.filter { it != 0L }) && unique(result.unparsed.map { it.smsHash }) &&
            unique(result.rules.map { it.merchantKey }) && unique(result.balances.map { it.accountKey }) && unique(result.ignored.map { it.smsHash })) { "Duplicate identifiers in backup" }
        val ids = result.wallets.map { it.id }.toSet()
        require(result.wallets.all { it.id.isNotBlank() && it.name.isNotBlank() && it.kind in WALLET_TYPES && it.openingPaise in -paise(MAX_MONEY)..paise(MAX_MONEY) } && unique(result.wallets.map { it.id })) { "Invalid wallets" }
        require(result.categories.all { it.name.isNotBlank() && it.name != OVERALL_BUDGET } && unique(result.categories.map { it.name.lowercase(java.util.Locale.ROOT) })) { "Invalid categories" }
        require(result.budgets.all { validMonthKey(it.month) && it.category.isNotBlank() && it.limitPaise in 1L..paise(MAX_MONEY) } && unique(result.budgets.map { it.month to it.category })) { "Invalid budgets" }
        require(result.transactions.all { t -> (t.walletId == null || t.walletId in ids) && (t.destinationWalletId == null || (t.destinationWalletId in ids && t.walletId != null && t.walletId != t.destinationWalletId && t.isTransfer && t.type == TYPE_DEBIT)) }) { "Invalid wallet links" }
        return result
    }
}
