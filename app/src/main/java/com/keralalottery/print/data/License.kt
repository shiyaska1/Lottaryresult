package com.keralalottery.print.data

import android.content.Context
import android.provider.Settings
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trial + device-locked activation - same scheme as the POS Billing app, so the same key
 * computation works for both: the activation key is a keyed HMAC-SHA256 of the device id, so
 * it can't be forged without [SECRET]. To activate a customer: they read you their Device ID
 * from the trial-ended screen, you compute the key (any HMAC-SHA256 tool: key = SECRET,
 * message = the Device ID exactly as shown, take the first 16 hex chars, upper-case, dash
 * every 4), and they type it in.
 */
object License {
    const val TRIAL_DAYS = 30

    // ---- Support / purchase contact, shown wherever a licence key is needed ----
    /** WhatsApp number in international form, no "+" (wa.me wants it that way). */
    const val SUPPORT_WHATSAPP = "919961128378"
    /** Same number, formatted for display and for a plain dial link. */
    const val SUPPORT_PHONE = "+919961128378"

    /** WhatsApp chat pre-filled with the device id, so the licence key can be issued straight away. */
    fun buyUrlFor(deviceId: String): String {
        val msg = java.net.URLEncoder.encode(
            "I want to buy Lottery Result Print. My Device ID is $deviceId", "UTF-8"
        )
        return "https://wa.me/$SUPPORT_WHATSAPP?text=$msg"
    }

    /** Same secret as the POS Billing app, deliberately - one shared key-computation workflow
     *  for both apps. >>> Keep this in sync with POS Billing's if either one changes. <<< */
    private const val SECRET = "POSB-change-this-secret-2024"

    /** Stable per-device identifier (Android ID), shown to the user for activation. */
    fun deviceId(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return (if (id.isBlank()) "UNKNOWNDEVICE" else id).uppercase()
    }

    /** Activation key for a device - a permanent key, no renewal tiers. */
    fun activationKey(deviceId: String): String {
        val hex = hmacHex(deviceId.trim().uppercase()).take(16).uppercase()
        return hex.chunked(4).joinToString("-")
    }

    /** True when [key] matches the key for this device. */
    fun isValid(deviceId: String, key: String): Boolean {
        val norm = key.uppercase().replace(Regex("[^0-9A-F]"), "")
        if (norm.isEmpty()) return false
        return activationKey(deviceId).replace("-", "") == norm
    }

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun daysSince(installMillis: Long): Long {
        if (installMillis <= 0L) return 0L
        return (System.currentTimeMillis() - installMillis) / (1000L * 60 * 60 * 24)
    }

    fun trialExpired(installMillis: Long): Boolean = daysSince(installMillis) >= TRIAL_DAYS

    fun daysLeft(installMillis: Long): Int =
        (TRIAL_DAYS - daysSince(installMillis)).toInt().coerceIn(0, TRIAL_DAYS)
}
