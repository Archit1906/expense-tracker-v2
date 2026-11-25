package com.example.espensestracker.sms

import com.example.espensestracker.data.AccountBalance
import com.example.espensestracker.data.SOURCE_SMS
import com.example.espensestracker.data.TRANSFER_CATEGORY
import com.example.espensestracker.data.TYPE_CREDIT
import com.example.espensestracker.data.TYPE_REFUND
import com.example.espensestracker.data.TYPE_DEBIT
import com.example.espensestracker.data.Transaction
import com.example.espensestracker.data.UnparsedSms
import java.security.MessageDigest
import com.example.espensestracker.util.parseMoney
import com.example.espensestracker.util.validMoney

sealed interface ParseResult {
    data class Ok(val tx: Transaction) : ParseResult
    data class Review(val sms: UnparsedSms) : ParseResult
    data object Ignore : ParseResult
}

/** Pure Kotlin (no Android classes) so it can be unit-tested on the JVM. */
object SmsParser {
    private val I = RegexOption.IGNORE_CASE
    private val amountRx = Regex("""(?:\bRs\.?|\bINR|₹)\s*([\d,]+(?:\.\d+)?)(?!\d|[.,]\d)""", I)
    private val debitRx = Regex("""\b(?:debited|spent|paid|sent|withdrawn|purchase)\b""", I)
    private val creditRx = Regex("""\b(?:credited|received|deposited|refund(?:ed)?|revers(?:al|ed))\b""", I)
    private val refundRx = Regex("""\b(?:refund(?:ed)?|revers(?:al|ed))\b""", I)
    private val failedRx = Regex("""\b(?:failed|declined|unsuccessful|not (?:been )?(?:debited|credited|processed|completed)|could not be (?:processed|completed)|payment cancelled|transaction cancelled)\b""", I)
    private val pendingRx = Regex("""\b(?:pending|processing|initiated|awaiting confirmation|will be (?:credited|refunded|reversed)|refund (?:requested|expected))\b""", I)
    private val otpRx = Regex("""\b(?:OTP|one.time password)\b.{0,30}\b(?:for|to authorize|to complete)\b|\b(?:is your OTP|do not share (?:your |this )?OTP)\b""", I)
    private val reminderRx = Regex("""\b(?:minimum amount due|payment due|bill is due|statement generated|payment reminder)\b""", I)
    private val cardWordRx = Regex("""card""", I)
    // Card-bill payments and own-account moves: money moving between your accounts, not spending.
    private val transferRx = Regex("""credit card (?:bill|payment)|card bill|towards .{0,25}credit card|payment (?:of .{0,20} )?received (?:towards|on|for) (?:your )?.{0,20}card|\bCRED\b|self[- ]?transfer|own account""", I)
    private val strongRx = Regex("""\b(?:debited|credited|spent)\b""", I)
    private val promoRx = Regex("""\b(?:offer|win|apply now|click|pre-approved|loan)\b|https?://|www\.""", I)
    private val hintRx = Regex("""a/c|acct|account|card|upi|vpa|bank""", I)
    private val balRx = Regex("""bal|limit|avl""", I)
    private val accountRx = Regex("""(?:a/c|acct|account|card(?:\s+ending)?|ending)\D{0,12}?(\d{4})(?!\d)""", I)
    private val refRx = Regex("""(?:ref(?:erence)?(?:\s*no)?\.?|rrn|utr|txn\s*(?:id|no))\D{0,5}(\d{9,14})""", I)
    private val vpaRx = Regex("""([\w.\-]{2,}@[A-Za-z0-9][A-Za-z0-9.\-]*)""")
    private val posRx = Regex("""\bPOS\s*\d{4,}""", I)
    private val foreignRx = Regex("""INTNL|INTERNATIONAL|FOREX""", I)
    private val atmRx = Regex("""\bATM\b""", I)
    private val nameRx = Regex("""\b(?:at|to|towards|info:?)\s+([A-Za-z][A-Za-z0-9 &.'*-]{2,30}?)(?=\s+(?:on|via|ref|using|upi|avl|avail|bal|\d)|[.,;]|$)""", I)
    private val fromRx = Regex("""\bfrom\s+([A-Za-z][A-Za-z .&-]{2,30}?)(?=\s*[.,;]|\s+(?:on|ref|via|upi|credited|to|into|\d)|$)""", I)
    private val phoneSender = Regex("""[+\d\s-]+""")

