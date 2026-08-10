package com.keralalottery.print.calculator

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keralalottery.print.data.AppDatabase
import com.keralalottery.print.data.SavedCalc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private fun money(v: Double): String {
    val symbols = DecimalFormatSymbols(Locale.ENGLISH)
    return DecimalFormat("##,##,##0.##", symbols).format(v)
}

private enum class CalcTab { NEW, SAVED }

@Composable
fun CalculatorScreen() {
    var tab by remember { mutableStateOf(CalcTab.NEW) }

    // Hoisted above the New/Saved tab switch: TapeCalculator used to remember() this itself,
    // which meant it got destroyed and reset to empty every time the tab left composition -
    // an accidental tap on "Saved" silently wiped out whatever was being typed. Living here
    // instead, it survives switching tabs.
    val entries = remember { mutableStateListOf<Double>() }
    var input by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingLabel by remember { mutableStateOf("") }

    fun startNew() {
        entries.clear()
        input = ""
        editingId = null
        editingLabel = ""
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Calculator", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty() || input.isNotEmpty() || editingId != null) {
                TextButton(onClick = { startNew() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(selected = tab == CalcTab.NEW, onClick = { tab = CalcTab.NEW }, text = { Text("New") })
            Tab(selected = tab == CalcTab.SAVED, onClick = { tab = CalcTab.SAVED }, text = { Text("Saved") })
        }
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                CalcTab.NEW -> TapeCalculator(
                    entries = entries,
                    input = input,
                    onInputChange = { input = it },
                    editingId = editingId,
                    editingLabel = editingLabel,
                    onSaved = {
                        tab = CalcTab.SAVED
                        startNew()
                    }
                )
                CalcTab.SAVED -> SavedCalcsList(
                    onEdit = { calc ->
                        entries.clear()
                        entries.addAll(calc.amountList)
                        input = ""
                        editingId = calc.id
                        editingLabel = calc.label
                        tab = CalcTab.NEW
                    }
                )
            }
        }
    }
}

