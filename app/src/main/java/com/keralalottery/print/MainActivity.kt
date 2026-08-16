package com.keralalottery.print

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keralalottery.print.calculator.CalculatorScreen
import com.keralalottery.print.data.AppPrefs
import com.keralalottery.print.data.License
import com.keralalottery.print.diary.DiaryScreen
import com.keralalottery.print.education.EducationScreen
import com.keralalottery.print.gold.GoldRateScreen
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.network.KeralaLotterySchedule
import com.keralalottery.print.network.LotteryListing
import com.keralalottery.print.network.OfficialLotteryResultsClient
import com.keralalottery.print.network.UnofficialLotteryResultsClient
import com.keralalottery.print.network.UnofficialLotteryResultsClient2
import com.keralalottery.print.news.NewsScreen
import com.keralalottery.print.parse.LotteryPdfParser
import com.keralalottery.print.parse.UnofficialResultParser
import com.keralalottery.print.parse.UnofficialResultParser2
import com.keralalottery.print.pdf.CompactPdfGenerator
import com.keralalottery.print.pdf.CompactPdfGeneratorV2
import com.keralalottery.print.pdf.PdfPrinter
import com.keralalottery.print.psc.PscScreen
import com.keralalottery.print.search.findTicketMatches
import com.keralalottery.print.ui.ZoomableImageViewer
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

private enum class ResultSource { OFFICIAL, UNOFFICIAL }

private data class GenResult(val result: LotteryResult, val file: File, val preview: Bitmap, val gridFontSize: Float?)

private sealed interface GenerationState {
    data object Idle : GenerationState
    data object Working : GenerationState
    data class Error(val message: String) : GenerationState
    /** [gridFontSize] is only set for a Format 2 result - what its grid tiers' auto-fit (or a
     * manual adjustment) landed on, shown next to the preview so it can be nudged in place. */
    data class Ready(
        val result: LotteryResult,
        val file: File,
        val preview: Bitmap,
        val source: ResultSource,
        val isFormat2: Boolean,
        val gridFontSize: Float? = null
    ) : GenerationState
}

/** One matched ticket, flattened with which draw/source it came from - a multi-number search
 * can turn up matches from several different draws, so each row carries its own header info
 * rather than the whole result sharing one. */
private data class FoundMatch(
    val query: String,
    val lotteryName: String,
    val drawNumber: String,
    val drawDate: String,
    val source: ResultSource,
    val tierLabel: String,
    val amount: String,
    val number: String,
    val place: String
)

private sealed interface SearchState {
    data object Idle : SearchState
    data object Working : SearchState
    data class Error(val message: String) : SearchState
    /** [notFoundQueries] is whatever's left unmatched after this pass - shown alongside any
     * matches found, with an option to look [depthTried] + 1 week(s) further back for those. */
    data class Done(val matches: List<FoundMatch>, val notFoundQueries: List<String>, val depthTried: Int) : SearchState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Offer the Play update straight away, so users are not left on an old build.
        AppUpdater.check(this)
        val prefs = AppPrefs(this)
        // Start the trial clock automatically on first launch - no registration needed.
        if (prefs.installDateMillis <= 0L) prefs.installDateMillis = System.currentTimeMillis()
        // 30-day trial, then a blocking activation-key prompt - same device-locked HMAC scheme
        // as the POS Billing app, so the same key-generator tool issues keys for both.
        val needsLicense = !prefs.licensed && License.trialExpired(prefs.installDateMillis)

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
        CompanyBanner()
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

private const val COMPANY_PHONE = "9961128378"

/** Persistent across every tab, where the quick-links shortcut row used to be - the shop's own
 * identity/contact instead, tap-to-call so a customer can ring the shop straight from here.
 * Two tight lines on a tinted background rather than a full card, so it reads as a proper
 * letterhead strip without eating much vertical space. */
@Composable
private fun CompanyBanner() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$COMPANY_PHONE"))) }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "MOBI CARE COMPUTERS",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PERIGALA, ERNAKULAM  •  ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Icon(
                Icons.Filled.Call,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                " $COMPANY_PHONE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
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
    var useUnofficial by remember { mutableStateOf(true) }
    var unofficialDayOffset by remember { mutableStateOf(0) }  // 0 = today, -1 = yesterday

