package com.livetv.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.livetv.android.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var androidLogsBridge: AndroidLogsBridge
    private lateinit var nativePlayerController: NativeDirectPlayerController
    private lateinit var androidNativePlayerBridge: AndroidNativePlayerBridge
    private val directStreamInterceptor = DirectStreamInterceptor(TARGET_HOST)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_LiveTV)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidLogsBridge = AndroidLogsBridge(this)
        nativePlayerController = NativeDirectPlayerController(this, binding.nativePlayerView)
        androidNativePlayerBridge = AndroidNativePlayerBridge(nativePlayerController)
        DebugLogStore.add("MainActivity", "App created")

        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.displayZoomControls = false
            settings.builtInZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.textZoom = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settings.offscreenPreRaster = true
            }

            settings.userAgentString = DIRECT_STREAM_USER_AGENT

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@with, true)
            }

            setBackgroundColor(Color.TRANSPARENT)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setInitialScale(100)
            addJavascriptInterface(androidLogsBridge, "AndroidLogs")
            addJavascriptInterface(androidNativePlayerBridge, "AndroidNativePlayer")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val source = consoleMessage.sourceId()?.substringAfterLast('/') ?: "inline"
                    DebugLogStore.add(
                        "WebConsole",
                        "${consoleMessage.messageLevel()} $source:${consoleMessage.lineNumber()} ${consoleMessage.message()}",
                    )
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false // Load all links in the WebView
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ) = directStreamInterceptor.maybeIntercept(request)

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    DebugLogStore.add("WebView", "Page started ${url ?: "unknown"}")
                    view?.evaluateJavascript(
                        """
                        (function () {
                          if (document.documentElement) {
                            document.documentElement.style.background = '#000';
                          }
                          if (document.body) {
                            document.body.style.background = '#000';
                            document.body.style.margin = '0';
                          }
                        })();
                        """.trimIndent(),
                        null,
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    DebugLogStore.add("WebView", "Page finished ${url ?: "unknown"}")
                    view?.let { injectAndroidTvShell(it) }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true || request?.url?.host == TARGET_HOST) {
                        DebugLogStore.add(
                            "WebView",
                            "Request error ${request.url} code=${error?.errorCode} desc=${error?.description ?: "unknown"}",
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val url = request?.url?.toString().orEmpty()
                    if (request?.isForMainFrame == true || url.contains("/api/")) {
                        DebugLogStore.add(
                            "WebView",
                            "HTTP ${errorResponse?.statusCode ?: -1} ${request?.method ?: "GET"} $url",
                        )
                    }
                }
            }

            DebugLogStore.add("WebView", "Loading $TARGET_URL")
            loadUrl(TARGET_URL)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle Back button for WebView navigation
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        DebugLogStore.add("MainActivity", "App destroyed")
        nativePlayerController.close()
        with(binding.webView) {
            stopLoading()
            clearHistory()
            clearCache(false)
            webChromeClient = null
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TARGET_URL = "https://jiolivetv.vercel.app"
        private const val TARGET_HOST = "jiolivetv.vercel.app"
        private const val DIRECT_STREAM_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private fun injectAndroidTvShell(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function () {
              const doc = document;
              const body = doc.body;
              const root = doc.documentElement;
              if (!body || !root) return;

              const width = Math.max(window.innerWidth || 0, root.clientWidth || 0, 1280);
              const height = Math.max(window.innerHeight || 0, root.clientHeight || 0, 720);
              const scale = Math.max(0.82, Math.min(Math.min(width / 1920, height / 1080), 1.18));
              const compact = width < 1366 || height < 768;

              body.classList.add('android-tv-shell');

              let style = doc.getElementById('android-tv-shell-style');
              if (!style) {
                style = doc.createElement('style');
                style.id = 'android-tv-shell-style';
                doc.head.appendChild(style);
              }

              style.textContent = `
                :root {
                  --android-tv-scale: ${'$'}{scale};
                }

                html, body, #root {
                  width: 100%;
                  height: 100%;
                  overflow: hidden !important;
                  background: #000 !important;
                }

                html.android-native-direct-active,
                body.android-native-direct-active,
                body.android-native-direct-active #root {
                  background: transparent !important;
                }

                body.android-tv-shell {
                  overscroll-behavior: none;
                }

                body.android-tv-shell .tv-container {
                  min-height: 100vh;
                }

                body.android-tv-shell .overlay-container {
                  gap: calc(36px * var(--android-tv-scale));
                  padding-left: calc(40px * var(--android-tv-scale));
                  padding-right: calc(40px * var(--android-tv-scale));
                  padding-bottom: calc(${ '$' }{compact ? 26 : 40}px * var(--android-tv-scale));
                }

                body.android-tv-shell .glass-panel {
                  max-width: min(62vw, calc(640px * var(--android-tv-scale))) !important;
                  border-radius: calc(24px * var(--android-tv-scale)) !important;
                  padding: calc(18px * var(--android-tv-scale))
                           calc(22px * var(--android-tv-scale))
                           calc(18px * var(--android-tv-scale))
                           calc(22px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-title {
                  font-size: clamp(1.8rem, calc(2.6rem * var(--android-tv-scale)), 3rem) !important;
                }

                body.android-tv-shell .channel-time {
                  font-size: clamp(0.9rem, calc(1.1rem * var(--android-tv-scale)), 1.25rem) !important;
                }

                body.android-tv-shell .debug-log-button {
                  font-size: clamp(0.5rem, calc(0.52rem * var(--android-tv-scale)), 0.68rem) !important;
                  padding: calc(5px * var(--android-tv-scale)) calc(10px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .native-direct-stage-pill {
                  font-size: clamp(0.56rem, calc(0.62rem * var(--android-tv-scale)), 0.74rem) !important;
                  padding: calc(7px * var(--android-tv-scale)) calc(12px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-card {
                  min-width: min(76vw, calc(380px * var(--android-tv-scale))) !important;
                  padding: calc(18px * var(--android-tv-scale))
                           calc(20px * var(--android-tv-scale))
                           calc(16px * var(--android-tv-scale)) !important;
                  border-radius: calc(20px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-label {
                  font-size: clamp(0.82rem, calc(0.9rem * var(--android-tv-scale)), 1rem) !important;
                }

                body.android-tv-shell .channel-loading-bar {
                  height: calc(10px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-percent {
                  font-size: clamp(0.66rem, calc(0.72rem * var(--android-tv-scale)), 0.82rem) !important;
                }

                body.android-tv-shell .epg-list {
                  width: min(52vw, calc(520px * var(--android-tv-scale))) !important;
                  gap: calc(6px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .epg-item {
                  padding: calc(14px * var(--android-tv-scale)) calc(20px * var(--android-tv-scale)) !important;
                  border-radius: calc(14px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .epg-title {
                  font-size: clamp(0.9rem, calc(1rem * var(--android-tv-scale)), 1.12rem) !important;
                }

                body.android-tv-shell .epg-time {
                  font-size: clamp(0.72rem, calc(0.78rem * var(--android-tv-scale)), 0.9rem) !important;
                }

                body.android-tv-shell .epg-badge {
                  font-size: clamp(0.58rem, calc(0.65rem * var(--android-tv-scale)), 0.76rem) !important;
                  padding: calc(4px * var(--android-tv-scale)) calc(10px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-audio-switcher {
                  gap: calc(10px * var(--android-tv-scale)) !important;
                  padding: calc(14px * var(--android-tv-scale)) calc(16px * var(--android-tv-scale)) !important;
                  border-radius: calc(18px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-audio-switcher-current {
                  font-size: clamp(0.72rem, calc(0.8rem * var(--android-tv-scale)), 0.94rem) !important;
                }

                body.android-tv-shell .channel-audio-switcher-option {
                  font-size: clamp(0.68rem, calc(0.76rem * var(--android-tv-scale)), 0.9rem) !important;
                  padding: calc(8px * var(--android-tv-scale)) calc(13px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-audio-switcher-hint {
                  font-size: clamp(0.62rem, calc(0.68rem * var(--android-tv-scale)), 0.78rem) !important;
                }

                body.android-tv-shell .channel-tune-hint {
                  padding: calc(18px * var(--android-tv-scale)) calc(30px * var(--android-tv-scale)) !important;
                  border-radius: calc(20px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-tune-hint-digits {
                  font-size: clamp(2.2rem, calc(3.2rem * var(--android-tv-scale)), 3.4rem) !important;
                }

                body.android-tv-shell .channel-tune-hint-label,
                body.android-tv-shell .info-footer,
                body.android-tv-shell .remote-hint-row {
                  letter-spacing: 0.12em !important;
                }

                body.android-tv-shell img[alt][src] {
                  image-rendering: auto;
                }
              `;
            })();
            """.trimIndent(),
            null,
        )
    }
}
