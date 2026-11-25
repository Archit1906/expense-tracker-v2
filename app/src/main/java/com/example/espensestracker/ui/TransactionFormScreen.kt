package com.example.espensestracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import com.example.espensestracker.data.*
import androidx.compose.material.icons.filled.DateRange
import com.example.espensestracker.sms.SmsParser
import com.example.espensestracker.util.mergeDate
import com.example.espensestracker.util.utcMidnight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.espensestracker.util.parseMoney
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Used for both "new transaction" (id = -1) and "expense detail / edit". */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionFormScreen(vm: ExpenseViewModel, id: Long, onDone: () -> Unit) {
    var existing by remember { mutableStateOf<Transaction?>(null) }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TYPE_DEBIT) }
    var transfer by remember { mutableStateOf(false) }
    var walletId by remember { mutableStateOf<String?>(null) }
    var destinationId by remember { mutableStateOf<String?>(null) }
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val customCategories by vm.customCategories.collectAsStateWithLifecycle()
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CATEGORY_LIST.first().name) }
    var note by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf(false) }
    var learn by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(id > 0) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var dateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id > 0) {
            try { vm.get(id)?.let { t ->
            existing = t; amount = java.math.BigDecimal.valueOf(t.amount).stripTrailingZeros().toPlainString(); type = t.type; transfer = t.isTransfer; walletId = t.walletId; destinationId = t.destinationWalletId
            merchant = t.merchant ?: ""; category = t.category; note = t.note ?: ""; tax = t.taxDeductible; dateMillis = t.timestamp
            } ?: run { error = "Transaction not found" }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Could not load transaction" }
            finally { loading = false }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showDate) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = utcMidnight(dateMillis),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= utcMidnight(System.currentTimeMillis())
                }
            )
            DatePickerDialog(
                onDismissRequest = { showDate = false },
                confirmButton = { TextButton({ state.selectedDateMillis?.let { dateMillis = minOf(mergeDate(dateMillis, it), System.currentTimeMillis()) }; showDate = false }) { Text("OK") } },
                dismissButton = { TextButton({ showDate = false }) { Text("Cancel") } }
            ) { DatePicker(state) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text(if (existing == null) "New Transaction" else "Expense Detail", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                existing?.let { Text("TRANSACTION #TXN-${it.id}", fontSize = 11.sp, color = Muted) }
            }
        }

        Panel {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TYPE_DEBIT to "Expense", TYPE_CREDIT to "Income", TYPE_REFUND to "Refund", TYPE_REIMBURSEMENT to "Reimbursement").forEach { (value, label) ->
                    FilterChip(!transfer && type == value, { type = value; transfer = false; destinationId = null; if (category == TRANSFER_CATEGORY) category = "Other" }, { Text(label) }, colors = chipColors())
                }
                FilterChip(transfer && type == TYPE_DEBIT, { type = TYPE_DEBIT; transfer = true; category = TRANSFER_CATEGORY }, { Text("Transfer out") }, colors = chipColors())
                FilterChip(transfer && type == TYPE_CREDIT, { type = TYPE_CREDIT; transfer = true; destinationId = null; category = TRANSFER_CATEGORY }, { Text("Transfer in") }, colors = chipColors())
            }
            if (transfer) Text("Transfers do not count as spending. Link both wallets for a single internal transfer. If the incoming payment is already recorded separately, leave the destination unassigned to avoid counting it twice.", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Text("TOTAL AMOUNT", fontSize = 11.sp, color = Muted)
            OutlinedTextField(
                amount, { amount = it; error = null },
                Modifier.fillMaxWidth(), prefix = { Text("₹ ", fontSize = 24.sp, color = Gold) },
                textStyle = LocalTextStyle.current.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = error != null, supportingText = error?.let { m -> { Text(m) } }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ showDate = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DateRange, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(dateMillis)))
            }
        }

        existing?.takeIf { it.source == SOURCE_SMS }?.let { t ->
            Panel(color = PanelBg) {
                val acct = t.account?.let { "${SmsParser.bankName(t.sender.orEmpty())} •• $it" } ?: "Unknown"
                Text("TRANSACTION TELEMETRY", fontSize = 11.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Text("Source account: $acct", fontSize = 13.sp)
                t.refNo?.let { Text("UTR / Ref: $it", fontSize = 13.sp) }
                Spacer(Modifier.height(8.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = CardBg) {
                    Text(t.rawSms.orEmpty(), Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        Panel {
            Text(if (transfer && type == TYPE_DEBIT) "FROM WALLET" else "WALLET", fontSize = 11.sp, color = Muted)
            WalletPicker(wallets, walletId) { walletId = it; if (it == destinationId || it == null) destinationId = null }
            if (transfer && type == TYPE_DEBIT) {
                Text("TO WALLET (optional)", fontSize = 11.sp, color = Muted)
                WalletPicker(wallets.filter { it.id != walletId }, destinationId) { destinationId = it }
            }
            Text("Optional. Assign from Wallet tab; SMS balances remain separate.", fontSize = 12.sp, color = Muted)
        }
        Panel {
            Text("CATEGORY", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (CATEGORY_LIST + customCategories.map { catOf(it.name) } + listOfNotNull(existing?.category).filter { name -> CATEGORY_LIST.none { it.name == name } && customCategories.none { it.name == name } }.map(::catOf)).filter { transfer || it.name != TRANSFER_CATEGORY }.forEach { c ->
                    FilterChip(category == c.name, { category = c.name }, { Text(c.name) }, leadingIcon = { Icon(c.icon, null, Modifier.size(18.dp)) }, colors = chipColors())
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(merchant, { merchant = it }, Modifier.fillMaxWidth(), label = { Text("Merchant / payee name") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                note, { if (it.length <= 200) note = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Personal notes & purpose") }, supportingText = { Text("${note.length}/200") }
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mark as Tax Deductible", fontWeight = FontWeight.SemiBold)
                    Text("Tags transaction for quarterly deduction summaries", fontSize = 12.sp, color = Muted)
                }
                Switch(tax, { tax = it }, colors = SwitchDefaults.colors(checkedTrackColor = Gold))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remember for this merchant", fontWeight = FontWeight.SemiBold)
                    Text("Applies this category only to future payments to this merchant", fontSize = 12.sp, color = Muted)
                }
                Switch(learn && !transfer, { learn = it }, enabled = !transfer, colors = SwitchDefaults.colors(checkedTrackColor = Gold))
            }
        }

        Button(
            onClick = {
                val v = parseMoney(amount)
                if (v == null) error = "Enter a positive amount with up to 2 decimals" else {
                    busy = true
                    scope.launch {
                    try { vm.save(
                        (existing ?: Transaction(amountPaise = com.example.espensestracker.util.paise(v))).copy(
                            amountPaise = com.example.espensestracker.util.paise(v), type = type, merchant = merchant.trim().ifBlank { null },
                            category = category, isTransfer = transfer, walletId = walletId, destinationWalletId = if (transfer && type == TYPE_DEBIT) destinationId else null, note = note.trim().ifBlank { null }, taxDeductible = tax, timestamp = dateMillis
                        ),
                        learn && !transfer && merchant.isNotBlank()
                    )
                    onDone()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not save transaction" } finally { busy = false }
                    }
                }
            },
            Modifier.fillMaxWidth().height(52.dp), enabled = !busy && !loading && (id <= 0 || existing != null), colors = ButtonDefaults.buttonColors(containerColor = Gold)
        ) { Text(if (existing == null) "Save Transaction" else "Save Changes", fontWeight = FontWeight.SemiBold) }

        existing?.let { t ->
            TextButton({ confirmDelete = true }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.textButtonColors(contentColor = Debit)) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete Transaction")
            }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("Delete transaction?") },
            text = { Text("This removes the transaction. The original imported SMS fingerprint will be ignored on rescan.") },
            confirmButton = { TextButton({
                confirmDelete = false
                busy = true
                scope.launch {
                    try { existing?.let { vm.delete(it) }; onDone() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "Could not delete transaction" }
                    finally { busy = false }
                }
            }) { Text("Delete", color = Debit) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WalletPicker(wallets: List<Wallet>, selected: String?, onChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(wallets.firstOrNull { it.id == selected }?.name ?: "Unassigned") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { onChange(null); expanded = false })
            wallets.forEach { w -> DropdownMenuItem(text = { Text(w.name) }, onClick = { onChange(w.id); expanded = false }) }
        }
    }
}
