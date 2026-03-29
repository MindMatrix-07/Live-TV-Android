package com.livetv.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        if (!BootLaunchPreferences.isEnabled(context)) {
            DebugLogStore.add("BootReceiver", "Boot launch skipped; disabled")
            return
        }

        runCatching {
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                } ?: return

            DebugLogStore.add("BootReceiver", "Launching app after boot")
            context.startActivity(launchIntent)
        }.onFailure { error ->
            DebugLogStore.add("BootReceiver", "Boot launch failed", error)
        }
    }
}
