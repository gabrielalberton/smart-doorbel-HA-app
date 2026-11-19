package com.example.smartdoorbell

import android.app.Activity
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Doorbell audio is always hands-free: the built-in loudspeaker, never Bluetooth,
 * a wired headset or the earpiece, and always covered by echo cancellation.
 *
 * The page calls [Bridge.setActive] with true while the camera sound or the
 * microphone is on. The phone is then held in MODE_IN_COMMUNICATION with the
 * loudspeaker as the communication device. Chromium picks the output stream type
 * from the current audio mode, so camera audio opened in this mode is a voice-call
 * stream: it plays on the loudspeaker and the microphone's echo canceller has it
 * as reference, so the visitor does not hear their own voice coming back.
 *
 * Whenever the mode is entered or has to be restored, the page is told
 * (window.campainhaAudioRouteChanged) to reopen the camera audio in that mode.
 * Chromium, the system or a device change may move the route; the listeners
 * put the loudspeaker back.
 */
object DoorbellAudio {
    private const val TAG = "DoorbellAudio"
    private const val JS_INTERFACE = "CampainhaAudio"
    private const val MAX_RESTORES = 4
    private const val RESTORE_WINDOW_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { handler.post(it) }
    private val restoreToken = Any()

    /** WebViews whose page currently needs doorbell audio. */
    private val activeWebViews = LinkedHashSet<WebView>()
    private var audioManager: AudioManager? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphone = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var commDeviceListener: Any? = null
    private var modeListener: Any? = null
    private val restoreTimes = ArrayDeque<Long>()

    private val engaged: Boolean
        get() = activeWebViews.isNotEmpty()

    /** Exposes window.CampainhaAudio to the page in [webView]. */
    fun install(activity: Activity, webView: WebView) {
        webView.addJavascriptInterface(Bridge(activity, webView), JS_INTERFACE)
    }

    /** The activity left the screen (background without PiP, PiP closed, destroyed). */
    fun release(activity: Activity, webView: WebView?) {
        if (webView != null) setActive(activity, webView, false)
    }

    private class Bridge(private val activity: Activity, private val webView: WebView) {
        @JavascriptInterface
        fun setActive(active: Boolean) {
            handler.post {
                // Only the doorbell page may take the audio (not a login page, for example).
                val host = runCatching { Uri.parse(webView.url).host }.getOrNull()
                if (active && !DoorbellConfig.isDoorbellHost(host)) return@post
                setActive(activity, webView, active)
            }
        }
    }

    private fun setActive(activity: Activity, webView: WebView, active: Boolean) {
        if (active) {
            if (!activeWebViews.add(webView)) return
            activity.volumeControlStream = AudioManager.STREAM_VOICE_CALL
            if (activeWebViews.size == 1) engage(activity) else notifyRouteChanged(webView)
        } else {
            if (!activeWebViews.remove(webView)) return
            activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
            if (!engaged) disengage()
        }
    }

    private fun engage(activity: Activity) {
        val am = activity.getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        savedMode = am.mode
        @Suppress("DEPRECATION")
        savedSpeakerphone = am.isSpeakerphoneOn
        restoreTimes.clear()
        registerListeners(am)
        applyMode()
        applySpeaker()
        Log.i(TAG, "engaged: mode=${am.mode} route=${routeName()}")
        activeWebViews.forEach { notifyRouteChanged(it) }
    }

    private fun disengage() {
        val am = audioManager ?: return
        unregisterListeners(am)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = savedSpeakerphone
            }
            am.mode = if (savedMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else savedMode
        }.onFailure { Log.w(TAG, "release failed", it) }
        Log.i(TAG, "released: mode=${am.mode}")
    }

    private fun applyMode() {
        val am = audioManager ?: return
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onFailure { Log.w(TAG, "setMode failed", it) }
        }
    }

    /** Loudspeaker as the only route. */
    private fun applySpeaker() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker == null || !am.setCommunicationDevice(speaker)) {
                        Log.w(TAG, "loudspeaker not accepted as communication device")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                @Suppress("DEPRECATION")
                if (!am.isSpeakerphoneOn) am.isSpeakerphoneOn = true
            }
        }.onFailure { Log.w(TAG, "speaker routing failed", it) }
    }

    /** Something moved the audio: put it back on the loudspeaker, and in call mode if it was dropped. */
    private fun restore(reason: String) {
        if (!engaged) return
        val am = audioManager ?: return
        applySpeaker()
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) return
        // The system can drop the mode (e.g. no audio for a while); do not fight it in a loop.
        val now = SystemClock.elapsedRealtime()
        while (restoreTimes.isNotEmpty() && now - restoreTimes.first() > RESTORE_WINDOW_MS) restoreTimes.removeFirst()
        if (restoreTimes.size >= MAX_RESTORES) {
            Log.w(TAG, "call mode keeps dropping ($reason); not forcing it again now")
            return
        }
        restoreTimes.addLast(now)
        applyMode()
        applySpeaker()
        Log.i(TAG, "restored after $reason: mode=${am.mode} route=${routeName()}")
        activeWebViews.forEach { notifyRouteChanged(it) }
    }

    private fun postRestore(reason: String, delayMs: Long) {
        handler.postAtTime({ restore(reason) }, restoreToken, SystemClock.uptimeMillis() + delayMs)
    }

    private fun notifyRouteChanged(webView: WebView) {
        webView.evaluateJavascript("window.campainhaAudioRouteChanged?.();", null)
    }

    private fun registerListeners(am: AudioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = postRestore("device added", 300)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = postRestore("device removed", 300)
        }
        am.registerAudioDeviceCallback(callback, handler)
        deviceCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerApi31Listeners(am)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31Listeners(am: AudioManager) {
        val deviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) postRestore("communication device changed", 0)
        }
        val modeChanged = AudioManager.OnModeChangedListener { mode ->
            if (mode != AudioManager.MODE_IN_COMMUNICATION) postRestore("mode changed to $mode", 200)
        }
        runCatching {
            am.addOnCommunicationDeviceChangedListener(mainExecutor, deviceListener)
            commDeviceListener = deviceListener
            am.addOnModeChangedListener(mainExecutor, modeChanged)
            modeListener = modeChanged
        }.onFailure { Log.w(TAG, "audio listeners failed", it) }
    }

    private fun unregisterListeners(am: AudioManager) {
        deviceCallback?.let { runCatching { am.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (commDeviceListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                runCatching { am.removeOnCommunicationDeviceChangedListener(it) }
            }
            (modeListener as? AudioManager.OnModeChangedListener)?.let {
                runCatching { am.removeOnModeChangedListener(it) }
            }
        }
        commDeviceListener = null
        modeListener = null
        handler.removeCallbacksAndMessages(restoreToken)
    }

    private fun routeName(): String {
        val am = audioManager ?: return "?"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.type?.toString() ?: "none"
        } else {
            @Suppress("DEPRECATION")
            "speakerphone=${am.isSpeakerphoneOn}"
        }
    }
}