    var searchQuery by remember { mutableStateOf("") }
    var searchState by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    // Quick search: only today's and yesterday's lottery, instead of the whole week - the common
    // case (checking a ticket you just bought), so it defaults on.
    var quickSearch by remember { mutableStateOf(true) }

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

    // Resolves which listing "unofficial" (and search) actually targets - today's (or
    // yesterday's, per the chip below) actual lottery by Kerala's fixed weekly schedule, not
    // whatever the dropdown happens to have selected, which can still be lagging a day behind if
    // the official listing itself hasn't posted today's row yet.
    fun unofficialTargetOrFallback(): LotteryListing? {
        val fallback = selectedListing
        val officialItems = (listingsState as? ListingsState.Loaded)?.items.orEmpty()
        val unofficialDate = java.time.LocalDate.now().plusDays(unofficialDayOffset.toLong())
        return KeralaLotterySchedule.listingForDate(unofficialDate, officialItems) ?: fallback
    }

    // Ticket search: checks every lottery, not just whatever the dropdown has selected - most
    // recent draw first (today's own lottery, then yesterday's, walking back through the rest of
    // the week's 7 lotteries, unless "quick search" narrows it to just those first two), and for
    // each one tries every source in order: Source 2 first (always first preference), then
    // Source 1 - which turns out to often be the most reliably up to date of the three (Source
    // 2's page has no date parameter and can keep serving last week's result for days after a
    // new draw) - then the real official PDF if that exact draw has actually been posted.
    // [queries] can be several numbers at once (someone checking every ticket they bought); a
    // draw is only re-checked against whichever of them haven't already matched somewhere else,
    // and sources beyond the first are only tried while some queries still haven't matched this
    // particular draw. [depth] > 1 means "check an older date too" - each step shifts every
    // lottery's target one whole week further into the past.
    suspend fun searchOneDraw(target: LotteryListing, officialItems: List<LotteryListing>, depth: Int, queries: List<String>): List<FoundMatch> {
        val found = mutableListOf<FoundMatch>()
        var remaining = queries

        fun record(result: LotteryResult, source: ResultSource) {
            val matches = result.findTicketMatches(remaining)
            if (matches.isEmpty()) return
            val h = result.header
            matches.forEach { m -> found += FoundMatch(m.matchedQuery, h.lotteryName, h.drawNumber, h.drawDate, source, m.tierLabel, m.amount, m.number, m.place) }
            remaining = remaining - matches.map { it.matchedQuery }.toSet()
        }

        if (depth == 1 && remaining.isNotEmpty()) {
            runCatching {
                val html = UnofficialLotteryResultsClient2.fetchHtml(UnofficialLotteryResultsClient2.guessUrl(target))
                UnofficialResultParser2.parseHtml(html, target)
            }.getOrNull()?.takeIf { it.tiers.isNotEmpty() }?.let { record(it, ResultSource.UNOFFICIAL) }
        }
        if (remaining.isNotEmpty()) {
            runCatching {
                val html = UnofficialLotteryResultsClient.fetchHtml(UnofficialLotteryResultsClient.resolveUrl(target))
                UnofficialResultParser.parseHtml(html)
            }.getOrNull()?.takeIf { it.tiers.isNotEmpty() }?.let { record(it, ResultSource.UNOFFICIAL) }
        }
        if (remaining.isNotEmpty()) {
            officialItems.find { it.drawCode == target.drawCode }?.let { real ->
                runCatching {
                    val bytes = OfficialLotteryResultsClient.fetchResultPdf(real.itemId)
                    LotteryPdfParser.parsePdfBytes(context, bytes)
                }.getOrNull()?.takeIf { it.tiers.isNotEmpty() }?.let { record(it, ResultSource.OFFICIAL) }
            }
        }
        return found
    }

