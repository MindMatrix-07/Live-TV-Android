package com.livetv.android

import android.content.Context
import android.os.Build

object BootLaunchPreferences {
    private const val PREFS_NAME = "live_tv_prefs"
    private const val KEY_AUTO_LAUNCH_ON_BOOT = "auto_launch_on_boot"

    private fun prefsContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

    fun isEnabled(context: Context): Boolean =
        prefsContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_LAUNCH_ON_BOOT, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefsContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_LAUNCH_ON_BOOT, enabled)
            .apply()
    }
}
