package com.keralalottery.print.quicklinks

import android.content.Context

// A sensible starting set - the user can remove any of these or add their own from the full
// installed-apps picker. If one isn't installed, tapping its chip falls back to its Play Store
// listing (see launchOrInstall in QuickLinksRow.kt) rather than doing nothing.
private const val DEFAULT_PACKAGES =
    "com.google.android.youtube,com.whatsapp,com.android.chrome,com.google.android.apps.nbu.paisa.user"

/** User-editable list of quick-link package names, persisted as a simple comma-joined string. */
class QuickLinksStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("quick_links_prefs", Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = prefs.getString(KEY, null) ?: DEFAULT_PACKAGES
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun add(packageName: String) {
        val current = load()
        if (packageName !in current) save(current + packageName)
    }

    fun remove(packageName: String) {
        save(load() - packageName)
    }

    private fun save(packages: List<String>) {
        prefs.edit().putString(KEY, packages.joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "packages"
    }
}
