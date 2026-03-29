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
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import kotlinx.coroutines.flow.collectLatest
import com.livetv.android.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var androidLogsBridge: AndroidLogsBridge
    private lateinit var nativePlayerController: NativeDirectPlayerController
    private lateinit var androidNativePlayerBridge: AndroidNativePlayerBridge
    private lateinit var nativeWatchViewModel: NativeWatchViewModel
    private lateinit var androidWatchBridge: AndroidWatchBridge
    private lateinit var nativeWatchProgramAdapter: NativeWatchProgramAdapter
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
        nativeWatchViewModel = ViewModelProvider(this)[NativeWatchViewModel::class.java]
        androidWatchBridge = AndroidWatchBridge(nativeWatchViewModel)
        nativeWatchProgramAdapter = NativeWatchProgramAdapter()
        DebugLogStore.add("MainActivity", "App created")

        setupNativeWatchOverlay()
        observeNativeWatchState()
        setupWebView()
    }

    private fun setupNativeWatchOverlay() {
        binding.nativeWatchEpgList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = nativeWatchProgramAdapter
            itemAnimator = null
        }
    }

    private fun observeNativeWatchState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                nativeWatchViewModel.uiState.collectLatest(::renderNativeWatchState)
            }
        }
    }

    private fun renderNativeWatchState(state: NativeWatchUiState) {
        renderNativeLoading(state)
        renderNativeWatchPanel(state)
    }

    private fun renderNativeLoading(state: NativeWatchUiState) {
        val loadingVisible = state.loading.visible
        binding.nativeLoadingContainer.isVisible = loadingVisible

        if (!loadingVisible) {
            binding.nativeLoadingChannelLogo.isVisible = false
            binding.nativeLoadingFallbackName.isVisible = false
            binding.nativeLoadingProgress.progress = 0
            binding.nativeLoadingSubtext.text = ""
            return
        }

        val fallbackName = state.channel?.name?.takeIf { it.isNotBlank() } ?: "Loading channel"
        val logoUrl = state.channel?.logoUrl

        binding.nativeLoadingProgress.progress = state.loading.progress.coerceIn(0, 100)
        binding.nativeLoadingText.text = state.loading.label.ifBlank { "Loading..." }
        binding.nativeLoadingSubtext.text = fallbackName
        binding.nativeLoadingFallbackName.text = fallbackName

        if (logoUrl.isNullOrBlank()) {
            binding.nativeLoadingChannelLogo.setImageDrawable(null)
            binding.nativeLoadingChannelLogo.isVisible = false
            binding.nativeLoadingFallbackName.isVisible = true
            return
        }

        binding.nativeLoadingChannelLogo.isVisible = true
        binding.nativeLoadingFallbackName.isVisible = false
        binding.nativeLoadingChannelLogo.load(logoUrl) {
            crossfade(true)
            listener(
                onError = { _, _ ->
                    binding.nativeLoadingChannelLogo.isVisible = false
                    binding.nativeLoadingFallbackName.isVisible = true
                },
            )
        }
    }

    private fun renderNativeWatchPanel(state: NativeWatchUiState) {
        val shouldShowPanel = ENABLE_NATIVE_WATCH_PANEL && state.isMenuVisible && !state.loading.visible
        binding.nativeWatchPanel.isVisible = shouldShowPanel
        if (!shouldShowPanel) return

        val channelName = state.channel?.name?.takeIf { it.isNotBlank() } ?: "Live TV"
        val playbackLabel = state.channel?.playbackMode?.takeIf { it.isNotBlank() } ?: "Live"
        binding.nativeWatchTitle.text = channelName
        binding.nativeWatchMeta.text =
            buildString {
                append("CH ")
                append(state.channel?.id?.ifBlank { "--" } ?: "--")
                append(" • ")
                append(playbackLabel.uppercase())
            }
        nativeWatchProgramAdapter.submitList(state.epg)
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
            addJavascriptInterface(androidWatchBridge, "AndroidWatch")
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
        nativeWatchViewModel.clear()
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
        private const val ENABLE_NATIVE_WATCH_PANEL = false
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
                  max-width: min(44vw, calc(440px * var(--android-tv-scale))) !important;
                  border-radius: calc(18px * var(--android-tv-scale)) !important;
                  padding: calc(12px * var(--android-tv-scale))
                           calc(14px * var(--android-tv-scale))
                           calc(10px * var(--android-tv-scale))
                           calc(14px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-title {
                  font-size: clamp(1.3rem, calc(1.72rem * var(--android-tv-scale)), 2rem) !important;
                }

                body.android-tv-shell .channel-time {
                  font-size: clamp(0.68rem, calc(0.8rem * var(--android-tv-scale)), 0.88rem) !important;
                }

                body.android-tv-shell .debug-log-button {
                  font-size: clamp(0.44rem, calc(0.48rem * var(--android-tv-scale)), 0.58rem) !important;
                  padding: calc(4px * var(--android-tv-scale)) calc(8px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .native-direct-stage-pill {
                  font-size: clamp(0.56rem, calc(0.62rem * var(--android-tv-scale)), 0.74rem) !important;
                  padding: calc(7px * var(--android-tv-scale)) calc(12px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-card {
                  width: min(54vw, calc(360px * var(--android-tv-scale))) !important;
                  gap: calc(12px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-overlay {
                  inset: 0 !important;
                }

                body.android-tv-shell .channel-loading-brand {
                  top: max(env(safe-area-inset-top, 0px), calc(28px * var(--android-tv-scale))) !important;
                  left: calc(28px * var(--android-tv-scale)) !important;
                  width: min(22vw, calc(180px * var(--android-tv-scale))) !important;
                }

                body.android-tv-shell .channel-loading-stage {
                  min-height: calc(132px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-logo {
                  width: min(100%, calc(220px * var(--android-tv-scale))) !important;
                  max-height: calc(136px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-fallback {
                  font-size: clamp(1.6rem, calc(1.9rem * var(--android-tv-scale)), 2.1rem) !important;
                }

                body.android-tv-shell .channel-loading-bar {
                  width: min(100%, calc(220px * var(--android-tv-scale))) !important;
                  height: calc(4px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-loading-copy {
                  font-size: clamp(0.88rem, calc(1rem * var(--android-tv-scale)), 1.08rem) !important;
                }

                body.android-tv-shell .channel-loading-subcopy {
                  font-size: clamp(0.56rem, calc(0.62rem * var(--android-tv-scale)), 0.72rem) !important;
                }

                body.android-tv-shell .epg-list {
                  width: min(40vw, calc(360px * var(--android-tv-scale))) !important;
                  gap: 0 !important;
                }

                body.android-tv-shell .epg-item {
                  padding: calc(8px * var(--android-tv-scale)) calc(10px * var(--android-tv-scale)) !important;
                  border-radius: calc(10px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .epg-title {
                  font-size: clamp(0.7rem, calc(0.76rem * var(--android-tv-scale)), 0.86rem) !important;
                }

                body.android-tv-shell .epg-time {
                  font-size: clamp(0.56rem, calc(0.6rem * var(--android-tv-scale)), 0.68rem) !important;
                }

                body.android-tv-shell .epg-badge {
                  font-size: clamp(0.44rem, calc(0.48rem * var(--android-tv-scale)), 0.56rem) !important;
                  padding: calc(2px * var(--android-tv-scale)) calc(6px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-audio-switcher {
                  gap: calc(6px * var(--android-tv-scale)) !important;
                  padding: calc(8px * var(--android-tv-scale)) calc(10px * var(--android-tv-scale)) !important;
                  border-radius: calc(12px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-audio-switcher-current {
                  font-size: clamp(0.56rem, calc(0.62rem * var(--android-tv-scale)), 0.72rem) !important;
                }

                body.android-tv-shell .channel-audio-switcher-option {
                  font-size: clamp(0.54rem, calc(0.58rem * var(--android-tv-scale)), 0.68rem) !important;
                  padding: calc(5px * var(--android-tv-scale)) calc(8px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-quick-controls {
                  gap: calc(5px * var(--android-tv-scale)) !important;
                  margin-top: calc(8px * var(--android-tv-scale)) !important;
                  padding: calc(5px * var(--android-tv-scale)) calc(6px * var(--android-tv-scale)) !important;
                  border-radius: calc(10px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-nav-button {
                  padding: calc(6px * var(--android-tv-scale)) calc(7px * var(--android-tv-scale)) !important;
                  gap: calc(5px * var(--android-tv-scale)) !important;
                  border-radius: calc(9px * var(--android-tv-scale)) !important;
                }

                body.android-tv-shell .channel-nav-label {
                  font-size: clamp(0.42rem, calc(0.46rem * var(--android-tv-scale)), 0.52rem) !important;
                }

                body.android-tv-shell .channel-nav-name {
                  font-size: clamp(0.56rem, calc(0.62rem * var(--android-tv-scale)), 0.72rem) !important;
                }

                body.android-tv-shell .channel-icon-button {
                  width: calc(32px * var(--android-tv-scale)) !important;
                  height: calc(32px * var(--android-tv-scale)) !important;
                  border-radius: calc(10px * var(--android-tv-scale)) !important;
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
