package com.keralalottery.print.news

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface NewsFetchState {
    data object Loading : NewsFetchState
    data object Done : NewsFetchState
    data class Error(val message: String) : NewsFetchState
}

@Composable
fun NewsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bySource by remember { mutableStateOf<Map<String, List<NewsItem>>>(emptyMap()) }
    var state by remember { mutableStateOf<NewsFetchState>(NewsFetchState.Loading) }

    fun refresh() {
        state = NewsFetchState.Loading
        scope.launch {
            state = try {
                bySource = withContext(Dispatchers.IO) { NewsFetcher.fetchAll() }
                NewsFetchState.Done
            } catch (e: Exception) {
                NewsFetchState.Error(e.message ?: "Could not fetch news.")
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Malayalam News", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Latest headlines from Mathrubhumi, Kerala Kaumudi and Madhyamam's own feeds. " +
                "\"Read\" opens the full article on the publisher's site.",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val s = state) {
            is NewsFetchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fetching latest headlines…")
            }
            is NewsFetchState.Error -> Column {
                Text(
                    if (bySource.isEmpty()) "Error: ${s.message}" else "Could not refresh some sources: ${s.message}",
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { refresh() }) { Text("Retry") }
            }
            NewsFetchState.Done -> Unit
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            bySource.forEach { (source, items) ->
                item(key = "header_$source") {
                    Text(
                        source,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(items, key = { it.link }) { newsItem ->
                    NewsCard(newsItem, onRead = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(newsItem.link)))
                    })
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem, onRead: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.date.isNotBlank()) {
                    Text(
                        item.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onRead) { Text("Read") }
            }
        }
    }
}
