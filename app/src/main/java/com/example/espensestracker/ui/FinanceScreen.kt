package com.example.espensestracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.espensestracker.data.*
import com.example.espensestracker.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun FinanceScreen(vm: ExpenseViewModel) {
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val categories by vm.customCategories.collectAsStateWithLifecycle()
    val budgets by vm.monthlyBudgets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var walletEditor by remember { mutableStateOf(false) }
    var editingWallet by remember { mutableStateOf<Wallet?>(null) }
    var budgetEditor by remember { mutableStateOf<String?>(null) }
    var categoryEditor by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val range = monthRange(offset)
    val key = monthKey(range.start)
    val monthTx = transactions.filter { it.timestamp in range.start..minOf(range.end, System.currentTimeMillis()) }
    val monthBudgets = budgets.filter { it.month == key }
    val cats = (CATEGORY_LIST.map { it.name } + categories.map { it.name } + transactions.map { it.category } + monthBudgets.map { it.category }).distinct().filter { it != TRANSFER_CATEGORY && it != OVERALL_BUDGET }.sorted()
    fun submit(action: suspend () -> Unit, success: () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action(); success() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not save" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Wallet & Planning", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Wallets", "Budgets", "Categories").forEachIndexed { i, label ->
                FilterChip(tab == i, { tab = i }, { Text(label) }, colors = chipColors())
            }
        }
        when (tab) {
            0 -> {
                Panel {
                    Text("Your manual ledger", fontWeight = FontWeight.Bold)
                    Text("Opening balance is the amount before all linked transactions. Link transactions in their editor. Credit card debt is negative. These are calculated, not live balances.", fontSize = 12.sp, color = Muted)
                    Button({ editingWallet = null; error = null; walletEditor = true }, Modifier.fillMaxWidth()) { Text("Add wallet") }
                    if (wallets.isEmpty()) Text("Create a bank, card, cash or UPI wallet to start.", color = Muted)
                    wallets.forEach { w ->
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(w.name, fontWeight = FontWeight.SemiBold)
                                Text(w.kind, fontSize = 12.sp, color = Muted)
                                Text(formatMoney(w.ledgerBalance(transactions.filter { it.timestamp <= System.currentTimeMillis() }) / 100.0), fontWeight = FontWeight.Bold)
                            }
                            TextButton({ editingWallet = w; error = null; walletEditor = true }) { Text("Edit") }
                        }
                    }
                }
                AccountsPanel(balances)
                Text("SMS-reported balances are shown separately and are not added to the manual ledger.", fontSize = 12.sp, color = Muted)
            }
            1 -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ offset-- }) { Text("Previous") }
                    Text(range.label, modifier = Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                    TextButton({ offset++ }) { Text("Next") }
                }
                Panel {
                    val overall = monthBudgets.firstOrNull { it.category == OVERALL_BUDGET }
                    val limit = overall?.limitPaise ?: if (offset == 0) paise(AppSettings.budget) else null
                    BudgetLine("Overall budget", monthTx.totalSpendPaise(), limit) { error = null; budgetEditor = OVERALL_BUDGET }
                    Text(if (overall == null && offset == 0) "Using default budget from Settings. Set a monthly override to preserve this month's target." else "Monthly override applies only to this month.", fontSize = 12.sp, color = Muted)
                }
                cats.forEach { cat ->
                    Panel { BudgetLine(cat, monthTx.filter { it.category == cat }.totalSpendPaise(), monthBudgets.firstOrNull { it.category == cat }?.limitPaise) { error = null; budgetEditor = cat } }
                }
            }
            else -> {
                Button({ error = null; categoryEditor = true }, Modifier.fillMaxWidth()) { Text("Add custom category") }
                Panel {
                    Text("Built-in categories", fontWeight = FontWeight.Bold)
                    CATEGORY_LIST.forEach { Text(it.name, Modifier.padding(vertical = 4.dp)) }
                    HorizontalDivider()
                    Text("Your categories", fontWeight = FontWeight.Bold)
                    if (categories.isEmpty()) Text("No custom categories yet", color = Muted)
                    categories.forEach { Text(it.name, Modifier.padding(vertical = 4.dp)) }
                    Text("Custom categories appear in transaction and SMS review forms. Renaming/deletion is deferred to avoid changing historical classifications.", fontSize = 12.sp, color = Muted)
                }
            }
        }
    }
    if (walletEditor) {
        var name by remember(editingWallet) { mutableStateOf(editingWallet?.name.orEmpty()) }
        var kind by remember(editingWallet) { mutableStateOf(editingWallet?.kind ?: WALLET_TYPES.first()) }
        var opening by remember(editingWallet) { mutableStateOf(editingWallet?.let { java.math.BigDecimal.valueOf(it.openingPaise, 2).toPlainString() } ?: "0") }
        AlertDialog(onDismissRequest = { if (!busy) walletEditor = false }, title = { Text(if (editingWallet == null) "New wallet" else "Edit wallet") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Wallet name") }, singleLine = true)
                WALLET_TYPES.forEach { k -> Row { RadioButton(kind == k, { kind = k }); Text(k, Modifier.padding(top = 14.dp)) } }
                OutlinedTextField(opening, { opening = it }, label = { Text("Opening balance (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                error?.let { Text(it, color = Debit) }
            }
        }, confirmButton = { TextButton({ submit({ val v = opening.toBigDecimalOrNull()?.takeIf { it.stripTrailingZeros().scale() <= 2 }?.movePointRight(2)?.longValueExact() ?: kotlin.error("Enter an amount with up to two decimals"); vm.saveWallet(editingWallet?.id, name, kind, v) }, { walletEditor = false }) }, enabled = !busy) { Text("Save") } }, dismissButton = { TextButton({ walletEditor = false }, enabled = !busy) { Text("Cancel") } })
    }
    budgetEditor?.let { cat ->
        val existing = monthBudgets.firstOrNull { it.category == cat }
        var amount by remember(cat, key) { mutableStateOf(existing?.let { java.math.BigDecimal.valueOf(it.limitPaise, 2).toPlainString() }.orEmpty()) }
        AlertDialog(onDismissRequest = { if (!busy) budgetEditor = null }, title = { Text(if (cat == OVERALL_BUDGET) "Overall budget" else cat) }, text = {
            Column { Text(range.label); OutlinedTextField(amount, { amount = it }, label = { Text("Monthly limit (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true); error?.let { Text(it, color = Debit) }; if (existing != null) TextButton({ submit({ vm.setMonthlyBudget(key, cat, null) }, { budgetEditor = null }) }, enabled = !busy) { Text("Remove budget") } }
        }, confirmButton = { TextButton({ submit({ vm.setMonthlyBudget(key, cat, paise(parseMoney(amount) ?: kotlin.error("Enter a positive amount with up to two decimals"))) }, { budgetEditor = null }) }, enabled = !busy) { Text("Save") } }, dismissButton = { TextButton({ budgetEditor = null }, enabled = !busy) { Text("Cancel") } })
    }
    if (categoryEditor) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) categoryEditor = false }, title = { Text("New category") }, text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Category name") }); error?.let { Text(it, color = Debit) } } }, confirmButton = { TextButton({ submit({ vm.addCategory(name) }, { categoryEditor = false }) }, enabled = !busy) { Text("Add") } }, dismissButton = { TextButton({ categoryEditor = false }, enabled = !busy) { Text("Cancel") } })
    }
}

@Composable
private fun BudgetLine(name: String, spent: Long, limit: Long?, edit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text("${formatMoney(spent / 100.0)} / ${limit?.let { formatMoney(it / 100.0) } ?: "Not set"}", fontSize = 12.sp)
        }
        TextButton(edit) { Text(if (limit == null) "Set" else "Edit") }
    }
    if (limit != null && limit > 0) {
        val ratio = spent.toDouble() / limit
        LinearProgressIndicator(progress = { ratio.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = if (ratio > 1) Debit else Gold)
        Text("${"%.1f".format(ratio.coerceAtLeast(0.0) * 100)}% used • ${formatMoney(kotlin.math.abs(limit - spent) / 100.0)} ${if (spent > limit) "over" else "remaining"}", fontSize = 12.sp, color = if (ratio > 1) Debit else Muted)
    }
}
