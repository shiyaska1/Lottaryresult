package com.keralalottery.print.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File

/** Encrypts an already-generated PDF in place so it needs a password to open. */
object PdfEncryptor {

    private var loaderInitialized = false

    /** Same password for opening and for changing permissions - simplest case for a shared result PDF. */
    fun protect(context: Context, file: File, password: String) {
        if (!loaderInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            loaderInitialized = true
        }
        PDDocument.load(file).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(file)
        }
    }
}
