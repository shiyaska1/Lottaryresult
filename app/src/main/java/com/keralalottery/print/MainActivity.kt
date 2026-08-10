package com.keralalottery.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.keralalottery.print.calculator.CalculatorScreen
import com.keralalottery.print.data.AppPrefs
import com.keralalottery.print.diary.DiaryScreen
import com.keralalottery.print.education.EducationScreen
import com.keralalottery.print.gold.GoldRateScreen
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.network.KeralaLotterySchedule
import com.keralalottery.print.network.LotteryListing
import com.keralalottery.print.network.OfficialLotteryResultsClient
import com.keralalottery.print.network.UnofficialLotteryResultsClient
import com.keralalottery.print.news.NewsScreen
import com.keralalottery.print.parse.LotteryPdfParser
import com.keralalottery.print.parse.UnofficialResultParser
import com.keralalottery.print.pdf.CompactPdfGenerator
import com.keralalottery.print.pdf.PdfEncryptor
import com.keralalottery.print.pdf.PdfPrinter
import com.keralalottery.print.psc.PscScreen
import com.keralalottery.print.ui.ZoomableImageViewer
import com.keralalottery.print.quicklinks.QuickLinksRow
import com.keralalottery.print.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ListingsState {
    data object Loading : ListingsState
    data class Loaded(val items: List<LotteryListing>) : ListingsState
    data class Error(val message: String) : ListingsState
}

private enum class ResultSource { OFFICIAL, UNOFFICIAL, IMPORTED }

private sealed interface GenerationState {
    data object Idle : GenerationState
    data object Working : GenerationState
    data class Error(val message: String) : GenerationState
    data class Ready(val result: LotteryResult, val file: File, val preview: Bitmap, val source: ResultSource) : GenerationState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Offer the Play update straight away, so users are not left on an old build.
        AppUpdater.check(this)
        val prefs = AppPrefs(this)
        // Start the trial clock automatically on first launch - no registration needed.
        if (prefs.installDateMillis <= 0L) prefs.installDateMillis = System.currentTimeMillis()
        // Trial/licence gate disabled for now - free for everyone until a mobile-number-based
        // licensing scheme replaces this device-ID one. License.kt/AppPrefs.kt/LicenseScreen.kt
        // are left in place to build that on top of.
        val needsLicense = false

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var licensed by remember { mutableStateOf(!needsLicense) }
                    if (licensed) {
                        RootTabs()
                    } else {
                        LicenseScreen(onActivated = { licensed = true })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time the app comes back to the foreground — this is what makes an
        // update actually mandatory: backing out of the Play update screen just returns here
        // and immediately re-blocks, instead of leaving the user on the old build.
        AppUpdater.check(this)
    }
}

private enum class RootTab(val label: String) {
    LOTTERY("ലോട്ടറി ഫലം"), GOLD("Gold Rate"), PSC("PSC"), EDUCATION("Education"),
    CALCULATOR("Calculator"), DIARY("Diary"), NEWS("News")
}