@Composable
private fun TapeCalculator(
    entries: SnapshotStateList<Double>,
    input: String,
    onInputChange: (String) -> Unit,
    editingId: Long?,
    editingLabel: String,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).savedCalcDao() }
    val scope = rememberCoroutineScope()

    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val total = entries.sum()

    var showMulDivDialog by remember { mutableStateOf(false) }
    var mulDivOp by remember { mutableStateOf('*') }
    var mulDivFactor by remember { mutableStateOf("") }
    var showNoAmountAlert by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveLabel by remember { mutableStateOf("") }

    fun add(sign: Int) {
        val v = input.toDoubleOrNull()
        if (v != null && v > 0.0) entries.add(v * sign)
        onInputChange("")
        focus.requestFocus()
    }

    LaunchedEffect(entries.size) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }

    // Pushes the amount box + operator buttons above the keyboard instead of letting it cover
    // them - the tape above (weight(1f)) shrinks to make room, same as POS billing's CalculatorDialog.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        "Type an amount, then + or −",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        textAlign = TextAlign.Center
                    )
                }
                entries.forEachIndexed { i, v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (v < 0) "-" else if (i == 0) " " else "+",
                            fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                        )
                        Text(
                            money(abs(v)),
                            modifier = Modifier.weight(1f),
                            fontSize = 24.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                if (entries.isNotEmpty()) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 8.dp), thickness = 3.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("=", fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            money(total),
                            modifier = Modifier.weight(1f),
                            fontSize = 36.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                            maxLines = 1, softWrap = false,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (editingId != null) {
                Text(
                    "Editing a saved tape — Save will update it.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { onInputChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    label = { Text("Amount") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 22.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add(1) }),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                IconButton(onClick = {
                    if (input.isNotEmpty()) onInputChange("") else if (entries.isNotEmpty()) entries.removeAt(entries.lastIndex)
                }) { Icon(Icons.Filled.Backspace, contentDescription = "Remove last") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    val cur = input.toDoubleOrNull()
                    if (cur == null) { showNoAmountAlert = true; return@OutlinedButton }
                    mulDivOp = '*'; mulDivFactor = ""; showMulDivDialog = true
                }) { Text("×", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    val cur = input.toDoubleOrNull()
                    if (cur == null) { showNoAmountAlert = true; return@OutlinedButton }
                    mulDivOp = '/'; mulDivFactor = ""; showMulDivDialog = true
                }) { Text("÷", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { add(-1) }) {
                    Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Button(modifier = Modifier.weight(1f), onClick = { add(1) }) {
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = { saveLabel = editingLabel; showSaveDialog = true },
                enabled = entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(if (editingId != null) "Update this tape" else "Save this tape") }
        }
    }

    if (showMulDivDialog) {
        AlertDialog(
            onDismissRequest = { showMulDivDialog = false },
            title = { Text(if (mulDivOp == '*') "Multiply amount" else "Divide amount") },
            text = {
                Column {
                    OutlinedTextField(
                        value = mulDivFactor,
                        onValueChange = { mulDivFactor = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Enter number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (mulDivOp == '/' && mulDivFactor.toDoubleOrNull() == 0.0 && mulDivFactor.isNotBlank()) {
                        Text("Cannot divide by zero", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val factor = mulDivFactor.toDoubleOrNull()
                    val cur = input.toDoubleOrNull()
                    if (factor == null || cur == null || (mulDivOp == '/' && factor == 0.0)) {
                        showMulDivDialog = false; return@Button
                    }
                    onInputChange(money(if (mulDivOp == '*') cur * factor else cur / factor))
                    showMulDivDialog = false
                    focus.requestFocus()
                }) { Text("Apply") }
            },
            dismissButton = { OutlinedButton(onClick = { showMulDivDialog = false }) { Text("Cancel") } }
        )
    }

    if (showNoAmountAlert) {
        AlertDialog(
            onDismissRequest = { showNoAmountAlert = false },
            title = { Text("No amount") },
            text = { Text("Enter an amount first in the Amount field before using × or ÷.") },
            confirmButton = { Button(onClick = { showNoAmountAlert = false }) { Text("OK") } }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(if (editingId != null) "Update tape" else "Save tape") },
            text = {
                OutlinedTextField(
                    value = saveLabel,
                    onValueChange = { saveLabel = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val snapshot = entries.toList()
                    val label = saveLabel
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (editingId != null) {
                                dao.update(
                                    SavedCalc(
                                        id = editingId,
                                        dateMillis = System.currentTimeMillis(),
                                        amounts = SavedCalc.pack(snapshot),
                                        total = snapshot.sum(),
                                        label = label
                                    )
                                )
                            } else {
                                dao.insert(
                                    SavedCalc(
                                        dateMillis = System.currentTimeMillis(),
                                        amounts = SavedCalc.pack(snapshot),
                                        total = snapshot.sum(),
                                        label = label
                                    )
                                )
                            }
                        }
                        showSaveDialog = false
                        onSaved()
                    }
                }) { Text(if (editingId != null) "Update" else "Save") }
            },
            dismissButton = { OutlinedButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SavedCalcsList(onEdit: (SavedCalc) -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).savedCalcDao() }
    val scope = rememberCoroutineScope()
    val saved by dao.observeAll().collectAsState(initial = emptyList())
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    if (saved.isEmpty()) {
        Text("No saved calculations yet.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(saved, key = { it.id }) { calc ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        calc.label.ifBlank { "(no label)" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(dateFmt.format(Date(calc.dateMillis)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Total: ${money(calc.total)}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(calc) }) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }
                        TextButton(onClick = {
                            val lines = buildString {
                                if (calc.label.isNotBlank()) appendLine(calc.label)
                                appendLine(dateFmt.format(Date(calc.dateMillis)))
                                calc.amountList.forEach { v -> appendLine((if (v < 0) "- " else "+ ") + money(abs(v))) }
                                appendLine("= ${money(calc.total)}")
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, lines)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share tape"))
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share")
                        }
                        TextButton(onClick = { confirmDeleteId = calc.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }

    val toDelete = confirmDeleteId
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete this tape?") },
            confirmButton = {
                Button(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { dao.delete(toDelete) } }
                    confirmDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDeleteId = null }) { Text("Cancel") } }
        )
    }
}
