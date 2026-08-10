package com.keralalottery.print.diary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.keralalottery.print.data.AttachmentType
import com.keralalottery.print.data.DiaryAttachment
import java.io.File

/** Copies picked files into app-private storage for the diary. */
object AttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "diary").apply { mkdirs() }

    /** Copies [uri] into app storage and returns a (not-yet-persisted) attachment. */
    fun copyIn(context: Context, uri: Uri): DiaryAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "att_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            DiaryAttachment(
                entryId = 0,
                path = target.absolutePath,
                name = displayName,
                mime = mime,
                type = typeOf(mime)
            )
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    fun delete(attachment: DiaryAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    /** A content:// Uri for opening/sharing the attachment. */
    fun uriFor(context: Context, attachment: DiaryAttachment): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(attachment.path))

    private fun typeOf(mime: String): AttachmentType = when {
        mime.startsWith("image/") -> AttachmentType.IMAGE
        mime.startsWith("video/") -> AttachmentType.VIDEO
        mime.startsWith("audio/") -> AttachmentType.AUDIO
        else -> AttachmentType.DOCUMENT
    }

    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "video/mp4" -> "mp4"
        "audio/mp4", "audio/aac" -> "m4a"; "application/pdf" -> "pdf"; else -> ""
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }
}