@Composable
private fun RootTabs() {
    var tab by remember { mutableStateOf(RootTab.LOTTERY) }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Scrollable rather than fixed: with 4+ tabs a fixed TabRow starts cramming/wrapping
        // labels on a narrow phone, and this only grows as more tabs get added.
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
            RootTab.values().forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
            }
        }
        HorizontalDivider()
        // Persistent across every tab - quick access to whatever the user reaches for most,
        // without leaving this app to hunt for it on the home screen.
        QuickLinksRow()
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                RootTab.LOTTERY -> LotteryPrintApp()
                RootTab.GOLD -> GoldRateScreen()
                RootTab.PSC -> PscScreen()
                RootTab.EDUCATION -> EducationScreen()
                RootTab.CALCULATOR -> CalculatorScreen()
                RootTab.DIARY -> DiaryScreen()
                RootTab.NEWS -> NewsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LotteryPrintApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var companyName by remember { mutableStateOf("") }
    var genState by remember { mutableStateOf<GenerationState>(GenerationState.Idle) }

    var listingsState by remember { mutableStateOf<ListingsState>(ListingsState.Loading) }
    var selectedListing by remember { mutableStateOf<LotteryListing?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var useUnofficial by remember { mutableStateOf(false) }
    var unofficialDayOffset by remember { mutableStateOf(0) }  // 0 = today, -1 = yesterday

    var manualUri by remember { mutableStateOf<Uri?>(null) }
    var manualName by remember { mutableStateOf<String?>(null) }

    var passwordProtect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val passwordReady = !passwordProtect || password.isNotBlank()

    LaunchedEffect(reloadKey) {
        listingsState = ListingsState.Loading
        listingsState = try {
            val items = withContext(Dispatchers.IO) { OfficialLotteryResultsClient.fetchLatestDraws() }
            selectedListing = items.firstOrNull()
            ListingsState.Loaded(items)
        } catch (e: Exception) {
            ListingsState.Error(e.message ?: "ഫല പട്ടിക ലോഡ് ചെയ്യാൻ കഴിഞ്ഞില്ല.")
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            manualUri = uri
            manualName = queryDisplayName(context, uri)
        }
    }

    fun runGeneration(source: ResultSource, block: suspend () -> LotteryResult) {
        genState = GenerationState.Working
        scope.launch {
            try {
                val (result, file, preview) = withContext(Dispatchers.IO) {
                    val parsed = block()
                    val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                    val outFile = File(outDir, "lottery_result_${System.currentTimeMillis()}.pdf")
                    CompactPdfGenerator.generate(parsed, companyName.trim(), outFile, isUnofficial = source == ResultSource.UNOFFICIAL)
                    // Render the preview from the plain PDF first - Android's PdfRenderer can't
                    // open a password-protected one - then encrypt the file in place afterward,
                    // so both Download and Share end up with the protected copy.
                    val bitmap = renderFirstPage(outFile)
                    if (passwordProtect && password.isNotBlank()) {
                        PdfEncryptor.protect(context, outFile, password)
                    }
                    Triple(parsed, outFile, bitmap)
                }
                genState = GenerationState.Ready(result, file, preview, source)
            } catch (e: Exception) {
                genState = GenerationState.Error(e.message ?: "എന്തോ പിശക് സംഭവിച്ചു.")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ലോട്ടറി ഫലം — ഒറ്റ പേജ് പ്രിന്റ്", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = companyName,
            onValueChange = { companyName = it },
            label = { Text("ഹെഡർ / കമ്പനി പേര്") },
            placeholder = { Text("ഉദാ: ശ്രീ ലക്കി ഏജൻസീസ്") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = passwordProtect, onCheckedChange = { passwordProtect = it })
                Text("PDF പാസ്‌വേഡ് സംരക്ഷിക്കുക")
            }
            if (passwordProtect) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("PDF പാസ്‌വേഡ്") },
                    isError = password.isBlank(),
                    supportingText = { if (password.isBlank()) Text("PDF സംരക്ഷിക്കാൻ ആവശ്യമാണ്") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        HorizontalDivider()
        Text("ഏറ്റവും പുതിയ ഔദ്യോഗിക ഫലം എടുക്കുക", style = MaterialTheme.typography.titleMedium)

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useUnofficial, onCheckedChange = { useUnofficial = it })
                Text("അനൗദ്യോഗിക സ്രോതസ്സ് ഉപയോഗിക്കുക (വേഗത്തിൽ)")
            }
            if (useUnofficial) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = unofficialDayOffset == 0, onClick = { unofficialDayOffset = 0 }, label = { Text("ഇന്ന്") })
                    FilterChip(selected = unofficialDayOffset == -1, onClick = { unofficialDayOffset = -1 }, label = { Text("ഇന്നലെ") })
                }
                // Not necessarily today's draw - the chips above let it target yesterday's
                // instead, so someone can compare the unofficial source against an already-
                // complete official result. Shown here so it's never a surprise which draw is
                // about to be checked.
                val officialItems = (listingsState as? ListingsState.Loaded)?.items.orEmpty()
                val target = KeralaLotterySchedule.listingForDate(
                    java.time.LocalDate.now().plusDays(unofficialDayOffset.toLong()), officialItems
                )
                if (target != null) {
                    Text(
                        "പരിശോധിക്കുന്നത്: ${target.name} (${target.drawCode}) — ${target.date}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        when (val ls = listingsState) {
            is ListingsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("ലോട്ടറി പട്ടിക ലോഡ് ചെയ്യുന്നു…")
            }
            is ListingsState.Error -> Column {
                Text("പിശക്: ${ls.message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reloadKey++ }) { Text("വീണ്ടും ശ്രമിക്കുക") }
            }
            is ListingsState.Loaded -> {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedListing?.let { "${it.name} (${it.drawCode}) — ${it.date}" } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("ലോട്ടറി") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ls.items.forEach { listing ->
                            DropdownMenuItem(
                                text = { Text("${listing.name} (${listing.drawCode}) — ${listing.date}") },
                                onClick = {
                                    selectedListing = listing
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val listing = selectedListing ?: return@Button
                val source = if (useUnofficial) ResultSource.UNOFFICIAL else ResultSource.OFFICIAL
                // Unofficial targets today's (or yesterday's, per the chip above) actual
                // lottery by Kerala's fixed weekly schedule, not whatever the dropdown happens
                // to have selected - which can still be lagging a day behind if the official
                // listing itself hasn't posted today's row yet.
                val officialItems = (listingsState as? ListingsState.Loaded)?.items.orEmpty()
                val unofficialDate = java.time.LocalDate.now().plusDays(unofficialDayOffset.toLong())
                val unofficialTarget = KeralaLotterySchedule.listingForDate(unofficialDate, officialItems) ?: listing
                runGeneration(source) {
                    if (useUnofficial) {
                        try {
                            val url = UnofficialLotteryResultsClient.guessUrl(unofficialTarget)
                            val html = UnofficialLotteryResultsClient.fetchHtml(url)
                            UnofficialResultParser.parseHtml(html)
                        } catch (e: Exception) {
                            // The mirror page itself might not be up yet (404) ahead of the
                            // draw - still print the letterhead from what we already know,
                            // with the same "result coming soon" placeholder as a page that
                            // loaded but had nothing on it yet.
                            UnofficialResultParser.waitingResult(unofficialTarget)
                        }
                    } else {
                        val bytes = OfficialLotteryResultsClient.fetchResultPdf(listing.itemId)
                        LotteryPdfParser.parsePdfBytes(context, bytes)
                    }
                }
            },
            enabled = selectedListing != null && genState !is GenerationState.Working && passwordReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (useUnofficial) "അനൗദ്യോഗിക സ്രോതസ്സിൽ നിന്ന് എടുത്ത് തയ്യാറാക്കുക" else "ഏറ്റവും പുതിയ ഫലം എടുത്ത് ഒറ്റ പേജ് തയ്യാറാക്കുക")
        }

        HorizontalDivider()
        Text("അല്ലെങ്കിൽ ഒരു PDF ഫയൽ നേരിട്ട് ചേർക്കുക", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (manualName == null) "ഔദ്യോഗിക ഫല PDF തിരഞ്ഞെടുക്കുക" else "PDF മാറ്റുക")
        }
        manualName?.let { Text("തിരഞ്ഞെടുത്തത്: $it", style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val uri = manualUri ?: return@Button
                runGeneration(ResultSource.IMPORTED) { LotteryPdfParser.parsePdf(context, uri) }
            },
            enabled = manualUri != null && genState !is GenerationState.Working && passwordReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ചേർത്ത PDF-ൽ നിന്ന് തയ്യാറാക്കുക")
        }

        when (val s = genState) {
            is GenerationState.Working -> Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            is GenerationState.Error -> Text(
                "പിശക്: ${s.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            is GenerationState.Ready -> {
                HorizontalDivider()
                Text("പ്രിവ്യൂ", style = MaterialTheme.typography.titleMedium)
                SourceBadge(source = s.source, tierCount = s.result.tiers.size)
                // Actions come before the (often tall) preview image so they stay reachable
                // without scrolling all the way down past it, near the phone's nav bar.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Button(onClick = {
                        val name = "Lottery_Result_${System.currentTimeMillis()}.pdf"
                        PdfPrinter.saveToDownloads(context, s.file, name)
                        Toast.makeText(context, "ഡൗൺലോഡ്‌സിലേക്ക് സേവ് ചെയ്തു", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("PDF")
                    }
                    OutlinedButton(onClick = {
                        val name = "Lottery_Result_${System.currentTimeMillis()}.jpg"
                        PdfPrinter.saveJpgToDownloads(context, s.preview, name)
                        Toast.makeText(context, "ഡൗൺലോഡ്‌സിലേക്ക് സേവ് ചെയ്തു", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("JPG")
                    }
                    OutlinedButton(onClick = { PdfPrinter.share(context, s.file) }) {
                        Text("ഷെയർ ചെയ്യുക")
                    }
                }
                var viewingFullScreen by remember { mutableStateOf(false) }
                Image(
                    bitmap = s.preview.asImageBitmap(),
                    contentDescription = "തയ്യാറാക്കിയ ഫലത്തിന്റെ പ്രിവ്യൂ - ഓരോ നമ്പറും സൂം ചെയ്ത് പരിശോധിക്കാൻ ടാപ്പ് ചെയ്യുക",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable { viewingFullScreen = true }
                )
                if (viewingFullScreen) {
                    ZoomableImageViewer(bitmap = s.preview, onDismiss = { viewingFullScreen = false })
                }
            }
            GenerationState.Idle -> Unit
        }
    }
}

/** Tells the user which source the currently shown result came from - the printed page looks
 * identical either way, but people need to know whether to trust it as final or expect it to
 * fill in further (unofficial results are often posted early with only some prizes). */
@Composable
private fun SourceBadge(source: ResultSource, tierCount: Int) {
    val (text, color) = when (source) {
        ResultSource.OFFICIAL -> "ഔദ്യോഗിക സർക്കാർ പോർട്ടൽ" to MaterialTheme.colorScheme.primary
        ResultSource.IMPORTED -> "നേരിട്ട് ചേർത്ത PDF" to MaterialTheme.colorScheme.primary
        ResultSource.UNOFFICIAL -> (
            if (tierCount == 0) "അനൗദ്യോഗിക സ്രോതസ്സ് (keralalotteries.net) - ഇതുവരെ പ്രഖ്യാപിച്ചിട്ടില്ല, കുറച്ച് കഴിഞ്ഞ് നോക്കുക"
            else "അനൗദ്യോഗിക സ്രോതസ്സ് (keralalotteries.net) - $tierCount സമ്മാന വിഭാഗ${if (tierCount == 1) "ം" else "ങ്ങൾ"} കണ്ടെത്തി, അപൂർണ്ണമാകാം"
            ) to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text("സ്രോതസ്സ്: $text", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment
        } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }
}

private fun renderFirstPage(file: File): Bitmap {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(0).use { page ->
                // 2x scale for a crisp on-screen preview of the vector text.
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                return bitmap
            }
        }
    }
}
