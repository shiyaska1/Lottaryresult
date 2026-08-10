package com.keralalottery.print.pdf

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/** Saves or shares an already-generated single-page PDF file, or its rendered preview image. */
object PdfPrinter {

    /** Copies the PDF into the public Downloads folder so it shows up like any other download. */
    fun saveToDownloads(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("ഡൗൺലോഡ് എൻട്രി ഉണ്ടാക്കാൻ കഴിഞ്ഞില്ല.")
        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
        } ?: error("ഡൗൺലോഡ് ലക്ഷ്യസ്ഥാനം തുറക്കാൻ കഴിഞ്ഞില്ല.")
        return uri
    }

    /** Saves the already-rendered preview bitmap as a JPG into the public Downloads folder. */
    fun saveJpgToDownloads(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("ഡൗൺലോഡ് എൻട്രി ഉണ്ടാക്കാൻ കഴിഞ്ഞില്ല.")
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        } ?: error("ഡൗൺലോഡ് ലക്ഷ്യസ്ഥാനം തുറക്കാൻ കഴിഞ്ഞില്ല.")
        return uri
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "ഫലം ഷെയർ ചെയ്യുക"))
    }
}