    fun runSearch(depth: Int, queriesOverride: List<String>? = null, previousMatches: List<FoundMatch> = emptyList()) {
        val queries = queriesOverride ?: searchQuery.split(",").map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (queries.isEmpty()) {
            searchState = SearchState.Error("ചുരുങ്ങിയത് 3 അക്കമെങ്കിലും ഉള്ള ഒരു നമ്പറെങ്കിലും നൽകുക")
            return
        }
        searchState = SearchState.Working
        val officialItems = (listingsState as? ListingsState.Loaded)?.items.orEmpty()
        val offsets = if (quickSearch) 0..1 else 0..6
        scope.launch {
            searchState = withContext(Dispatchers.IO) {
                val today = java.time.LocalDate.now()
                var remaining = queries
                val newlyFound = mutableListOf<FoundMatch>()
                for (offset in offsets) {
                    if (remaining.isEmpty()) break
                    val anchor = today.minusDays(offset.toLong())
                    val target = KeralaLotterySchedule.listingForDate(anchor.minusWeeks((depth - 1).toLong()), officialItems) ?: continue
                    val found = searchOneDraw(target, officialItems, depth, remaining)
                    if (found.isNotEmpty()) {
                        newlyFound += found
                        remaining = remaining - found.map { it.query }.toSet()
                    }
                }
                SearchState.Done(previousMatches + newlyFound, remaining, depth)
            }
        }
    }

    fun runGeneration(source: ResultSource, useFormat2: Boolean, block: suspend () -> LotteryResult) {
        genState = GenerationState.Working
        scope.launch {
            try {
                val (result, file, preview, fontSize) = withContext(Dispatchers.IO) {
                    val parsed = block()
                    val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                    val outFile = File(outDir, "lottery_result_${System.currentTimeMillis()}.pdf")
                    val fs = if (useFormat2) {
                        CompactPdfGeneratorV2.generate(parsed, companyName.trim(), outFile, isUnofficial = source == ResultSource.UNOFFICIAL).second
                    } else {
                        CompactPdfGenerator.generate(parsed, companyName.trim(), outFile, isUnofficial = source == ResultSource.UNOFFICIAL).second
                    }
                    val bitmap = renderFirstPage(outFile)
                    GenResult(parsed, outFile, bitmap, fs)
                }
                genState = GenerationState.Ready(result, file, preview, source, isFormat2 = useFormat2, gridFontSize = fontSize)
            } catch (e: Exception) {
                genState = GenerationState.Error(e.message ?: "എന്തോ പിശക് സംഭവിച്ചു.")
            }
        }
    }

    /** Regenerates the currently-shown result (either format) at a manually chosen font size -
     * updates the preview in place, no download, so PDF/JPG/Share afterward act on whatever the
     * user actually settled on. Always available, from the very first preview - not just once
     * someone has picked Format 2. */
    fun regenerateWithFontSize(current: GenerationState.Ready, fontOverride: Float) {
        scope.launch {
            val (file, preview, fontSize) = withContext(Dispatchers.IO) {
                val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                val outFile = File(outDir, "lottery_result_${System.currentTimeMillis()}.pdf")
                val fs = if (current.isFormat2) {
                    CompactPdfGeneratorV2.generate(
                        current.result, companyName.trim(), outFile,
                        isUnofficial = current.source == ResultSource.UNOFFICIAL,
                        gridFontOverride = fontOverride
                    ).second
                } else {
                    CompactPdfGenerator.generate(
                        current.result, companyName.trim(), outFile,
                        isUnofficial = current.source == ResultSource.UNOFFICIAL,
                        fontSizeOverride = fontOverride
                    ).second
                }
                val bitmap = renderFirstPage(outFile)
                Triple(outFile, bitmap, fs)
            }
            genState = current.copy(file = file, preview = preview, gridFontSize = fontSize)
        }
    }

    // Asked once, right when a generate button is tapped, instead of always defaulting to
    // Format 1 and only surfacing Format 2 as an after-the-fact button on the finished result.
    var pendingGeneration by remember { mutableStateOf<Pair<ResultSource, suspend () -> LotteryResult>?>(null) }

    // Drives both the refresh button inside the preview and the very first auto-generated
    // result - always the latest official draw (Format 1), the one result everyone wants to see
    // without having to pick a source or format first.
    fun generateLatestOfficial() {
        val listing = selectedListing ?: (listingsState as? ListingsState.Loaded)?.items?.firstOrNull() ?: return
        runGeneration(ResultSource.OFFICIAL, useFormat2 = false) {
            val bytes = OfficialLotteryResultsClient.fetchResultPdf(listing.itemId)
            LotteryPdfParser.parsePdfBytes(context, bytes)
        }
    }

