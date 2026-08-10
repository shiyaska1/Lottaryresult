package com.keralalottery.print.quicklinks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Persistent row of user-chosen app shortcuts, shown above the tab content on every tab -
 * quick access to whatever the user actually reaches for most (YouTube, WhatsApp, Chrome,
 * GPay by default), without leaving this app to hunt for them on the home screen. */
@Composable
fun QuickLinksRow() {
    val context = LocalContext.current
    val store = remember { QuickLinksStore(context) }
    var packages by remember { mutableStateOf(store.load()) }
    var showPicker by remember { mutableStateOf(false) }
    // The close icon sits right next to the (much larger) tap target for launching the app in a
    // tightly-packed scrolling row - easy to hit by accident, so removal needs a confirm step.
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        packages.forEach { pkg ->
            QuickLinkChip(
                packageName = pkg,
                onClick = { launchOrInstall(context, pkg) },
                onRemove = { pendingRemove = pkg }
            )
        }
        AssistChip(onClick = { showPicker = true }, label = { Text("+ Add") })
    }

    if (showPicker) {
        AppPickerDialog(
            alreadyAdded = packages.toSet(),
            onDismiss = { showPicker = false },
            onPick = { pkg ->
                store.add(pkg)
                packages = store.load()
                showPicker = false
            }
        )
    }

    val toRemove = pendingRemove
    if (toRemove != null) {
        val label = remember(toRemove) { appLabel(context, toRemove) }
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove quick link?") },
            text = { Text("Remove \"$label\" from your quick links? You can add it back any time.") },
            confirmButton = {
                Button(onClick = {
                    store.remove(toRemove)
                    packages = store.load()
                    pendingRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { OutlinedButton(onClick = { pendingRemove = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun QuickLinkChip(packageName: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val label = remember(packageName) { appLabel(context, packageName) }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { AppIcon(packageName, size = 20.dp) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove $label",
                modifier = Modifier.size(16.dp).clickable { onRemove() }
            )
        }
    )
}

@Composable
private fun AppPickerDialog(alreadyAdded: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val allApps = remember { installedLaunchableApps(context).filterNot { it.packageName in alreadyAdded } }
    var query by remember { mutableStateOf("") }
    val apps = remember(query) {
        if (query.isBlank()) allApps else allApps.filter { it.label.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Add a quick link") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                if (allApps.isEmpty()) {
                    Text("Nothing left to add - every installed app is already a quick link.")
                } else if (apps.isEmpty()) {
                    Text("No installed app matches \"$query\".")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app.packageName) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AppIcon(app.packageName, size = 32.dp)
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun AppIcon(packageName: String, size: Dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(modifier = Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
    }
}

private data class LaunchableApp(val packageName: String, val label: String)

/** Every launcher-visible app on the device. Relies on the <queries> MAIN/LAUNCHER intent
 * declaration in AndroidManifest.xml - without it, Android 11+'s package-visibility rules would
 * hide most of these from a plain queryIntentActivities call. */
private fun installedLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    // No MATCH_DEFAULT_ONLY here: that flag only returns activities declaring the DEFAULT
    // category, which excludes some real, launcher-visible apps - exactly the "fewer apps
    // than expected" gap reported after shipping with that flag.
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .asSequence()
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
        .map { pkg -> LaunchableApp(pkg, appLabel(context, pkg)) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

private fun appLabel(context: Context, packageName: String): String {
    val pm = context.packageManager
    return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
        .getOrDefault(packageName)
}

/** Launches the app if installed, otherwise falls back to its Play Store listing. */
private fun launchOrInstall(context: Context, packageName: String) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        context.startActivity(launchIntent)
        return
    }
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
    try {
        context.startActivity(marketIntent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
    }
}
