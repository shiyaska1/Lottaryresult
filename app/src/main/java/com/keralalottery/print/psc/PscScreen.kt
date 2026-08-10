package com.keralalottery.print.psc

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keralalottery.print.pdf.PdfPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface PscFetchState {
    data object Loading : PscFetchState
    data object Done : PscFetchState
    data class Error(val message: String) : PscFetchState
}

@Composable
fun PscScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notices by remember { mutableStateOf<List<PscNotice>>(emptyList()) }
    var state by remember { mutableStateOf<PscFetchState>(PscFetchState.Loading) }

    fun refresh() {
        state = PscFetchState.Loading
        scope.launch {
            state = try {
                notices = withContext(Dispatchers.IO) { PscNoticeFetcher.fetchLatest() }
                PscFetchState.Done
            } catch (e: Exception) {
                PscFetchState.Error(e.message ?: "Could not fetch PSC notices.")
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .navigationBarsPadding()
    ) {
        Text("Kerala PSC — Latest", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ranked lists, short lists, interviews and notifications, straight from the " +
                "official Kerala PSC site.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        when (val s = state) {
            is PscFetchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fetching latest notices…")
            }
            is PscFetchState.Error -> Column {
                Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh() }) { Text("Retry") }
            }
            PscFetchState.Done -> Unit
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(notices) { notice -> PscNoticeCard(notice) }
        }
    }
}

@Composable
private fun PscNoticeCard(notice: PscNotice) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(notice.type) })
            Text(notice.title, style = MaterialTheme.typography.bodyLarge)
            val fileUrl = notice.fileUrl
            if (fileUrl != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        // Downloads the PDF, drops its boilerplate notes/blank pages (see
                        // PscRankListTrimmer), and saves just the rank-table pages - a much
                        // shorter file than the original for posts with heavy legal boilerplate.
                        TextButton(onClick = {
                            working = true
                            scope.launch {
                                try {
                                    val bytes = withContext(Dispatchers.IO) { PscNoticeFetcher.downloadPdf(fileUrl) }
                                    val tempFile = File(context.cacheDir, "psc_compact_${System.currentTimeMillis()}.pdf")
                                    withContext(Dispatchers.IO) { PscRankListTrimmer.trim(context, bytes, tempFile) }
                                    PdfPrinter.saveToDownloads(context, tempFile, "PSC_${System.currentTimeMillis()}.pdf")
                                    Toast.makeText(context, "Compact PDF saved to Downloads", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Could not create the compact PDF.", Toast.LENGTH_SHORT).show()
                                } finally {
                                    working = false
                                }
                            }
                        }) {
                            Text("Compact PDF")
                        }
                    }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl))) }) {
                        Text("Open PDF")
                    }
                }
            }
        }
    }
}