    // Shows a result on screen the moment the listings load, with no tap needed - the app opens
    // straight into the latest official draw instead of a blank preview.
    var autoGenerated by remember { mutableStateOf(false) }
    LaunchedEffect(listingsState) {
        if (!autoGenerated && listingsState is ListingsState.Loaded) {
            autoGenerated = true
            generateLatestOfficial()
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
        // Preview stays at the very top, right under the company banner, so a result is visible
        // immediately - the refresh button doubles as the loading indicator while one's being
        // generated, and as the manual "get the latest again" trigger once it's ready.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("പ്രിവ്യൂ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (genState is GenerationState.Working) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
            } else {
                IconButton(onClick = { generateLatestOfficial() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "ഏറ്റവും പുതിയ ഔദ്യോഗിക ഫലം വീണ്ടും തയ്യാറാക്കുക")
                }
            }
        }
        when (val s = genState) {
            GenerationState.Idle, GenerationState.Working -> Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (genState is GenerationState.Working) "ഫലം തയ്യാറാക്കുന്നു…" else "ഫലം കാണാൻ മുകളിലെ ബട്ടൺ അമർത്തുക",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            is GenerationState.Error -> Text(
                "പിശക്: ${s.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            is GenerationState.Ready -> {
                SourceBadge(source = s.source, tierCount = s.result.tiers.size)

                // Always shown, right from the very first preview, for either format -
                // adjusting it regenerates the PDF and re-renders the preview in place, live, no
                // download. Only PDF/JPG/Share below actually save anything, once satisfied.
                if (s.gridFontSize != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ഫോണ്ട് വലുപ്പം:", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { regenerateWithFontSize(s, (s.gridFontSize - 0.1f).coerceAtLeast(2f)) }) {
                            Icon(Icons.Filled.Remove, contentDescription = "ഫോണ്ട് ചെറുതാക്കുക")
                        }
                        Text(
                            "%.1f".format(s.gridFontSize),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        IconButton(onClick = { regenerateWithFontSize(s, s.gridFontSize + 0.1f) }) {
                            Icon(Icons.Filled.Add, contentDescription = "ഫോണ്ട് വലുതാക്കുക")
                        }
                    }
                }

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
        }

        // Generate controls sit right under the preview, not buried below the search section -
        // the preview above updates the moment one of these is used.
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

