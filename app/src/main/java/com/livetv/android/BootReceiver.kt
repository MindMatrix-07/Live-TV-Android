package com.livetv.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        if (!BootLaunchPreferences.isEnabled(context)) {
            DebugLogStore.add("BootReceiver", "Boot launch skipped; disabled")
            return
        }

        DebugLogStore.add("BootReceiver", "Launching app after boot")
        val launchIntent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        context.startActivity(launchIntent)
    }
}
