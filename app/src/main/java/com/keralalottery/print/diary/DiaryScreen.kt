package com.keralalottery.print.diary

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun shareEntryIntent(entry: DiaryEntry): Intent {
    val text = buildString {
        if (entry.title.isNotBlank()) appendLine(entry.title)
        if (entry.body.isNotBlank()) append(entry.body)
    }
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text.trim())
    }
}

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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.title.ifBlank { "(untitled)" },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { context.startActivity(Intent.createChooser(shareEntryIntent(entry), "Share entry")) }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share")
                                }
                            }
                            if (entry.body.isNotBlank()) {
                                Text(entry.body, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
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
    // Already copied into app storage this session, but not yet linked to a saved entry - only
    // committed to the database on Save. Backing out without saving deletes these files again.
    var pendingAttachments by remember { mutableStateOf<List<DiaryAttachment>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cameraTargetFile by remember { mutableStateOf<File?>(null) }

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

    fun back() {
        pendingAttachments.forEach { AttachmentStore.delete(it) }
        onDone()
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
                pendingAttachments.forEach { att -> dao.insertAttachment(att.copy(entryId = id)) }
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

    fun removePendingAttachment(att: DiaryAttachment) {
        AttachmentStore.delete(att)
        pendingAttachments = pendingAttachments.filterNot { it === att }
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

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraTargetFile
        cameraTargetFile = null
        if (success && file != null) {
            pendingAttachments = pendingAttachments + AttachmentStore.fromFile(file, file.name, "image/jpeg")
        } else {
            file?.delete()
        }
    }

    val pickGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) { uris.mapNotNull { AttachmentStore.copyIn(context, it) } }
            pendingAttachments = pendingAttachments + copied
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) { uris.mapNotNull { AttachmentStore.copyIn(context, it) } }
            pendingAttachments = pendingAttachments + copied
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
            OutlinedButton(onClick = { back() }) { Text("Back") }
            Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
            IconButton(onClick = {
                context.startActivity(
                    Intent.createChooser(
                        shareEntryIntent(DiaryEntry(title = title, body = body, createdAt = createdAt, updatedAt = createdAt)),
                        "Share entry"
                    )
                )
            }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
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
            OutlinedButton(onClick = {
                val file = AttachmentStore.newCameraFile(context)
                cameraTargetFile = file
                takePicture.launch(AttachmentStore.uriForFile(context, file))
            }) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Camera")
            }
            OutlinedButton(onClick = {
                pickGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Gallery")
            }
            OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("File")
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
        pendingAttachments.forEach { att ->
            AttachmentRow(name = att.name, onOpen = null, onRemove = { removePendingAttachment(att) })
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
