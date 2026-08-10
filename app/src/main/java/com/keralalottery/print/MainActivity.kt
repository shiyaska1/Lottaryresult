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
import com.keralalottery.print.data.AppPrefs
import com.keralalottery.print.education.EducationScreen
import com.keralalottery.print.gold.GoldRateScreen
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.network.LotteryListing
import com.keralalottery.print.network.OfficialLotteryResultsClient
import com.keralalottery.print.parse.LotteryPdfParser
import com.keralalottery.print.pdf.CompactPdfGenerator
import com.keralalottery.print.pdf.PdfEncryptor
import com.keralalottery.print.pdf.PdfPrinter
import com.keralalottery.print.psc.PscScreen
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

private sealed interface GenerationState {
    data object Idle : GenerationState
    data object Working : GenerationState
    data class Error(val message: String) : GenerationState
    data class Ready(val result: LotteryResult, val file: File, val preview: Bitmap) : GenerationState
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
    LOTTERY("Lottery Result"), GOLD("Gold Rate"), PSC("PSC"), EDUCATION("Education")
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
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                RootTab.LOTTERY -> LotteryPrintApp()
                RootTab.GOLD -> GoldRateScreen()
                RootTab.PSC -> PscScreen()
                RootTab.EDUCATION -> EducationScreen()
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
            ListingsState.Error(e.message ?: "Could not load the results list.")
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            manualUri = uri
            manualName = queryDisplayName(context, uri)
        }
    }

    fun runGeneration(block: suspend () -> LotteryResult) {
        genState = GenerationState.Working
        scope.launch {
            try {
                val (result, file, preview) = withContext(Dispatchers.IO) {
                    val parsed = block()
                    val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                    val outFile = File(outDir, "lottery_result_${System.currentTimeMillis()}.pdf")
                    CompactPdfGenerator.generate(parsed, companyName.trim(), outFile)
                    // Render the preview from the plain PDF first - Android's PdfRenderer can't
                    // open a password-protected one - then encrypt the file in place afterward,
                    // so both Download and Share end up with the protected copy.
                    val bitmap = renderFirstPage(outFile)
                    if (passwordProtect && password.isNotBlank()) {
                        PdfEncryptor.protect(context, outFile, password)
                    }
                    Triple(parsed, outFile, bitmap)
                }
                genState = GenerationState.Ready(result, file, preview)
            } catch (e: Exception) {
                genState = GenerationState.Error(e.message ?: "Something went wrong.")
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
        Text("Lottery Result — One-Page Print", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Fetch the latest result straight from the Kerala Government's official lottery " +
                "portal and get back a single, dense, bold, printable page with your own header.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = companyName,
            onValueChange = { companyName = it },
            label = { Text("Header / Company name") },
            placeholder = { Text("e.g. Sree Lucky Agencies") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = passwordProtect, onCheckedChange = { passwordProtect = it })
                Text("Password protect PDF")
            }
            if (passwordProtect) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("PDF password") },
                    isError = password.isBlank(),
                    supportingText = { if (password.isBlank()) Text("Required to protect the PDF") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        HorizontalDivider()
        Text("Fetch latest official result", style = MaterialTheme.typography.titleMedium)

        when (val ls = listingsState) {
            is ListingsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Loading lottery list…")
            }
            is ListingsState.Error -> Column {
                Text("Error: ${ls.message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reloadKey++ }) { Text("Retry") }
            }
            is ListingsState.Loaded -> {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedListing?.let { "${it.name} (${it.drawCode}) — ${it.date}" } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Lottery") },
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
                Button(
                    onClick = {
                        val listing = selectedListing ?: return@Button
                        runGeneration {
                            val bytes = OfficialLotteryResultsClient.fetchResultPdf(listing.itemId)
                            LotteryPdfParser.parsePdfBytes(context, bytes)
                        }
                    },
                    enabled = selectedListing != null && genState !is GenerationState.Working && passwordReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Fetch latest & generate one-page result")
                }
            }
        }

        HorizontalDivider()
        Text("Or import a PDF file manually", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (manualName == null) "Choose official result PDF" else "Change PDF")
        }
        manualName?.let { Text("Selected: $it", style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val uri = manualUri ?: return@Button
                runGeneration { LotteryPdfParser.parsePdf(context, uri) }
            },
            enabled = manualUri != null && genState !is GenerationState.Working && passwordReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate from imported PDF")
        }

        when (val s = genState) {
            is GenerationState.Working -> Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            is GenerationState.Error -> Text(
                "Error: ${s.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            is GenerationState.Ready -> {
                HorizontalDivider()
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                // Actions come before the (often tall) preview image so they stay reachable
                // without scrolling all the way down past it, near the phone's nav bar.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Button(onClick = {
                        val name = "Lottery_Result_${System.currentTimeMillis()}.pdf"
                        PdfPrinter.saveToDownloads(context, s.file, name)
                        Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("PDF")
                    }
                    OutlinedButton(onClick = {
                        val name = "Lottery_Result_${System.currentTimeMillis()}.jpg"
                        PdfPrinter.saveJpgToDownloads(context, s.preview, name)
                        Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("JPG")
                    }
                    OutlinedButton(onClick = { PdfPrinter.share(context, s.file) }) {
                        Text("Share")
                    }
                }
                Image(
                    bitmap = s.preview.asImageBitmap(),
                    contentDescription = "Generated result preview",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            GenerationState.Idle -> Unit
        }
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
