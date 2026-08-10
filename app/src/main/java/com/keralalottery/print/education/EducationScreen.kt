package com.keralalottery.print.education

import android.content.Intent
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private sealed interface EducationFetchState {
    data object Idle : EducationFetchState
    data object Loading : EducationFetchState
    data class Error(val message: String) : EducationFetchState
}

@Composable
fun EducationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Seeded from cache; deliberately no auto-refresh here (see EducationNoticeFetcher) - the
    // feed costs ~5MB per fetch, so refreshing only happens when the user taps the button below.
    var notices by remember { mutableStateOf(EducationNoticeStore.loadCached(context)) }
    var state by remember { mutableStateOf<EducationFetchState>(EducationFetchState.Idle) }

    fun refresh() {
        state = EducationFetchState.Loading
        scope.launch {
            state = try {
                val fetched = withContext(Dispatchers.IO) { EducationNoticeFetcher.fetchLatest() }
                notices = fetched
                withContext(Dispatchers.IO) { EducationNoticeStore.save(context, fetched) }
                EducationFetchState.Idle
            } catch (e: Exception) {
                EducationFetchState.Error(e.message ?: "Could not fetch education notices.")
            }
        }
    }

    // Only auto-fetch when there's truly nothing cached to show yet.
    LaunchedEffect(Unit) { if (notices.isEmpty()) refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .navigationBarsPadding()
    ) {
        Text("Kerala Education — Latest", style = MaterialTheme.typography.headlineSmall)
        Text(
            "SSLC/THSLC rank lists, exam results and notifications from Kerala Pareeksha " +
                "Bhavan. Each fetch is a few MB, so this only refreshes when you ask it to.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            when (val s = state) {
                is EducationFetchState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetching latest notices…")
                }
                is EducationFetchState.Error -> Column {
                    Text(
                        if (notices.isEmpty()) "Error: ${s.message}"
                        else "Could not refresh (showing the last saved list): ${s.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { refresh() }) { Text("Retry") }
                }
                EducationFetchState.Idle -> OutlinedButton(onClick = { refresh() }) {
                    Text("Refresh (~5MB)")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(notices) { notice -> EducationNoticeCard(notice) }
        }
    }
}

@Composable
private fun EducationNoticeCard(notice: EducationNotice) {
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(notice.title, style = MaterialTheme.typography.bodyLarge)
            TextButton(
                onClick = {
                    try {
                        val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                        val outFile = File(dir, "education_${System.currentTimeMillis()}.pdf")
                        FileOutputStream(outFile).use { it.write(notice.pdfBytes) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", outFile)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not open the PDF.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Open PDF")
            }
        }
    }
}
