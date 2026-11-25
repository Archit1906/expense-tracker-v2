package com.example.espensestracker.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.espensestracker.backup.Backup
import com.example.espensestracker.backup.BackupData
import com.example.espensestracker.data.*
import com.example.espensestracker.sms.SmsParser
import com.example.espensestracker.sms.SmsProcessor
import com.example.espensestracker.util.AppSettings
import com.example.espensestracker.util.toCsv
import com.example.espensestracker.work.postWeeklySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import com.example.espensestracker.util.validMoney
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException

class ExpenseViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.transactionDao()
    private val finance = db.financeDao()
    val wallets = finance.wallets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val customCategories = finance.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val monthlyBudgets = finance.budgets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val processor = SmsProcessor(app)
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)

    val transactions: StateFlow<List<Transaction>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<List<UnparsedSms>> = db.unparsedSmsDao().observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rules: StateFlow<List<MerchantRule>> = db.merchantRuleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ruleCount: StateFlow<Int> = rules.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val balances: StateFlow<List<AccountBalance>> = db.accountBalanceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** null = no restore pending; true/false = a backup file is loaded, and whether it needs a password. */
    private val _restoreState = MutableStateFlow<Boolean?>(null)
    val restoreState: StateFlow<Boolean?> = _restoreState.asStateFlow()
    private var pendingBackup: ByteArray? = null
    private var pendingRestore: BackupData? = null
    private val _restorePreview = MutableStateFlow<String?>(null)
    val restorePreview = _restorePreview.asStateFlow()
    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()

    init {
        if (!prefs.getBoolean("keys_v5", false)) {
            viewModelScope.launch {
                try { processor.backfillKeys(); prefs.edit().putBoolean("keys_v5", true).apply() }
                catch (e: CancellationException) { throw e } catch (e: Exception) { _notice.value = "Merchant key update failed; reopen the app to retry." }
            }
        }
        if (!prefs.getBoolean("balances_v4", false)) {
            viewModelScope.launch {
                try { processor.backfillBalances(); prefs.edit().putBoolean("balances_v4", true).apply() }
                catch (e: CancellationException) { throw e } catch (e: Exception) { _notice.value = "Balance update failed; reopen the app to retry." }
            }
        }
    }

    suspend fun get(id: Long): Transaction? = dao.getById(id)

    /** Save only after successful persistence; report errors without closing the editor. */
    suspend fun save(t: Transaction, learn: Boolean = false) {
        require(validMoney(t.amount)) { "Enter a positive amount with at most two decimals" }
        require(t.type in setOf(TYPE_DEBIT, TYPE_CREDIT, TYPE_REFUND, TYPE_REIMBURSEMENT)) { "Unknown transaction type" }
        require(!t.isTransfer || t.type in setOf(TYPE_DEBIT, TYPE_CREDIT)) { "Transfer must be incoming or outgoing" }
        require(t.destinationWalletId == null || (t.isTransfer && t.type == TYPE_DEBIT && t.walletId != null && t.walletId != t.destinationWalletId)) { "Choose two different wallets" }
        val key = if (t.source == SOURCE_MANUAL) SmsParser.keyOf(t.merchant)
            else t.merchantKey ?: SmsParser.keyOf(t.merchant)
        val tx = t.copy(merchantKey = key)
        db.withTransaction {
            for (wallet in listOfNotNull(tx.walletId, tx.destinationWalletId)) require(finance.wallet(wallet) != null) { "Wallet no longer exists" }
            if (tx.id == 0L) check(dao.insertIfNew(tx) > 0) { "Transaction already exists" }
            else {
                check(dao.getById(tx.id) != null) { "Transaction no longer exists" }
                dao.update(tx)
            }
            if (learn && !tx.isTransfer && key != null) processor.remember(key, tx.category, tx.merchant)
        }
    }

    suspend fun delete(t: Transaction) = processor.delete(t)

    fun onSmsGranted() {
        if (!prefs.getBoolean("inbox_imported", false)) rescan()
    }

    fun rescan() {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                val n = processor.importInbox()
                prefs.edit().putBoolean("inbox_imported", true).apply()
                _notice.value = "Inbox scan finished: $n new item(s)."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _notice.value = "Inbox scan failed. Check SMS permission and retry." }
            finally { _importing.value = false }
        }
    }

    fun reparse() {
        viewModelScope.launch {
            try { _notice.value = "Re-checked: ${processor.reparse()} item(s) updated." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _notice.value = "Could not re-check SMS." }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = dao.getAllOnce()
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(toCsv(list).toByteArray()) } ?: error("Cannot open export file")
                _notice.value = "Exported ${list.size} transaction(s)."
            } catch (e: Exception) {
                _notice.value = "Export failed: ${e.message}"
            }
        }
    }

    // ---------- backup & restore ----------
    fun backupTo(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = db.withTransaction { BackupData(
                    dao.getAllOnce(), db.unparsedSmsDao().getAllOnce(), db.merchantRuleDao().getAllOnce(),
                    db.accountBalanceDao().getAllOnce(), AppSettings.budget.toLong(), AppSettings.netCredits, db.ignoredSmsDao().all(), finance.allWallets(), finance.allCategories(), finance.allBudgets()
                ) }
                val bytes = Backup.toBytes(data, password)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot open backup file")
                _notice.value = "Backup saved: ${data.transactions.size} transaction(s)" + if (password.isNotEmpty()) ", encrypted." else "."
            } catch (e: Exception) {
                _notice.value = "Backup failed: ${e.message}"
            }
        }
    }

    fun prepareRestore(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        require(out.size() + n <= Backup.MAX_BYTES) { "Backup exceeds size limit" }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                    ?: throw IllegalStateException("could not read the file")
                pendingRestore = null
                _restorePreview.value = null
                pendingBackup = bytes
                _restoreState.value = Backup.isEncrypted(bytes)
            } catch (e: Exception) {
                _notice.value = "Could not open backup: ${e.message}"
            }
        }
    }

    fun cancelRestore() {
        pendingBackup = null
        pendingRestore = null
        _restorePreview.value = null
        _restoreState.value = null
    }

    fun previewRestore(password: String) {
        val bytes = pendingBackup ?: return
        if (_restoring.value) return
        _restoring.value = true
        viewModelScope.launch {
            try {
                val d = kotlinx.coroutines.withContext(Dispatchers.Default) { Backup.parse(bytes, password) }
                pendingRestore = d
                _restorePreview.value = "${d.transactions.size} transactions, ${d.unparsed.count { !it.dismissed }} pending SMS, ${d.rules.size} merchant rules and ${d.balances.size} account balances, ${d.wallets.size} wallets and ${d.budgets.size} budgets."
            } catch (e: GeneralSecurityException) {
                _notice.value = "Wrong password, or the file is damaged."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _notice.value = "Invalid backup: ${e.message}"; cancelRestore() }
            finally { _restoring.value = false }
        }
    }

    fun confirmRestore() {
        val d = pendingRestore ?: return
        if (_restoring.value) return
        _restoring.value = true
        viewModelScope.launch {
            try {
                db.withTransaction {
                    dao.deleteAll(); db.unparsedSmsDao().deleteAll()
                    db.merchantRuleDao().deleteAll(); db.accountBalanceDao().deleteAll(); db.ignoredSmsDao().clear()
                    finance.clearWallets(); finance.clearCategories(); finance.clearBudgets()
                    finance.insertWallets(d.wallets); finance.insertCategories(d.categories); finance.insertBudgets(d.budgets)
                    dao.insertAll(d.transactions); db.unparsedSmsDao().insertAll(d.unparsed)
                    db.merchantRuleDao().insertAll(d.rules); db.accountBalanceDao().insertAll(d.balances)
                    db.ignoredSmsDao().insertAll(d.ignored)
                }
                val ctx = getApplication<Application>()
                AppSettings.saveBudget(ctx, d.budget.toDouble())
                AppSettings.saveNetCredits(ctx, d.netCredits)
                _notice.value = "Restored ${d.transactions.size} transaction(s)."
                cancelRestore()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _notice.value = "Restore failed: ${e.message}"; cancelRestore() }
            finally { _restoring.value = false }
        }
    }

    suspend fun saveWallet(id: String?, name: String, kind: String, openingPaise: Long) {
        require(name.trim().length in 1..60 && kind in WALLET_TYPES) { "Enter a valid wallet name and type" }
        require(openingPaise in -com.example.espensestracker.util.paise(com.example.espensestracker.util.MAX_MONEY)..com.example.espensestracker.util.paise(com.example.espensestracker.util.MAX_MONEY)) { "Opening balance is out of range" }
        db.withTransaction {
            require(id == null || finance.wallet(id) != null) { "Wallet no longer exists" }
            finance.saveWallet(Wallet(id ?: java.util.UUID.randomUUID().toString(), name.trim(), kind, openingPaise))
        }
    }

    suspend fun addCategory(name: String) {
        val clean = name.trim()
        require(clean.length in 1..40 && clean != OVERALL_BUDGET) { "Enter a category name (1–40 characters)" }
        db.withTransaction {
            require((CATEGORY_LIST.map { it.name } + finance.allCategories().map { it.name }).none { it.equals(clean, ignoreCase = true) }) { "Category already exists" }
            finance.insertCategory(CustomCategory(clean))
        }
    }

    suspend fun setMonthlyBudget(month: String, category: String, limitPaise: Long?) {
        require(validMonthKey(month) && category.isNotBlank()) { "Invalid budget" }
        if (limitPaise == null) finance.deleteBudget(month, category)
        else {
            require(limitPaise in 1L..com.example.espensestracker.util.paise(com.example.espensestracker.util.MAX_MONEY)) { "Invalid amount" }
            finance.saveBudget(MonthlyBudget(month, category, limitPaise))
        }
    }

    fun sendTestSummary() {
        viewModelScope.launch(Dispatchers.IO) {
            postWeeklySummary(getApplication())
            _notice.value = "Summary sent. If you don't see it, check notification permission."
        }
    }

    fun deleteRule(key: String) {
        viewModelScope.launch { db.merchantRuleDao().delete(key) }
    }

    fun assign(s: UnparsedSms, category: String, merchant: String?, learn: Boolean, type: String) {
        viewModelScope.launch {
            try { processor.assign(s, category, merchant, learn, type) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _notice.value = "Could not save reviewed SMS. Please retry." }
        }
    }

    fun dismiss(s: UnparsedSms) {
        viewModelScope.launch {
            try { processor.dismiss(s) } catch (e: CancellationException) { throw e } catch (e: Exception) { _notice.value = "Could not dismiss SMS. Please retry." }
        }
    }

    fun dismissAll() {
        viewModelScope.launch {
            try { processor.dismissAll() } catch (e: CancellationException) { throw e } catch (e: Exception) { _notice.value = "Could not dismiss SMS. Please retry." }
        }
    }
}
