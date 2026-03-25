package com.livetv.android

import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DirectStreamInterceptor(
    private val appHost: String,
) {
    fun maybeIntercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        if (!url.host.equals(appHost, ignoreCase = true)) return null

        return when (url.encodedPath) {
            RESOLVE_PATH -> handleResolve(url)
            PROXY_PATH -> handleProxy(request, url)
            else -> null
        }
    }

    private fun handleResolve(url: Uri): WebResourceResponse {
        val rawUrl = url.getQueryParameter("url")
        if (rawUrl.isNullOrBlank()) {
            return jsonError(400, "Missing url parameter")
        }

        return try {
            debug("Resolve request for $rawUrl")
            val payload = resolveDirectStream(rawUrl)
            debug(
                "Resolve success channel=${payload.optString("channelId")} type=${payload.optString("streamType")} manifest=${payload.optString("manifestUrl")}",
            )
            jsonResponse(200, payload.toString())
        } catch (error: Exception) {
            Log.e(TAG, "Direct stream resolution failed", error)
            debug("Resolve failed for $rawUrl", error)
            jsonResponse(
                500,
                JSONObject()
                    .put("error", "Direct stream resolution failed")
                    .put("detail", error.message ?: "Unknown error")
                    .toString(),
            )
        }
    }

    private fun handleProxy(request: WebResourceRequest, url: Uri): WebResourceResponse {
        val rawTargetUrl = url.getQueryParameter("url")
        if (rawTargetUrl.isNullOrBlank()) {
            return jsonError(400, "Missing url parameter")
        }

        return try {
            debug("Proxy request ${request.method ?: "GET"} $rawTargetUrl")
            if (rawTargetUrl.startsWith("data:", ignoreCase = true)) {
                handleDataUrl(rawTargetUrl)
            } else {
                proxyHttpRequest(request, rawTargetUrl, url)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Direct stream proxy failed", error)
            debug("Proxy failed for $rawTargetUrl", error)
            jsonResponse(
                502,
                JSONObject()
                    .put("error", "Direct proxy request failed")
                    .put("detail", error.message ?: "Unknown error")
                    .toString(),
            )
        }
    }

    private fun resolveDirectStream(rawUrl: String): JSONObject {
        val playerUri = Uri.parse(rawUrl)
        val channelId = playerUri.getQueryParameter("id")
            ?: throw IllegalArgumentException("Player URL is missing channel id")

        val playerUrl = URL(rawUrl)
        val catalogUrl = URL(playerUrl, "./jstr.json").toString()
        val catalog = fetchCatalogJson(catalogUrl, rawUrl)
        val entry = findChannelEntry(catalog, channelId)
            ?: throw IllegalStateException("Channel not found in direct stream catalog")

        val manifestBaseUrl = pickManifest(entry)
        val manifestUrl = normalizeStreamUrl(appendToken(manifestBaseUrl, entry.optString("token")))
        val streamType = inferStreamType(manifestUrl)
            ?: throw IllegalStateException("No playable manifest found for this direct stream")
        val useJioDirectHeaders = isJioBackedUrl(manifestUrl) || isJioBackedUrl(rawUrl)

        val payload = JSONObject()
        payload.put("channelId", channelId)
        payload.put("channelName", entry.optString("name").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        payload.put("streamType", streamType)
        payload.put("manifestUrl", manifestUrl)
        payload.put("clearKeys", normalizeClearKeys(entry.optJSONObject("drm")) ?: JSONObject.NULL)
        payload.put("referer", normalizeReferer(entry.optString("referer"), rawUrl, useJioDirectHeaders))
        payload.put("userAgent", normalizeUserAgent(entry.optString("userAgent"), useJioDirectHeaders))
        payload.put("sourceUrl", rawUrl)
        return payload
    }

    private fun fetchCatalogJson(catalogUrl: String, referer: String): JSONArray {
        val baseHeaders = linkedMapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Accept" to "application/json,text/plain,*/*",
            "Referer" to referer,
        )

        val initial = fetchBytes(catalogUrl, "GET", baseHeaders)
        debug("Catalog initial fetch ${initial.statusCode} $catalogUrl")
        if (initial.statusCode !in 200..299) {
            throw IllegalStateException("Catalog fetch failed: ${initial.statusCode}")
        }

        val initialText = initial.body.toString(StandardCharsets.UTF_8.name())
        return try {
            JSONArray(initialText)
        } catch (_jsonError: Exception) {
            val challenge = parseChallengeHtml(initialText, appendQueryParam(catalogUrl, "i", "1"))
                ?: throw IllegalStateException("Unexpected catalog response")
            debug("Catalog cookie challenge detected for $catalogUrl")

            val retryHeaders = LinkedHashMap(baseHeaders)
            retryHeaders["Cookie"] = "__test=${challenge.cookieValue}"

            val retry = fetchBytes(challenge.targetUrl, "GET", retryHeaders)
            debug("Catalog challenge retry ${retry.statusCode} ${challenge.targetUrl}")
            if (retry.statusCode !in 200..299) {
                throw IllegalStateException("Catalog challenge retry failed: ${retry.statusCode}")
            }

            JSONArray(retry.body.toString(StandardCharsets.UTF_8.name()))
        }
    }

    private fun findChannelEntry(catalog: JSONArray, channelId: String): JSONObject? {
        for (index in 0 until catalog.length()) {
            val item = catalog.optJSONObject(index) ?: continue
            if (item.optString("channel_id") == channelId) {
                return item
            }
        }
        return null
    }

    private fun proxyHttpRequest(
        request: WebResourceRequest,
        rawTargetUrl: String,
        proxyUrl: Uri,
    ): WebResourceResponse {
        val targetUri = Uri.parse(rawTargetUrl)
        val scheme = targetUri.scheme?.lowercase()
        if (scheme !in setOf("http", "https")) {
            return jsonError(400, "Blocked target url")
        }

        val host = targetUri.host
        if (host.isNullOrBlank() || isPrivateHost(host)) {
            return jsonError(400, "Blocked target url")
        }

        val requestHeaders = linkedMapOf<String, String>()
        requestHeaders["User-Agent"] = proxyUrl.getQueryParameter("ua").orEmpty().ifBlank { DEFAULT_USER_AGENT }
        requestHeaders["Accept"] = request.requestHeaders["Accept"].orEmpty().ifBlank { "*/*" }
        requestHeaders["Accept-Encoding"] = "identity"
        requestHeaders["Connection"] = "keep-alive"

        proxyUrl.getQueryParameter("referer")?.takeIf { it.isNotBlank() }?.let { referer ->
            requestHeaders["Referer"] = referer
            runCatching { URI(referer).scheme + "://" + URI(referer).host }
                .getOrNull()
                ?.let { requestHeaders["Origin"] = it }
        }

        request.requestHeaders["Range"]?.let { requestHeaders["Range"] = it }

        val upstream = fetchBytes(rawTargetUrl, request.method ?: "GET", requestHeaders)
        if (shouldLogProxyResult(rawTargetUrl, upstream.statusCode)) {
            debug("Proxy upstream ${upstream.statusCode} ${request.method ?: "GET"} $rawTargetUrl")
        }
        val bodyStream = ByteArrayInputStream(upstream.body)
        val mimeType = upstream.headers["Content-Type"]?.substringBefore(';') ?: "application/octet-stream"
        val encoding = upstream.headers["Content-Type"]?.substringAfter("charset=", "utf-8") ?: "utf-8"

        return WebResourceResponse(
            mimeType,
            encoding,
            upstream.statusCode,
            upstream.reasonPhrase,
            filterResponseHeaders(upstream.headers),
            bodyStream,
        )
    }

    private fun handleDataUrl(rawTargetUrl: String): WebResourceResponse {
        val separatorIndex = rawTargetUrl.indexOf(',')
        if (separatorIndex <= 0) {
            return jsonError(400, "Invalid data url")
        }

        val meta = rawTargetUrl.substring(5, separatorIndex)
        val payload = rawTargetUrl.substring(separatorIndex + 1)
        val isBase64 = meta.contains(";base64", ignoreCase = true)
        val mimeType = meta.substringBefore(';').ifBlank { "text/plain" }

        val bytes = if (isBase64) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(StandardCharsets.UTF_8)
        }

        return WebResourceResponse(
            mimeType,
            "utf-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(bytes),
        )
    }

    private fun fetchBytes(
        url: String,
        method: String,
        headers: Map<String, String>,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            useCaches = false
            doInput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        return try {
            val statusCode = connection.responseCode
            val reasonPhrase = connection.responseMessage ?: "OK"
            val input = if (statusCode >= 400) connection.errorStream else connection.inputStream
            val body = input?.use { it.readFully() } ?: ByteArray(0)
            val responseHeaders = linkedMapOf<String, String>()

            connection.headerFields
                .filterKeys { it != null }
                .forEach { (name, values) ->
                    if (!values.isNullOrEmpty()) {
                        responseHeaders[name] = values.joinToString(", ")
                    }
                }

            HttpResponse(statusCode, reasonPhrase, responseHeaders, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun filterResponseHeaders(headers: Map<String, String>): Map<String, String> {
        val allowed = listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "Cache-Control",
            "ETag",
            "Last-Modified",
        )

        val result = linkedMapOf<String, String>()
        allowed.forEach { headerName ->
            headers[headerName]?.let { result[headerName] = it }
        }
        result["X-Robots-Tag"] = "noindex"
        return result
    }

    private fun normalizeClearKeys(drm: JSONObject?): JSONObject? {
        if (drm == null) return null
        val normalized = JSONObject()
        val keys = drm.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = drm.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) {
                normalized.put(key, value)
            }
        }
        return normalized.takeIf { it.length() > 0 }
    }

    private fun pickManifest(entry: JSONObject): String {
        val candidates = listOf("mpd", "m3u8", "hls", "manifest", "url")
        return candidates.firstNotNullOfOrNull { key ->
            entry.optString(key).takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun appendToken(url: String, token: String): String {
        if (url.isBlank() || token.isBlank() || url.contains(token)) return url
        val cleanToken = token.trimStart('?', '&')
        return buildString {
            append(url)
            append(if (url.contains('?')) '&' else '?')
            append(cleanToken)
        }
    }

    private fun normalizeStreamUrl(candidateUrl: String): String {
        if (candidateUrl.isBlank()) return candidateUrl
        return try {
            val parsed = Uri.parse(candidateUrl)
            parsed.buildUpon()
                .path(parsed.path?.replace(Regex("/+"), "/"))
                .build()
                .toString()
        } catch (_error: Exception) {
            candidateUrl.replace(Regex("^(https?://[^/]+)/+"), "$1/")
        }
    }

    private fun inferStreamType(candidateUrl: String): String? {
        return when {
            MPD_REGEX.containsMatchIn(candidateUrl) -> "dash"
            M3U8_REGEX.containsMatchIn(candidateUrl) -> "hls"
            else -> null
        }
    }

    private fun normalizeReferer(candidate: String, fallback: String, useJioDirectHeaders: Boolean): String {
        if (useJioDirectHeaders) return JIO_REFERER
        val value = candidate.trim()
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return value
        }
        return fallback
    }

    private fun normalizeUserAgent(candidate: String, useJioDirectHeaders: Boolean): String {
        if (useJioDirectHeaders) return JIO_USER_AGENT
        val value = candidate.trim()
        if (value.isBlank()) return DEFAULT_USER_AGENT

        val looksLikeRealUserAgent =
            value.contains("Mozilla/", ignoreCase = true) ||
                value.contains("AppleWebKit", ignoreCase = true) ||
                value.contains("Chrome/", ignoreCase = true) ||
                value.contains("okhttp", ignoreCase = true) ||
                value.contains("Dalvik", ignoreCase = true)

        return if (looksLikeRealUserAgent) value else DEFAULT_USER_AGENT
    }

    private fun isJioBackedUrl(candidateUrl: String): Boolean {
        return candidateUrl.contains(".jio.com", ignoreCase = true)
    }

    private fun parseChallengeHtml(html: String, fallbackUrl: String): ChallengeCookie? {
        val matches = CHALLENGE_HEX_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        if (matches.size < 3) return null

        val keyHex = matches[0]
        val ivHex = matches[1]
        val cipherHex = matches[2]
        val targetUrl = LOCATION_REGEX.find(html)?.groupValues?.get(1) ?: fallbackUrl

        return ChallengeCookie(
            cookieValue = decryptChallengeCookie(keyHex, ivHex, cipherHex),
            targetUrl = targetUrl,
        )
    }

    private fun decryptChallengeCookie(keyHex: String, ivHex: String, cipherHex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(hexToBytes(keyHex), "AES"),
            IvParameterSpec(hexToBytes(ivHex)),
        )
        return cipher.doFinal(hexToBytes(cipherHex)).toHexString()
    }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$key=$value"
    }

    private fun isPrivateHost(host: String): Boolean {
        val lowerHost = host.lowercase()
        if (lowerHost == "localhost" || lowerHost.endsWith(".localhost")) return true

        return runCatching { InetAddress.getByName(host) }.getOrNull()?.let { address ->
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress
        } ?: false
    }

    private fun shouldLogProxyResult(url: String, statusCode: Int): Boolean {
        if (statusCode >= 400) return true
        val lower = url.lowercase()
        return lower.contains(".mpd") ||
            lower.contains(".m3u8") ||
            lower.contains("manifest") ||
            lower.contains("license") ||
            lower.contains("clearkey")
    }

    private fun debug(message: String, error: Throwable? = null) {
        DebugLogStore.add(TAG, message, error)
    }

    private fun jsonResponse(statusCode: Int, body: String): WebResourceResponse {
        val phrase = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "OK"
        }

        return WebResourceResponse(
            "application/json",
            "utf-8",
            statusCode,
            phrase,
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun jsonError(statusCode: Int, message: String): WebResourceResponse {
        return jsonResponse(
            statusCode,
            JSONObject().put("error", message).toString(),
        )
    }

    private fun InputStream.readFully(): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { eachByte ->
        "%02x".format(eachByte)
    }

    private fun hexToBytes(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private data class ChallengeCookie(
        val cookieValue: String,
        val targetUrl: String,
    )

    private data class HttpResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    companion object {
        private const val TAG = "DirectStream"
        private const val RESOLVE_PATH = "/api/resolve-direct-stream"
        private const val PROXY_PATH = "/api/direct-stream-proxy"
        private const val JIO_REFERER = "https://www.jiotv.com/"
        private const val JIO_USER_AGENT = "okhttp/4.9.0"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val MPD_REGEX = Regex("""\.mpd(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        private val M3U8_REGEX = Regex("""\.m3u8(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        private val CHALLENGE_HEX_REGEX = Regex("""toNumbers\("([0-9a-f]+)"\)""", RegexOption.IGNORE_CASE)
        private val LOCATION_REGEX = Regex("location\\.href=\"([^\"]+)\"")
    }
}
