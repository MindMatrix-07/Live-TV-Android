package com.livetv.android

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class AndroidWatchBridge(
    private val watchViewModel: NativeWatchViewModel,
) {
    @JavascriptInterface
    fun isAvailable(): Boolean = true

    @JavascriptInterface
    fun clearState(): Boolean {
        watchViewModel.clear()
        return true
    }

    @JavascriptInterface
    fun syncState(payloadJson: String): Boolean {
        return runCatching {
            val payload = JSONObject(payloadJson)
            val channelObject = payload.optJSONObject("channel")
            val loadingObject = payload.optJSONObject("loading")
            val epgArray = payload.optJSONArray("epg")

            watchViewModel.syncFromWeb(
                channel = channelObject?.toNativeWatchChannel(),
                loading = loadingObject.toNativeWatchLoadingState(),
                epg = epgArray.toNativeWatchPrograms(),
                isMenuVisible = payload.optBoolean("menuVisible", false),
                isNativePlayerActive = payload.optBoolean("nativePlayerActive", false),
            )
        }.onFailure { error ->
            DebugLogStore.add("AndroidWatchBridge", "syncState failed", error)
        }.isSuccess
    }

    private fun JSONObject.toNativeWatchChannel(): NativeWatchChannel {
        return NativeWatchChannel(
            id = optString("id"),
            name = optString("name"),
            logoUrl = optString("logoUrl").takeIf { it.isNotBlank() },
            playbackMode = optString("playbackMode"),
            isDirectStream = optBoolean("isDirectStream", false),
        )
    }

    private fun JSONObject?.toNativeWatchLoadingState(): NativeWatchLoadingState {
        if (this == null) return NativeWatchLoadingState()
        return NativeWatchLoadingState(
            visible = optBoolean("visible", false),
            label = optString("label"),
            progress = optInt("progress", 0),
        )
    }

    private fun JSONArray?.toNativeWatchPrograms(): List<NativeWatchProgram> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    NativeWatchProgram(
                        title = item.optString("title"),
                        subtitle = item.optString("subtitle"),
                        isPlaceholder = item.optBoolean("isPlaceholder", false),
                        isCurrent = item.optBoolean("isCurrent", false),
                    ),
                )
            }
        }
    }
}
