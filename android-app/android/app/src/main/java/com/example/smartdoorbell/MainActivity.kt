package com.example.smartdoorbell

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.getcapacitor.BridgeActivity
import java.net.URLEncoder

class MainActivity : BridgeActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var promptedNotificationAccessThisSession = false
    private var promptedFullScreenThisSession = false
    private var setupDialog: AlertDialog? = null
    private var activityResumed = false
    private var launchingOwnActivity = false
    private var pipReceiver: BroadcastReceiver? = null
    private val backgroundMediaStopRunnable = Runnable { suspendWebMedia() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        scheduleSpecialAccessCheck()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DoorbellNotifier.ensureChannels(this)
        ApkDownloadSupport.install(this, bridge.webView)
        val configured = DoorbellConfig.initialize(this)
        if (!configured) {
            showPairingDialog()
        } else if (!handleOpenUrlIntent(intent)) {
            loadDoorbellUrl(DoorbellConfig.PUBLIC_BASE_URL + "/", "/")
        }
        if (configured) requestNotificationPermissionIfNeeded()
        pipReceiver = DoorbellPip.registerActions(this) { bridge?.webView }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(backgroundMediaStopRunnable)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        launchingOwnActivity = false
        DoorbellPip.update(this, autoEnter = DoorbellConfig.isConfigured())
        bridge?.webView?.evaluateJavascript("window.campainhaResumeMedia?.();", null)
        ApkDownloadSupport.resumePendingIfAllowed(this)
        if (DoorbellConfig.isConfigured()) scheduleSpecialAccessCheck()
    }

    // Media is not suspended here: entering PiP pauses the activity and the camera must keep playing.
    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    // Pure background (not PiP): the microphone and streams are switched off after one minute.
    override fun onStop() {
        mainHandler.removeCallbacks(backgroundMediaStopRunnable)
        if (!DoorbellPip.isActive(this)) {
            mainHandler.postDelayed(backgroundMediaStopRunnable, BACKGROUND_MIC_TIMEOUT_MS)
        }
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (DoorbellConfig.isConfigured() && !launchingOwnActivity && setupDialog?.isShowing != true) {
            DoorbellPip.enter(this)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        DoorbellPip.notifyWebView(bridge?.webView, isInPictureInPictureMode)
        // PiP window closed by the user: the activity is already stopped, so start the background rule now.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mainHandler.removeCallbacks(backgroundMediaStopRunnable)
            mainHandler.postDelayed(backgroundMediaStopRunnable, BACKGROUND_MIC_TIMEOUT_MS)
        }
    }

    // Settings screens and the APK installer opened by the app must not shrink it into PiP.
    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        launchingOwnActivity = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onDestroy() {
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        pipReceiver = null
        mainHandler.removeCallbacksAndMessages(null)
        setupDialog?.dismiss()
        setupDialog = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenUrlIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showPairingDialog() {
        if (isFinishing || isDestroyed || setupDialog?.isShowing == true) return
        val padding = (20 * resources.displayMetrics.density).toInt()
        val defaultEndpoint = PairingConfig.defaultEndpoint
        // On Wi-Fi the build's default server is almost certainly the right one, so
        // the address is hidden behind a link instead of being asked every time.
        val hideEndpoint = defaultEndpoint.isNotBlank() && isOnWifi()
        val endpointInput = EditText(this).apply {
            hint = "https://campainha.exemplo.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(defaultEndpoint)
            visibility = if (hideEndpoint) View.GONE else View.VISIBLE
        }
        val changeServerLink = TextView(this).apply {
            text = "Servidor: ${DoorbellConfig.hostOf(defaultEndpoint).orEmpty()} · trocar"
            setPadding(0, padding / 2, 0, padding / 2)
            setTextColor(currentTextColorAccent())
            visibility = if (hideEndpoint) View.VISIBLE else View.GONE
            setOnClickListener {
                visibility = View.GONE
                endpointInput.visibility = View.VISIBLE
                endpointInput.requestFocus()
            }
        }
        val nameInput = EditText(this).apply {
            hint = "Seu nome (ex.: Gabriel)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
        }
        val passwordInput = EditText(this).apply {
            hint = "Senha"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            for (view in listOf(changeServerLink, endpointInput, nameInput, passwordInput)) {
                addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        setupDialog = AlertDialog.Builder(this)
            .setTitle("Conectar à campainha")
            .setMessage(
                if (hideEndpoint) "Informe seu nome e a senha da campainha."
                else "Informe o endpoint HTTPS, seu nome e a senha configurada no servidor."
            )
            .setView(form)
            .setCancelable(false)
            .setPositiveButton("Conectar", null)
            .create()
        setupDialog?.setOnShowListener {
            setupDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { button ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameInput.error = "Informe seu nome"
                    nameInput.requestFocus()
                    return@setOnClickListener
                }
                button.isEnabled = false
                PairingConfig.pairAsync(endpointInput.text.toString(), passwordInput.text.toString(), name) { result ->
                    button.isEnabled = true
                    result.onSuccess { pairing ->
                        PairingConfig.save(this, pairing.config)
                        DoorbellConfig.apply(pairing.config)
                        passwordInput.text?.clear()
                        setupDialog?.dismiss()
                        setupDialog = null
                        requestNotificationPermissionIfNeeded()
                        val enrollment = pairing.enrollment
                        if (enrollment != null) {
                            Toast.makeText(this, "Campainha conectada. Acesso fora de casa ativado.", Toast.LENGTH_LONG).show()
                            postDeviceEnrollment(enrollment)
                        } else {
                            Toast.makeText(
                                this,
                                "Campainha conectada. Para abrir fora de casa sem login, ative em casa nas Configurações.",
                                Toast.LENGTH_LONG
                            ).show()
                            loadDoorbellUrl(DoorbellConfig.PUBLIC_BASE_URL + "/", "/")
                        }
                    }.onFailure { error ->
                        passwordInput.text?.clear()
                        passwordInput.requestFocus()
                        // A network failure may mean the default server is wrong; let the user see it.
                        if (endpointInput.visibility != View.VISIBLE && error !is SecurityException) {
                            changeServerLink.visibility = View.GONE
                            endpointInput.visibility = View.VISIBLE
                        }
                        Toast.makeText(this, error.message ?: "Falha no pareamento", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        setupDialog?.show()
    }

    /** Top-level POST so the server's device cookie lands in the WebView's cookie jar. */
    private fun postDeviceEnrollment(enrollment: DeviceEnrollment) {
        val next = DoorbellConfig.withAppVersion("/")
        val body = "code=${URLEncoder.encode(enrollment.code, "UTF-8")}&next=${URLEncoder.encode(next, "UTF-8")}"
        bridge?.webView?.post {
            bridge?.webView?.postUrl(enrollment.enrollUrl, body.toByteArray(Charsets.UTF_8))
        }
    }

    private fun isOnWifi(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun currentTextColorAccent(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorAccent, value, true)
        return value.data
    }

    private fun scheduleSpecialAccessCheck() {
        mainHandler.removeCallbacksAndMessages(SETUP_TOKEN)
        mainHandler.postAtTime({ promptNextMissingAccess() }, SETUP_TOKEN, android.os.SystemClock.uptimeMillis() + 500)
    }

    private fun promptNextMissingAccess() {
        if (!DoorbellConfig.isConfigured() || !activityResumed || isFinishing || isDestroyed || setupDialog?.isShowing == true) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        if (!isNotificationListenerEnabled() && !promptedNotificationAccessThisSession) {
            promptedNotificationAccessThisSession = true
            showAccessDialog(
                title = "Ativar chamadas da campainha",
                message = "Permita que o app Campainha veja apenas o gatilho discreto enviado pelo Home Assistant. Isso é necessário para abrir a chamada nativa.",
                positiveLabel = "Abrir acesso a notificações"
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            return
        }

        if (!DoorbellNotifier.canUseFullScreenIntent(this) && !promptedFullScreenThisSession) {
            promptedFullScreenThisSession = true
            showAccessDialog(
                title = "Permitir tela cheia",
                message = "Ative Campainha em “Notificações em tela cheia”. Assim, quando alguém tocar, a tela acende e a câmera aparece mesmo com o celular bloqueado.",
                positiveLabel = "Abrir configuração"
            ) {
                DoorbellNotifier.fullScreenSettingsIntent(this)?.let { startActivity(it) }
            }
        }
    }

    private fun showAccessDialog(
        title: String,
        message: String,
        positiveLabel: String,
        positiveAction: () -> Unit
    ) {
        setupDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Agora não", null)
            .setPositiveButton(positiveLabel) { _, _ ->
                runCatching { positiveAction() }
            }
            .create()
        setupDialog?.setOnDismissListener {
            setupDialog = null
            scheduleSpecialAccessCheck()
        }
        setupDialog?.show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun handleOpenUrlIntent(intent: Intent?): Boolean {
        val openUrl = resolveOpenUrl(intent) ?: return false
        DoorbellNotifier.cancelIncomingCall(this)
        loadDoorbellUrl(openUrl, if (openUrl.contains("/atender")) "/atender" else "/")
        return true
    }

    private fun loadDoorbellUrl(preferredUrl: String, defaultPath: String) {
        DoorbellUrlChooser.chooseAsync(preferredUrl, defaultPath) { chosenUrl ->
            bridge?.webView?.post {
                bridge?.webView?.loadUrl(DoorbellConfig.withAppVersion(chosenUrl))
            }
        }
    }

    private fun suspendWebMedia() {
        bridge?.webView?.evaluateJavascript("window.campainhaSuspendMedia?.();", null)
    }

    private fun resolveOpenUrl(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(DoorbellConfig.EXTRA_OPEN_URL)?.let { return it }
        val data = intent.data ?: return null
        if (data.scheme == BuildConfig.DEEP_LINK_SCHEME) {
            data.getQueryParameter("url")?.let { return it }
            return when {
                data.host == "atender" || data.path == "/atender" -> DoorbellConfig.DEFAULT_ATTEND_URL
                data.host == "open" -> DoorbellConfig.PUBLIC_BASE_URL + "/"
                else -> DoorbellConfig.PUBLIC_BASE_URL + "/"
            }
        }
        if (data.scheme == "https" && DoorbellConfig.isDoorbellHost(data.host)) {
            return data.toString()
        }
        return null
    }

    companion object {
        private val SETUP_TOKEN = Any()
        private const val BACKGROUND_MIC_TIMEOUT_MS = 60_000L
    }
}
