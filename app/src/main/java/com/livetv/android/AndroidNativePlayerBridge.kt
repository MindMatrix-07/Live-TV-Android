package com.livetv.android

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AndroidNativePlayerBridge(
    private val playerController: NativeDirectPlayerController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun isAvailable(): Boolean = true

    @JavascriptInterface
    fun openPlayer(configJson: String): Boolean {
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        mainHandler.post {
            result.set(runCatching { playerController.play(configJson) }.getOrElse { error ->
                DebugLogStore.add("AndroidNativePlayerBridge", "Open failed", error)
                false
            })
            latch.countDown()
        }

        latch.await(1500, TimeUnit.MILLISECONDS)
        return result.get()
    }

    @JavascriptInterface
    fun closePlayer(): Boolean {
        mainHandler.post {
            runCatching { playerController.close() }.onFailure { error ->
                DebugLogStore.add("AndroidNativePlayerBridge", "Close failed", error)
            }
        }
        return true
    }
}
