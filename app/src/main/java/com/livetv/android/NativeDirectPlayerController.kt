package com.livetv.android

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class NativeDirectPlayerController(
    private val context: Context,
    private val playerView: PlayerView,
) {
    private var player: ExoPlayer? = null
    private var activeManifestUrl: String? = null

    fun play(configJson: String): Boolean {
        val config = DirectStreamConfig.fromJson(configJson)
        DebugLogStore.add(TAG, "Opening native player for ${config.channelName.ifBlank { config.channelId }}")

        if (config.manifestUrl.isBlank()) {
            DebugLogStore.add(TAG, "Missing manifest url for native player")
            return false
        }

        if (activeManifestUrl == config.manifestUrl && player != null) {
            playerView.visibility = View.VISIBLE
            return true
        }

        release()

        val mediaHeaders = linkedMapOf(
            "Accept" to "*/*",
        )

        if (config.referer.isNotBlank()) {
            mediaHeaders["Referer"] = config.referer
            runCatching { Uri.parse(config.referer) }
                .getOrNull()
                ?.let { refererUri ->
                    if (!refererUri.scheme.isNullOrBlank() && !refererUri.host.isNullOrBlank()) {
                        mediaHeaders["Origin"] = "${refererUri.scheme}://${refererUri.host}"
                    }
                }
        }

        val manifestUri = Uri.parse(config.manifestUrl)
        val tokenEntries = manifestUri.queryParameterNames.associateWith { key ->
            manifestUri.getQueryParameter(key).orEmpty()
        }.filterValues { it.isNotBlank() }

        val httpDataSourceFactory =
            DefaultHttpDataSource.Factory()
                .setUserAgent(config.userAgent.ifBlank { DEFAULT_USER_AGENT })
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(mediaHeaders)

        val dataSourceFactory =
            ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
                val rewrittenUri = rewriteMediaUri(dataSpec.uri, manifestUri, tokenEntries)
                if (rewrittenUri != dataSpec.uri) {
                    DebugLogStore.add(TAG, "Rewrote media request to $rewrittenUri")
                    dataSpec.buildUpon().setUri(rewrittenUri).build()
                } else {
                    dataSpec
                }
            }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        buildDrmSessionManagerProvider(config)?.let(mediaSourceFactory::setDrmSessionManagerProvider)

        val exoPlayer =
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()

        exoPlayer.playWhenReady = true
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    DebugLogStore.add(TAG, "Playback state=$playbackState")
                }

                override fun onPlayerError(error: PlaybackException) {
                    val detail = when (val cause = error.cause) {
                        is DefaultHttpDataSource.InvalidResponseCodeException ->
                            " status=${cause.responseCode} url=${cause.dataSpec?.uri}"
                        is DataSourceException ->
                            " dataSourceReason=${cause.reason}"
                        else -> ""
                    }
                    DebugLogStore.add(TAG, "Native player error code=${error.errorCodeName}$detail", error)
                }
            },
        )

        val mediaItemBuilder =
            MediaItem.Builder()
                .setUri(config.manifestUrl)

        when (config.streamType.lowercase()) {
            "dash" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            "hls" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        if (config.clearKeys.isNotEmpty()) {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build(),
            )
        }

        val mediaItem = mediaItemBuilder.build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        player = exoPlayer
        activeManifestUrl = config.manifestUrl
        playerView.player = exoPlayer
        playerView.useController = false
        playerView.controllerAutoShow = false
        playerView.isClickable = false
        playerView.isFocusable = false
        playerView.visibility = View.VISIBLE

        return true
    }

    fun close() {
        release()
        DebugLogStore.add(TAG, "Closed native player")
    }

    private fun release() {
        playerView.visibility = View.GONE
        playerView.player = null
        player?.release()
        player = null
        activeManifestUrl = null
    }

    private fun buildDrmSessionManagerProvider(config: DirectStreamConfig): DrmSessionManagerProvider? {
        if (config.clearKeys.isEmpty()) return null

        val licensePayload = buildClearKeyPayload(config.clearKeys)
        return DrmSessionManagerProvider {
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(licensePayload))
        }
    }

    private fun buildClearKeyPayload(clearKeys: Map<String, String>): ByteArray {
        val keysJson = buildString {
            append("{\"keys\":[")
            clearKeys.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append("{\"kty\":\"oct\",\"kid\":\"")
                append(hexToBase64Url(entry.key))
                append("\",\"k\":\"")
                append(hexToBase64Url(entry.value))
                append("\"}")
            }
            append("],\"type\":\"temporary\"}")
        }
        return keysJson.toByteArray(StandardCharsets.UTF_8)
    }

    private fun hexToBase64Url(value: String): String {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "Invalid hex value" }
        val bytes =
            ByteArray(clean.length / 2) { index ->
                clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
    }

    private fun rewriteMediaUri(
        originalUri: Uri,
        manifestUri: Uri,
        tokenEntries: Map<String, String>,
    ): Uri {
        if (tokenEntries.isEmpty()) return originalUri
        if (!originalUri.isHierarchical) return originalUri
        if (!sameAuthority(originalUri, manifestUri)) return originalUri

        var builder = originalUri.buildUpon().clearQuery()
        originalUri.queryParameterNames.forEach { key ->
            val values = originalUri.getQueryParameters(key)
            if (values.isEmpty()) {
                builder = builder.appendQueryParameter(key, null)
            } else {
                values.forEach { value -> builder = builder.appendQueryParameter(key, value) }
            }
        }

        tokenEntries.forEach { (key, value) ->
            if (!originalUri.queryParameterNames.contains(key)) {
                builder = builder.appendQueryParameter(key, value)
            }
        }

        return builder.build()
    }

    private fun sameAuthority(left: Uri, right: Uri): Boolean {
        return left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            left.port == right.port
    }

    data class DirectStreamConfig(
        val channelId: String,
        val channelName: String,
        val manifestUrl: String,
        val streamType: String,
        val referer: String,
        val userAgent: String,
        val clearKeys: Map<String, String>,
    ) {
        companion object {
            fun fromJson(json: String): DirectStreamConfig {
                val root = JSONObject(json)
                val clearKeysObject = root.optJSONObject("clearKeys")
                val clearKeys = linkedMapOf<String, String>()
                val keysIterator = clearKeysObject?.keys()
                while (keysIterator?.hasNext() == true) {
                    val key = keysIterator.next()
                    val value = clearKeysObject.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) {
                        clearKeys[key] = value
                    }
                }

                return DirectStreamConfig(
                    channelId = root.optString("channelId"),
                    channelName = root.optString("channelName"),
                    manifestUrl = root.optString("manifestUrl"),
                    streamType = root.optString("streamType"),
                    referer = root.optString("referer"),
                    userAgent = root.optString("userAgent"),
                    clearKeys = clearKeys,
                )
            }
        }
    }

    companion object {
        private const val TAG = "NativeDirectPlayer"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
