package com.keralalottery.print.gold

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keralalottery.print.gold.GoldPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

private val UP_COLOR = Color(0xFF1B8A3A)
private val DOWN_COLOR = Color(0xFFC62828)

private sealed interface FetchState {
    data object Loading : FetchState
    data object Done : FetchState
    data class Error(val message: String) : FetchState
}

@Composable
fun GoldRateScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(GoldRateStore.loadAll(context)) }
    var fetchState by remember { mutableStateOf<FetchState>(FetchState.Loading) }
    // Tracked separately from fetchState: today's rate (AKGSMA) and the backfilled history
    // (keralagold.com) are two different network calls, each visibly loading/erroring on its
    // own instead of one silently swallowing the other's failure.
    var historyState by remember { mutableStateOf<FetchState>(FetchState.Loading) }
    var period by remember { mutableStateOf(GoldPeriod.THIRTY_DAY) }
    var backgroundUri by remember { mutableStateOf<Uri?>(null) }

    fun refresh() {
        fetchState = FetchState.Loading
        historyState = FetchState.Loading
        scope.launch {
            fetchState = try {
                val rate = withContext(Dispatchers.IO) { GoldRateFetcher.fetchTodayRatePerGram() }
                entries = withContext(Dispatchers.IO) { GoldRateStore.recordToday(context, rate) }
                FetchState.Done
            } catch (e: Exception) {
                FetchState.Error(e.message ?: "Could not fetch today's rate.")
            }

            historyState = try {
                val history = withContext(Dispatchers.IO) { GoldRateFetcher.fetchHistoryFromKeralaGold() }
                entries = withContext(Dispatchers.IO) { GoldRateStore.mergeMissing(context, history) }
                FetchState.Done
            } catch (e: Exception) {
                FetchState.Error(e.message ?: "Could not fetch price history from keralagold.com.")
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backgroundUri = uri
    }

    val today = entries.lastOrNull()
    val yesterday = entries.dropLast(1).lastOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            // Extra bottom inset so the last button clears the gesture nav bar on edge-to-edge
            // (targetSdk 36) devices instead of sitting half-hidden behind it.
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("22K Gold Rate — Kerala", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Daily retail rate as published by AKGSMA (All Kerala Gold & Silver Merchants " +
                "Association). History builds up day by day as you use the app.",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val fs = fetchState) {
            is FetchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fetching today's rate…")
            }
            is FetchState.Error -> Column {
                Text("Error: ${fs.message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh() }) { Text("Retry") }
            }
            FetchState.Done -> Unit
        }

        if (today != null) {
            RateCard(today = today, yesterday = yesterday)
        }

        HorizontalDivider()
        Text("Trend", style = MaterialTheme.typography.titleMedium)
        when (val hs = historyState) {
            is FetchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Fetching price history from keralagold.com…", style = MaterialTheme.typography.bodySmall)
            }
            is FetchState.Error -> Column {
                Text(
                    "Could not fetch full history: ${hs.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { refresh() }) { Text("Retry") }
            }
            FetchState.Done -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldPeriod.values().forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(p.label) }
                )
            }
        }
        GoldChart(entries = entries.within(period))

        HorizontalDivider()
        Text("Last 15 days", style = MaterialTheme.typography.titleMedium)
        val last15 = entries.within(GoldPeriod.FIFTEEN_DAY).sortedByDescending { it.date }
        if (last15.isEmpty()) {
            Text("No history yet — check back tomorrow.", style = MaterialTheme.typography.bodySmall)
        } else {
            val byDateAsc = entries.within(GoldPeriod.FIFTEEN_DAY).sortedBy { it.date }
            val changeByDate = byDateAsc.indices.associate { i ->
                val prevRate = if (i > 0) byDateAsc[i - 1].ratePerGram else byDateAsc[i].ratePerGram
                byDateAsc[i].date to (byDateAsc[i].ratePerGram - prevRate)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                last15.forEach { entry ->
                    val change = changeByDate[entry.date] ?: 0
                    HistoryRow(entry, change)
                }
            }
        }

        HorizontalDivider()
        Text("Share as WhatsApp status", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick a background photo (your shop / jewellery photo) and it's overlaid with " +
                "today's rate, ready to share.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { pickBackground.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (backgroundUri == null) "Choose background photo" else "Change background photo")
        }
        Button(
            onClick = {
                val uri = backgroundUri ?: return@Button
                val entry = today ?: return@Button
                scope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) { decodeBitmap(context.contentResolver.openInputStream(uri)) }
                        val card = withContext(Dispatchers.Default) { GoldShareImage.render(bitmap, entry, yesterday) }
                        GoldShareImage.shareToWhatsAppStatus(context, card)
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not create the rate card.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = backgroundUri != null && today != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share today's rate")
        }
    }
}

@Composable
private fun RateCard(today: GoldRateEntry, yesterday: GoldRateEntry?) {
    val delta = yesterday?.let { today.ratePerGram - it.ratePerGram }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(today.date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("1 gram", style = MaterialTheme.typography.titleSmall)
                    Text("₹${indianFormat(today.ratePerGram)}", style = MaterialTheme.typography.headlineMedium)
                }
                Column {
                    Text("8 gram (1 Pavan)", style = MaterialTheme.typography.titleSmall)
                    Text("₹${indianFormat(today.ratePerGram * 8)}", style = MaterialTheme.typography.headlineMedium)
                }
            }
            if (delta != null) {
                val up = delta >= 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = if (up) UP_COLOR else DOWN_COLOR
                    )
                    Text(
                        "₹${indianFormat(kotlin.math.abs(delta))}/g  ·  ₹${indianFormat(kotlin.math.abs(delta) * 8)}/pavan  vs yesterday",
                        color = if (up) UP_COLOR else DOWN_COLOR,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: GoldRateEntry, change: Int) {
    val up = change >= 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(entry.date.format(DateTimeFormatter.ofPattern("dd MMM")), style = MaterialTheme.typography.bodyMedium)
        Text("₹${indianFormat(entry.ratePerGram)}/g", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = if (change == 0) MaterialTheme.colorScheme.onSurfaceVariant else if (up) UP_COLOR else DOWN_COLOR,
                modifier = Modifier.size(18.dp)
            )
            Text(
                if (change == 0) "—" else "₹${indianFormat(kotlin.math.abs(change))}",
                color = if (change == 0) MaterialTheme.colorScheme.onSurfaceVariant else if (up) UP_COLOR else DOWN_COLOR,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun decodeBitmap(stream: java.io.InputStream?): Bitmap {
    return stream?.use { BitmapFactory.decodeStream(it) } ?: error("Could not read the chosen photo.")
}

private fun indianFormat(amount: Int): String {
    val symbols = java.text.DecimalFormatSymbols(java.util.Locale.ENGLISH)
    val format = java.text.DecimalFormat("##,##,##0", symbols)
    return format.format(amount)
}
