package com.keralalottery.print.data

import android.content.Context

/** Tiny SharedPreferences store for the trial/licence state. */
class AppPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("lottery_print_prefs", Context.MODE_PRIVATE)

    var installDateMillis: Long
        get() = p.getLong("install_date", 0L)
        set(v) { p.edit().putLong("install_date", v).apply() }

    var licensed: Boolean
        get() = p.getBoolean("licensed", false)
        set(v) { p.edit().putBoolean("licensed", v).apply() }
}
