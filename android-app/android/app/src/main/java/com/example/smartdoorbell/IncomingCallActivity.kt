package com.example.smartdoorbell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class IncomingCallActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var actionsZone: FrameLayout
    private var alreadyTriedFallback = false
    private var terminalLoadError = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        DoorbellNotifier.cancelIncomingCall(this)
        if (!isFinishing) finish()
    }
    private val answeredSessionRunnable = Runnable {
        if (!isFinishing) finish()
    }
    private val backgroundMicStopRunnable = Runnable { stopWebMicrophone() }

    private val answerUrl: String
        get() = intent.getStringExtra(DoorbellConfig.EXTRA_OPEN_URL) ?: DoorbellConfig.DEFAULT_ATTEND_URL

    private val previewUrl: String
        get() = intent.getStringExtra(DoorbellConfig.EXTRA_PREVIEW_URL)
            ?: DoorbellConfig.previewUrlFromAnswer(answerUrl)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DoorbellConfig.initialize(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        showOverLockScreen()
        buildLayout()
        DoorbellUrlChooser.chooseAsync(previewUrl, "/?native_call=1") { url ->
            if (isFinishing || isDestroyed) return@chooseAsync
            showStatus(if (url.contains(".local.")) "Conectando pela rede local…" else "Conectando pela nuvem…")
            webView.loadUrl(url)
        }
        timeoutHandler.postDelayed(timeoutRunnable, DoorbellNotifier.CALL_TIMEOUT_MS)
    }

    override fun onStart() {
        super.onStart()
        timeoutHandler.removeCallbacks(backgroundMicStopRunnable)
    }

    override fun onResume() {
        super.onResume()
        ApkDownloadSupport.resumePendingIfAllowed(this)
    }

    override fun onStop() {
        timeoutHandler.removeCallbacks(backgroundMicStopRunnable)
        timeoutHandler.postDelayed(backgroundMicStopRunnable, BACKGROUND_MIC_TIMEOUT_MS)
        super.onStop()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    terminalLoadError = false
                    showStatus("Carregando câmeras…")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!terminalLoadError) statusView.visibility = View.GONE
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        tryFallback(view, request.url.toString())
                    }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    if (failingUrl != null) tryFallback(view, failingUrl)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val needsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    val needsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    val audioGranted = ContextCompat.checkSelfPermission(
                        this@IncomingCallActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    val cameraGranted = ContextCompat.checkSelfPermission(
                        this@IncomingCallActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if ((!needsAudio || audioGranted) && (!needsVideo || cameraGranted)) {
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                }
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
            CookieManager.getInstance().setAcceptCookie(true)
        }
        ApkDownloadSupport.install(this, webView)

        statusView = TextView(this).apply {
            text = "Preparando câmeras…"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(3, 7, 18))
        }

        previewContainer = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
        }

        val decline = callButton("Recusar", Color.rgb(211, 47, 47)) { decline() }
        val answer = callButton("Atender", Color.rgb(20, 150, 75)) { answer() }
        val buttonParams = LinearLayout.LayoutParams(0, dp(72), 1f).apply {
            setMargins(dp(8), 0, dp(8), 0)
        }
        actionsRow.addView(decline, buttonParams)
        actionsRow.addView(answer, buttonParams)

        actionsZone = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(3, 7, 18))
            addView(
                actionsRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        root.addView(
            previewContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeightPx())
        )
        root.addView(
            actionsZone,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun callButton(label: String, color: Int, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 19f
            setTextColor(Color.WHITE)
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(36).toFloat()
            }
            contentDescription = label
            setOnClickListener { action() }
        }
    }

    private fun answer() {
        DoorbellNotifier.cancelIncomingCall(this)
        timeoutHandler.removeCallbacks(timeoutRunnable)
        actionsZone.visibility = View.GONE
        previewContainer.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        showStatus("Atendendo…")

        val currentHost = runCatching { Uri.parse(webView.url).host }.getOrNull()
        if (DoorbellConfig.isDoorbellHost(currentHost)) {
            webView.evaluateJavascript(
                """
                fetch('/api/attend', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {'content-type': 'application/json'},
                    body: '{}'
                }).catch(() => {});
                document.body.classList.remove('native-call');
                document.body.style.overflow = '';
                window.scrollTo(0, 0);
                """.trimIndent(),
                null
            )
            statusView.visibility = View.GONE
        } else {
            DoorbellUrlChooser.chooseAsync(answerUrl, "/atender") { chosenAnswerUrl ->
                if (isFinishing || isDestroyed) return@chooseAsync
                webView.loadUrl(chosenAnswerUrl)
            }
        }
        timeoutHandler.postDelayed(answeredSessionRunnable, ANSWERED_SESSION_TIMEOUT_MS)
    }

    private fun decline() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        DoorbellNotifier.cancelIncomingCall(this)
        finish()
    }

    private fun tryFallback(view: WebView, failedUrl: String) {
        if (alreadyTriedFallback) {
            terminalLoadError = true
            showStatus("Não foi possível carregar as câmeras. Verifique a rede e tente novamente.")
            return
        }
        val fallback = DoorbellUrlChooser.alternateForFailedUrl(failedUrl)
        if (fallback == null) {
            terminalLoadError = true
            showStatus("Não foi possível carregar as câmeras.")
            return
        }
        alreadyTriedFallback = true
        showStatus("Tentando outra rota…")
        view.post { view.loadUrl(fallback) }
    }

    private fun showStatus(message: String) {
        if (!::statusView.isInitialized) return
        statusView.text = message
        statusView.visibility = View.VISIBLE
    }

    private fun stopWebMicrophone() {
        if (!::webView.isInitialized) return
        webView.post {
            webView.evaluateJavascript(
                "if (window.campainhaStopTalk) window.campainhaStopTalk();",
                null
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun previewHeightPx(): Int {
        val metrics = resources.displayMetrics
        val naturalTwoCameraHeight = (metrics.widthPixels * 9 / 8) + dp(4)
        val maxHeight = (metrics.heightPixels * 0.68f).toInt()
        return minOf(naturalTwoCameraHeight, maxHeight)
    }

    companion object {
        private const val ANSWERED_SESSION_TIMEOUT_MS = 5 * 60 * 1000L
        private const val BACKGROUND_MIC_TIMEOUT_MS = 60_000L
    }
}
