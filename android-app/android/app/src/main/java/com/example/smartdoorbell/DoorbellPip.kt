package com.example.smartdoorbell

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Picture-in-Picture shared by MainActivity and IncomingCallActivity: a floating window
 * with only the doorbell camera (the web UI switches to body.pip-mode) and two native
 * actions, microphone and gate, forwarded to the page's window.campainha* hooks.
 */
object DoorbellPip {
    const val ACTION_PIP_TALK = "com.example.smartdoorbell.PIP_TALK"
    const val ACTION_PIP_GATE = "com.example.smartdoorbell.PIP_GATE"

    // Same ratio as the web UI's #stage (Reolink 4:3 with +20% height).
    private val ASPECT_RATIO = Rational(10, 9)

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun isActive(activity: Activity): Boolean = activity.isInPictureInPictureMode

    /** Registers the PiP params; on Android 12+ [autoEnter] lets the home gesture enter PiP. */
    fun update(activity: Activity, autoEnter: Boolean) {
        if (!isSupported(activity)) return
        runCatching { activity.setPictureInPictureParams(params(activity, autoEnter)) }
    }

    fun enter(activity: Activity): Boolean {
        if (!isSupported(activity) || isActive(activity)) return false
        return runCatching { activity.enterPictureInPictureMode(params(activity, true)) }.getOrDefault(false)
    }

    /** Receives the PiP action taps and runs them in [webView]. Unregister in onDestroy. */
    fun registerActions(context: Context, webView: () -> WebView?): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val script = when (intent.action) {
                    ACTION_PIP_TALK -> "window.campainhaToggleTalk?.();"
                    ACTION_PIP_GATE -> "window.campainhaToggleGate?.();"
                    else -> return
                }
                webView()?.evaluateJavascript(script, null)
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_TALK)
            addAction(ACTION_PIP_GATE)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    fun notifyWebView(webView: WebView?, inPip: Boolean) {
        webView?.evaluateJavascript("window.campainhaSetPipMode?.($inPip);", null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun params(context: Context, autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ASPECT_RATIO)
            .setActions(
                listOf(
                    action(context, ACTION_PIP_TALK, R.drawable.ic_pip_mic, "Microfone", "Ligar ou desligar o microfone", 1),
                    action(context, ACTION_PIP_GATE, R.drawable.ic_pip_gate, "Portão", "Acionar o portão", 2)
                )
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun action(
        context: Context,
        action: String,
        icon: Int,
        title: String,
        description: String,
        requestCode: Int
    ): RemoteAction {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(context, icon), title, description, pendingIntent)
    }
}
