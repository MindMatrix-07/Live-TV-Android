package com.livetv.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast

class AndroidLogsBridge(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun getLogs(): String = DebugLogStore.dump()

    @JavascriptInterface
    fun copyText(text: String): Boolean {
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            mainHandler.post {
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText("Live TV Debug Logs", text),
                )
                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
            }

            DebugLogStore.add("AndroidLogsBridge", "Copied debug report (${text.length} chars)")
            true
        } catch (error: Exception) {
            DebugLogStore.add("AndroidLogsBridge", "Copy failed", error)
            false
        }
    }
}
