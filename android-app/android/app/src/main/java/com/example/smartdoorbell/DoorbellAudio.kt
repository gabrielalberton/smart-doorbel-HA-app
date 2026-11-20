package com.example.smartdoorbell

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
 * put the loudspeaker back, within a budget so a tug-of-war cannot spin.
 *
 * Threads: WebView state lives on the main thread; every AudioManager call runs
 * on a dedicated audio thread, because those binder calls can block for seconds
 * on some phones and must never freeze the UI (ANR).
 */
object DoorbellAudio {
    private const val TAG = "DoorbellAudio"
    private const val JS_INTERFACE = "CampainhaAudio"
    private const val MAX_RESTORES = 6
    private const val RESTORE_WINDOW_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val audio: Handler by lazy {
        Handler(HandlerThread("DoorbellAudio").apply { start() }.looper)
    }
    private val audioExecutor = Executor { audio.post(it) }
    private val restoreToken = Any()

    // Main thread only.
    /** WebViews whose page currently needs doorbell audio. */
    private val activeWebViews = LinkedHashSet<WebView>()

    // Audio thread only.
    private var audioManager: AudioManager? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphone = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var commDeviceListener: Any? = null
    private var modeListener: Any? = null
    private val restoreTimes = ArrayDeque<Long>()

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
            main.post {
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
            if (activeWebViews.size == 1) {
                val context = activity.applicationContext
                audio.post { engage(context) }
            } else {
                notifyRouteChanged(webView)
            }
        } else {
            if (!activeWebViews.remove(webView)) return
            activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
            if (activeWebViews.isEmpty()) audio.post { disengage() }
        }
    }

    // ---- Audio thread ----

    private fun engage(context: Context) {
        if (audioManager != null) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            savedMode = am.mode
            @Suppress("DEPRECATION")
            savedSpeakerphone = am.isSpeakerphoneOn
        }
        audioManager = am
        restoreTimes.clear()
        registerListeners(am)
        applyMode()
        applySpeaker()
        Log.i(TAG, "engaged: mode=${am.mode} route=${routeName()}")
        main.post { notifyAllRouteChanged() }
    }

    private fun disengage() {
        val am = audioManager ?: return
        audioManager = null
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
        Log.i(TAG, "released")
    }

    private fun applyMode() {
        val am = audioManager ?: return
        runCatching {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
        }.onFailure { Log.w(TAG, "setMode failed", it) }
    }

    /** Loudspeaker as the only route. Returns true when it had to change something. */
    private fun applySpeaker(): Boolean {
        val am = audioManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return@runCatching false
                val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker == null || !am.setCommunicationDevice(speaker)) {
                    Log.w(TAG, "loudspeaker not accepted as communication device")
                }
                true
            } else {
                var changed = false
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    changed = true
                }
                @Suppress("DEPRECATION")
                if (!am.isSpeakerphoneOn) {
                    am.isSpeakerphoneOn = true
                    changed = true
                }
                changed
            }
        }.onFailure { Log.w(TAG, "speaker routing failed", it) }.getOrDefault(false)
    }

    /** Something moved the audio: put it back on the loudspeaker, in call mode, within the budget. */
    private fun restore(reason: String) {
        val am = audioManager ?: return
        val modeLost = runCatching { am.mode != AudioManager.MODE_IN_COMMUNICATION }.getOrDefault(false)
        val routeLost = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                @Suppress("DEPRECATION")
                !am.isSpeakerphoneOn || am.isBluetoothScoOn
            }
        }.getOrDefault(false)
        if (!modeLost && !routeLost) return
        // Something keeps taking the audio back (the system, Chromium, a device): do not fight it in a loop.
        val now = SystemClock.elapsedRealtime()
        while (restoreTimes.isNotEmpty() && now - restoreTimes.first() > RESTORE_WINDOW_MS) restoreTimes.removeFirst()
        if (restoreTimes.size >= MAX_RESTORES) {
            Log.w(TAG, "audio keeps moving ($reason); not forcing it again now")
            return
        }
        restoreTimes.addLast(now)
        if (modeLost) applyMode()
        applySpeaker()
        Log.i(TAG, "restored after $reason: mode=${am.mode} route=${routeName()}")
        if (modeLost) main.post { notifyAllRouteChanged() }
    }

    /** Coalesces bursts of events into one restore. */
    private fun postRestore(reason: String, delayMs: Long) {
        audio.removeCallbacksAndMessages(restoreToken)
        audio.postAtTime({ restore(reason) }, restoreToken, SystemClock.uptimeMillis() + delayMs)
    }

    private fun registerListeners(am: AudioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = postRestore("device added", 300)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = postRestore("device removed", 300)
        }
        runCatching {
            am.registerAudioDeviceCallback(callback, audio)
            deviceCallback = callback
        }.onFailure { Log.w(TAG, "device callback failed", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerApi31Listeners(am)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31Listeners(am: AudioManager) {
        val deviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) postRestore("communication device changed", 100)
        }
        val modeChanged = AudioManager.OnModeChangedListener { mode ->
            if (mode != AudioManager.MODE_IN_COMMUNICATION) postRestore("mode changed to $mode", 200)
        }
        runCatching {
            am.addOnCommunicationDeviceChangedListener(audioExecutor, deviceListener)
            commDeviceListener = deviceListener
            am.addOnModeChangedListener(audioExecutor, modeChanged)
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
        audio.removeCallbacksAndMessages(restoreToken)
    }

    private fun routeName(): String {
        val am = audioManager ?: return "?"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.communicationDevice?.type?.toString() ?: "none"
            } else {
                @Suppress("DEPRECATION")
                "speakerphone=${am.isSpeakerphoneOn}"
            }
        }.getOrDefault("?")
    }

    // ---- Main thread ----

    private fun notifyAllRouteChanged() {
        activeWebViews.forEach { notifyRouteChanged(it) }
    }

    private fun notifyRouteChanged(webView: WebView) {
        runCatching { webView.evaluateJavascript("window.campainhaAudioRouteChanged?.();", null) }
    }
}
