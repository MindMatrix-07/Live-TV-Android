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
    fun isPlayerActive(): Boolean = runCatching { playerController.isActive() }.getOrDefault(false)

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

    @JavascriptInterface
    fun getAudioTracks(): String {
        val result = arrayOf("[]")
        val latch = CountDownLatch(1)

        mainHandler.post {
            result[0] = runCatching { playerController.getAudioTracksJson() }.getOrElse { error ->
                DebugLogStore.add("AndroidNativePlayerBridge", "Read audio tracks failed", error)
                "[]"
            }
            latch.countDown()
        }

        latch.await(1200, TimeUnit.MILLISECONDS)
        return result[0]
    }

    @JavascriptInterface
    fun selectAudioTrack(trackId: String): Boolean {
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        mainHandler.post {
            result.set(runCatching { playerController.selectAudioTrack(trackId) }.getOrElse { error ->
                DebugLogStore.add("AndroidNativePlayerBridge", "Select audio track failed", error)
                false
            })
            latch.countDown()
        }

        latch.await(1200, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
