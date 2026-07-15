package com.example.smartdoorbell

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var promptedNotificationAccessThisSession = false
    private var promptedFullScreenThisSession = false
    private var setupDialog: AlertDialog? = null
    private var activityResumed = false
    private val backgroundMicStopRunnable = Runnable { stopWebMicrophone() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        scheduleSpecialAccessCheck()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DoorbellNotifier.ensureChannels(this)
        ApkDownloadSupport.install(this, bridge.webView)
        requestNotificationPermissionIfNeeded()
        if (!handleOpenUrlIntent(intent)) {
            loadDoorbellUrl(DoorbellConfig.PUBLIC_BASE_URL + "/", "/")
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(backgroundMicStopRunnable)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        ApkDownloadSupport.resumePendingIfAllowed(this)
        scheduleSpecialAccessCheck()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(backgroundMicStopRunnable)
        mainHandler.postDelayed(backgroundMicStopRunnable, BACKGROUND_MIC_TIMEOUT_MS)
        super.onStop()
    }

    override fun onDestroy() {
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

    private fun scheduleSpecialAccessCheck() {
        mainHandler.removeCallbacksAndMessages(SETUP_TOKEN)
        mainHandler.postAtTime({ promptNextMissingAccess() }, SETUP_TOKEN, android.os.SystemClock.uptimeMillis() + 500)
    }

    private fun promptNextMissingAccess() {
        if (!activityResumed || isFinishing || isDestroyed || setupDialog?.isShowing == true) return
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

    private fun stopWebMicrophone() {
        bridge?.webView?.post {
            bridge?.webView?.evaluateJavascript(
                "if (window.campainhaStopTalk) window.campainhaStopTalk();",
                null
            )
        }
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