    private class Rule(pattern: String, val display: String?, val category: String) {
        val rx = Regex(pattern, I)
    }

    // First match wins. display = null -> use the matched keyword, prettified.
    private val RULES = listOf(
        Rule("""(?:swiggy[ ._-]*)?instamart|\b(?:blinkit|zepto|bigbasket|dmart)""", null, "Groceries"),
        Rule("""\bswiggy""", "Swiggy", "Food & Dining"),
        Rule("""\bzomato""", "Zomato", "Food & Dining"),
        Rule("""domino""", "Domino's Pizza", "Food & Dining"),
        Rule("""mcdonald""", "McDonald's", "Food & Dining"),
        Rule("""tokai""", "Blue Tokai Coffee", "Food & Dining"),
        Rule("""\b(?:starbucks|kfc|burger king|pizza hut|subway)""", null, "Food & Dining"),
        Rule("""\b(?:uber|rapido|redbus|irctc|makemytrip|indigo|vistara|goibibo|shell|hpcl|bpcl|iocl|ola)\b""", null, "Rides & Travel"),
        Rule("""\b(?:amazon|flipkart|myntra|ajio|meesho|nykaa)""", null, "Shopping"),
        Rule("""\b(?:netflix|spotify|hotstar|youtube|apple|google ?one|jiocinema|kindle)""", null, "Subscriptions"),
        Rule("""\b(?:airtel|vodafone|bsnl|tangedco|tneb|bescom|electricity|broadband|fibernet|jio)\b""", null, "Bills & Utilities"),
        Rule("""\b(?:apollo|pharmeasy|1mg|medplus|netmeds)""", null, "Health"),
        Rule("""\b(?:bookmyshow|pvr|inox)""", null, "Entertainment")
    )

    private val balanceRx = Regex(
        """\b(avail(?:able)?\.?\s*(?:bal(?:ance)?|limit)|avl\.?\s*(?:bal(?:ance)?|limit)|bal(?:ance)?)\s*(?:is|of|:|-|=)?\s*(?:INR|Rs\.?|₹)\s*([+-]?[\d,]+(?:\.\d+)?)(?!\d|[.,]\d)""", I
    )

    private fun normalizeAmount(text: String): String? {
        val clean = text.trimEnd(',')
        if (!Regex("""[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})(?:\.[0-9]{1,2})?""").matches(clean)) return null
        return clean.replace(",", "")
    }

    /** Latest "Avail Bal" / "Avl Limit" in a bank SMS, if any (works even when the SMS is not a payment). */
    fun balanceOf(sender: String, body: String, timestamp: Long): AccountBalance? {
        if (!knownSender(sender) || failedRx.containsMatchIn(body) || pendingRx.containsMatchIn(body) || otpRx.containsMatchIn(body) || reminderRx.containsMatchIn(body)) return null
        val m = balanceRx.find(body) ?: return null
        if (!hintRx.containsMatchIn(body)) return null
        val amount = normalizeAmount(m.groupValues[2])?.toDoubleOrNull()?.takeIf { validMoney(it, signed = true) } ?: return null
        val kind = if (m.groupValues[1].contains("limit", ignoreCase = true)) "LIMIT" else "BALANCE"
        val last4 = accountRx.find(body)?.groupValues?.get(1) ?: return null
        val bank = bankName(sender)
        return AccountBalance("$bank|$last4|$kind", bank, last4, kind, com.example.espensestracker.util.paise(amount), timestamp)
    }

    // Conservative allowlist of common Indian bank headers; unknown financial messages need review.
    private val banks = listOf("HDFC", "ICICI", "SBI", "AXIS", "KOTAK", "AMEX", "PNB", "BOB", "IDFC", "YES", "CANARA", "FEDERAL", "INDUSIND", "RBL", "UNION", "UCO", "IOB", "BOI", "IDBI", "AUBANK")
    private fun header(sender: String) = sender.substringAfter('-').substringBefore('-').uppercase(java.util.Locale.ROOT)
    private fun recognizedBank(sender: String): String? = banks.firstOrNull { header(sender).startsWith(it) }
    fun knownSender(sender: String): Boolean = recognizedBank(sender) != null
    fun bankName(sender: String): String = recognizedBank(sender) ?: header(sender).ifBlank { "Unknown" }