        if (useUnofficial) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val target = unofficialTargetOrFallback() ?: return@Button
                        pendingGeneration = ResultSource.UNOFFICIAL to suspend {
                            try {
                                val url = UnofficialLotteryResultsClient.resolveUrl(target)
                                val html = UnofficialLotteryResultsClient.fetchHtml(url)
                                UnofficialResultParser.parseHtml(html)
                            } catch (e: Exception) {
                                // The mirror page itself might not be up yet (404) ahead of the
                                // draw - still print the letterhead from what we already know,
                                // with the same "result coming soon" placeholder as a page that
                                // loaded but had nothing on it yet.
                                UnofficialResultParser.waitingResult(target)
                            }
                        }
                    },
                    enabled = genState !is GenerationState.Working,
                    modifier = Modifier.weight(1f)
                ) { Text("സ്രോതസ്സ് 1") }
                Button(
                    onClick = {
                        val target = unofficialTargetOrFallback() ?: return@Button
                        pendingGeneration = ResultSource.UNOFFICIAL to suspend {
                            try {
                                val url = UnofficialLotteryResultsClient2.guessUrl(target)
                                val html = UnofficialLotteryResultsClient2.fetchHtml(url)
                                UnofficialResultParser2.parseHtml(html, target)
                            } catch (e: Exception) {
                                UnofficialResultParser2.waitingResult(target)
                            }
                        }
                    },
                    enabled = genState !is GenerationState.Working,
                    modifier = Modifier.weight(1f)
                ) { Text("സ്രോതസ്സ് 2") }
            }
        } else {
            Button(
                onClick = {
                    val listing = selectedListing ?: return@Button
                    pendingGeneration = ResultSource.OFFICIAL to suspend {
                        val bytes = OfficialLotteryResultsClient.fetchResultPdf(listing.itemId)
                        LotteryPdfParser.parsePdfBytes(context, bytes)
                    }
                },
                enabled = selectedListing != null && genState !is GenerationState.Working,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ഏറ്റവും പുതിയ ഫലം എടുത്ത് ഒറ്റ പേജ് തയ്യാറാക്കുക")
            }
        }

        pendingGeneration?.let { (source, block) ->
            AlertDialog(
                onDismissRequest = { pendingGeneration = null },
                title = { Text("PDF ഫോർമാറ്റ് തിരഞ്ഞെടുക്കുക") },
                text = { Text("ഏത് ഫോർമാറ്റിൽ ഒറ്റ പേജ് തയ്യാറാക്കണം?") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingGeneration = null
                        runGeneration(source, useFormat2 = false, block)
                    }) { Text("ഫോർമാറ്റ് 1") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingGeneration = null
                        runGeneration(source, useFormat2 = true, block)
                    }) { Text("ഫോർമാറ്റ് 2") }
                }
            )
        }

        HorizontalDivider()
        Text("ലോട്ടറി ഫലം — ഒറ്റ പേജ് പ്രിന്റ്", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = companyName,
            onValueChange = { companyName = it },
            label = { Text("ഹെഡർ / കമ്പനി പേര്") },
            placeholder = { Text("ഉദാ: ശ്രീ ലക്കി ഏജൻസീസ്") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        HorizontalDivider()
        Text("ടിക്കറ്റ് നമ്പർ പരിശോധിക്കുക", style = MaterialTheme.typography.titleMedium)
        Text(
            "ഒന്നിലധികം ടിക്കറ്റ് നമ്പറുകൾ കോമയിട്ട് വേർതിരിച്ച് നൽകാം (ഉദാ: 1234, 5678). ഏതെങ്കിലും ഭാഗം മതി.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = quickSearch, onCheckedChange = { quickSearch = it })
            Text("ക്വിക്ക് സെർച്ച് (ഇന്നും ഇന്നലെയും മാത്രം)")
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("ടിക്കറ്റ് നമ്പർ(കൾ)") },
            placeholder = { Text("ഉദാ: 1234, 5678, 9012") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { runSearch(depth = 1) },
            enabled = searchState !is SearchState.Working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("തിരയുക")
        }
        when (val ss = searchState) {
            SearchState.Idle -> Unit
            SearchState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("തിരയുന്നു…")
            }
            is SearchState.Error -> Text(ss.message, color = MaterialTheme.colorScheme.error)
            is SearchState.Done -> {
                if (ss.matches.isNotEmpty()) {
                    Text(
                        "സമ്മാനം ലഭിച്ചു!",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall
                    )
                    ss.matches.forEach { m ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                "നമ്പർ ${m.query}: ${m.tierLabel} — ₹${CompactPdfGenerator.formatAmount(m.amount)}",
                                fontWeight = FontWeight.Bold
                            )
                            Text("ടിക്കറ്റ്: ${m.number}" + if (m.place.isNotBlank()) " (${m.place})" else "")
                            Text(
                                "${m.lotteryName} (${m.drawNumber}) — ${m.drawDate} • ${if (m.source == ResultSource.OFFICIAL) "ഔദ്യോഗികം" else "അനൗദ്യോഗികം"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        HorizontalDivider()
                    }
                }
                if (ss.notFoundQueries.isNotEmpty()) {
                    Text(
                        "ഈ നമ്പറുകൾക്ക് സമ്മാനം കണ്ടെത്താനായില്ല: ${ss.notFoundQueries.joinToString(", ")}",
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { runSearch(depth = ss.depthTried + 1, queriesOverride = ss.notFoundQueries, previousMatches = ss.matches) }) {
                        Text("മുൻ തീയതി പരിശോധിക്കുക")
                    }
                }
            }
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
        ResultSource.UNOFFICIAL -> (
            if (tierCount == 0) "അനൗദ്യോഗിക സ്രോതസ്സ് - ഇതുവരെ പ്രഖ്യാപിച്ചിട്ടില്ല, കുറച്ച് കഴിഞ്ഞ് നോക്കുക"
            else "അനൗദ്യോഗിക സ്രോതസ്സ് - $tierCount സമ്മാന വിഭാഗ${if (tierCount == 1) "ം" else "ങ്ങൾ"} കണ്ടെത്തി, അപൂർണ്ണമാകാം"
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
