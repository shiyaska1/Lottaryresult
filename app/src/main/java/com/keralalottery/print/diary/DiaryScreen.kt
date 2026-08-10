package com.keralalottery.print.diary

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keralalottery.print.data.AppDatabase
import com.keralalottery.print.data.DiaryAttachment
import com.keralalottery.print.data.DiaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiaryScreen() {
    // null = list, 0L = new entry, >0 = editing that entry.
    var openEntryId by remember { mutableStateOf<Long?>(null) }

    val id = openEntryId
    if (id == null) {
        DiaryListScreen(onOpen = { openEntryId = it }, onNew = { openEntryId = 0L })
    } else {
        DiaryEditScreen(entryId = id, onDone = { openEntryId = null })
    }
}

@Composable
private fun DiaryListScreen(onOpen: (Long) -> Unit, onNew: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).diaryDao() }
    var query by remember { mutableStateOf("") }
    val entries by dao.search(query).collectAsState(initial = emptyList())
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Diary", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New entry") }

        if (entries.isEmpty()) {
            Text(
                if (query.isBlank()) "No diary entries yet." else "No entries match \"$query\".",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry ->
                    ElevatedCard(onClick = { onOpen(entry.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                entry.title.ifBlank { "(untitled)" },
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (entry.body.isNotBlank()) {
                                Text(
                                    entry.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                            }
                            Text(
                                dateFmt.format(Date(entry.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryEditScreen(entryId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).diaryDao() }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var createdAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var savedAttachments by remember { mutableStateOf<List<DiaryAttachment>>(emptyList()) }
    // Picked in this session but not yet copied into storage - only committed on Save, so
    // backing out of a new entry leaves nothing behind.
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        if (entryId != 0L) {
            val e = dao.byId(entryId)
            if (e != null) {
                title = e.title
                body = e.body
                createdAt = e.createdAt
            }
            savedAttachments = dao.attachmentsFor(entryId)
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingUris = pendingUris + uris
    }

    fun save() {
        scope.launch {
            val now = System.currentTimeMillis()
            val id = withContext(Dispatchers.IO) {
                if (entryId == 0L) {
                    dao.insert(DiaryEntry(title = title, body = body, createdAt = now, updatedAt = now))
                } else {
                    dao.update(DiaryEntry(id = entryId, title = title, body = body, createdAt = createdAt, updatedAt = now))
                    entryId
                }
            }
            withContext(Dispatchers.IO) {
                pendingUris.forEach { uri ->
                    val att = AttachmentStore.copyIn(context, uri) ?: return@forEach
                    dao.insertAttachment(att.copy(entryId = id))
                }
            }
            onDone()
        }
    }

    fun removeSavedAttachment(att: DiaryAttachment) {
        scope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteAttachment(att)
                AttachmentStore.delete(att)
            }
            savedAttachments = savedAttachments.filterNot { it.id == att.id }
        }
    }

    fun deleteEntry() {
        scope.launch {
            withContext(Dispatchers.IO) {
                savedAttachments.forEach { AttachmentStore.delete(it) }
                dao.deleteAttachmentsFor(entryId)
                dao.byId(entryId)?.let { dao.delete(it) }
            }
            onDone()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDone) { Text("Back") }
            Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
            if (entryId != 0L) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
                }
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
        )

        Text("Attachments", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add file")
            }
        }
        savedAttachments.forEach { att ->
            AttachmentRow(
                name = att.name,
                onOpen = {
                    try {
                        val uri = AttachmentStore.uriFor(context, att)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, att.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not open the file.", Toast.LENGTH_SHORT).show()
                    }
                },
                onRemove = { removeSavedAttachment(att) }
            )
        }
        pendingUris.forEach { uri ->
            AttachmentRow(
                name = uri.lastPathSegment ?: "file",
                onOpen = null,
                onRemove = { pendingUris = pendingUris.filterNot { it == uri } }
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("This removes the entry and its attachments. This can't be undone.") },
            confirmButton = {
                Button(onClick = { confirmDelete = false; deleteEntry() }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AttachmentRow(name: String, onOpen: (() -> Unit)?, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
        val textModifier = Modifier.weight(1f).let { if (onOpen != null) it.clickable(onClick = onOpen) else it }
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = textModifier)
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
        }
    }
}