    fun parse(sender: String, body: String, timestamp: Long): ParseResult {
        if (sender.isBlank() || sender.matches(phoneSender)) return ParseResult.Ignore
        if (pendingRx.containsMatchIn(body) || otpRx.containsMatchIn(body) || reminderRx.containsMatchIn(body)) return ParseResult.Ignore
        if (failedRx.containsMatchIn(body) && !(refundRx.containsMatchIn(body) && Regex("""\b(?:credited back|refunded|reversed)\b""", I).containsMatchIn(body))) return ParseResult.Ignore
        val dm = debitRx.find(body)
        val cm = creditRx.find(body)
        if (dm == null && cm == null) return ParseResult.Ignore          // OTPs, promos, balance alerts
        if (promoRx.containsMatchIn(body) && !strongRx.containsMatchIn(body)) return ParseResult.Ignore
        if (!hintRx.containsMatchIn(body)) return ParseResult.Ignore

        val amount = amountRx.findAll(body).firstOrNull { m ->
            !body.substring(maxOf(0, m.range.first - 12), m.range.first).contains(balRx)
        }?.groupValues?.get(1)?.let(::normalizeAmount)?.let(::parseMoney) ?: return ParseResult.Ignore

        val baseType = if (cm == null || (dm != null && dm.range.first < cm.range.first)) TYPE_DEBIT else TYPE_CREDIT
        val type = if (refundRx.containsMatchIn(body)) TYPE_REFUND else baseType
        val account = accountRx.find(body)?.groupValues?.get(1)
        val ref = refRx.find(body)?.groupValues?.get(1)
        val vpa = vpaRx.find(body)?.groupValues?.get(1)?.trimEnd('.')
        val name = (if (type != TYPE_DEBIT) fromRx.find(body)?.groupValues?.get(1) else null)
            ?: nameRx.find(body)?.groupValues?.get(1)
        val hash = sha256("$sender|$timestamp|$body")
        val mkey = vpa?.lowercase(java.util.Locale.ROOT) ?: keyOf(posRx.find(body)?.value ?: name)

        fun ok(merchant: String?, category: String) = ParseResult.Ok(
            Transaction(
                amountPaise = com.example.espensestracker.util.paise(amount), type = type, merchant = merchant, category = category,
                account = account, refNo = ref, timestamp = timestamp, source = SOURCE_SMS,
                rawSms = body, sender = sender, smsHash = hash, merchantKey = mkey, isTransfer = category == TRANSFER_CATEGORY
            )
        )
        fun review(reason: String, explicit: Boolean = false) = ParseResult.Review(
            UnparsedSms(
                sender = sender, body = body, timestamp = timestamp, amountPaise = com.example.espensestracker.util.paise(amount),
                account = account, refNo = ref, reason = reason, smsHash = hash, merchantKey = mkey, type = type, explicitReview = explicit
            )
        )

        val key = vpa?.substringBefore('@') ?: name
        val rule = key?.let { k -> RULES.firstNotNullOfOrNull { r -> r.rx.find(k)?.let { m -> r to m.value } } }
        return when {
            !knownSender(sender) -> review("Unverified SMS sender", true)
            type != TYPE_REFUND && transferRx.containsMatchIn(body) ->
                ok(if (cardWordRx.containsMatchIn(body)) "Credit card payment" else "Transfer", TRANSFER_CATEGORY)
            rule != null -> ok(rule.first.display ?: pretty(rule.second), rule.first.category)
            type != TYPE_DEBIT -> ok((name ?: vpa?.substringBefore('@'))?.let { pretty(it.trim()) }, "Other")
            atmRx.containsMatchIn(body) -> ok("ATM withdrawal", "Other")
            vpa != null -> review("Unmapped VPA handle")
            posRx.containsMatchIn(body) -> review("POS code unmapped")
            foreignRx.containsMatchIn(body) -> review("Foreign merchant")
            name != null -> ok(pretty(name.trim()), "Other")
            else -> review("Unknown merchant")
        }
    }

    /** Stable id for "the same merchant": lowercase letters+digits of the VPA name, POS code or merchant name. */
    fun keyOf(s: String?): String? = s?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

    private fun pretty(s: String): String =
        if (s == s.uppercase() || s == s.lowercase())
            s.lowercase().split(' ').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        else s

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
