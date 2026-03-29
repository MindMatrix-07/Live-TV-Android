package com.livetv.android

import android.content.Context

object BootLaunchPreferences {
    private const val PREFS_NAME = "live_tv_prefs"
    private const val KEY_AUTO_LAUNCH_ON_BOOT = "auto_launch_on_boot"

    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_LAUNCH_ON_BOOT, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_LAUNCH_ON_BOOT, enabled)
            .apply()
    }
}
