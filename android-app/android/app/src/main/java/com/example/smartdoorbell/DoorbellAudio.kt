package com.example.smartdoorbell

import android.app.Activity
import android.content.Context
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
 * a wired headset or the earpiece.
 *
 * While the page has sound on or the microphone open it calls [Bridge.setActive],
 * and the phone is held in MODE_IN_COMMUNICATION with the loudspeaker as the
 * communication device. Chromium picks the output stream type from the current
 * audio mode, so the camera audio opened after this is a voice-call stream:
 * it follows the communication device (loudspeaker), and the platform echo
 * canceller of the microphone (getUserMedia echoCancellation) has it as reference,
 * so the visitor does not hear their own voice coming back.
 *
 * Chromium also switches the audio mode and device itself when the microphone
 * opens and closes; the listeners below put the loudspeaker back after any change.
 * After entering the mode the page is told to reopen the camera audio
 * (window.campainhaAudioRouteChanged) so the stream is recreated as a call stream.
 */
object DoorbellAudio {
    private const val TAG = "DoorbellAudio"
    const val JS_INTERFACE = "CampainhaAudio"
    private const val MAX_REASSERTS = 4
    private const val REASSERT_WINDOW_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { handler.post(it) }

    /** Owners (one WebView each) that currently need doorbell audio, with their WebView. */
    private val owners = LinkedHashMap<Any, WebView>()
    private var audioManager: AudioManager? = null
    private var engaged = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphone = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var commDeviceListener: Any? = null
    private var modeListener: Any? = null
    private val reassertTimes = ArrayDeque<Long>()
    private val reassertToken = Any()

    /** Adds the JS bridge to [webView]; [activity] gets its volume keys bound to the call volume while active. */
    fun install(activity: Activity, webView: WebView) {
        webView.addJavascriptInterface(Bridge(activity, webView), JS_INTERFACE)
    }

    /** Lifecycle exit (background without PiP, PiP closed, destroy): stop routing for this owner. */
    fun release(activity: Activity, webView: WebView?) {
        webView ?: return
        setActive(activity, webView, false)
    }

    private class Bridge(private val activity: Activity, private val webView: WebView) {
        @JavascriptInterface
        fun setActive(active: Boolean) {
            handler.post {
                // Only the doorbell page may drive the audio mode (not the login page, for example).
                val host = runCatching { Uri.parse(webView.url).host }.getOrNull()
                if (active && !DoorbellConfig.isDoorbellHost(host)) return@post
                setActive(activity, webView, active)
            }
        }

        @JavascriptInterface
        fun isAvailable(): Boolean = true
    }

    private fun setActive(activity: Activity, webView: WebView, active: Boolean) {
        if (active) {
            // The page calls this only when its sound or microphone state changes; each call
            // ends with the page reopening the camera audio in the call mode set here.
            val wasEmpty = owners.isEmpty()
            owners[webView] = webView
            activity.volumeControlStream = AudioManager.STREAM_VOICE_CALL
            if (wasEmpty) engage(activity.applicationContext)
            else {
                applyMode()
                applySpeaker()
            }
            notifyRouteChanged(webView)
        } else {
            if (owners.remove(webView) == null) return
            activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
            if (owners.isEmpty()) disengage()
        }
    }

    private fun engage(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        if (!engaged) {
            savedMode = am.mode
            @Suppress("DEPRECATION")
            savedSpeakerphone = am.isSpeakerphoneOn
            engaged = true
            registerListeners(am)
        }
        reassertTimes.clear()
        applyMode()
        applySpeaker()
        Log.i(TAG, "engaged: mode=${am.mode} device=${currentDeviceName()}")
    }

    private fun disengage() {
        val am = audioManager ?: return
        if (!engaged) return
        engaged = false
        unregisterListeners(am)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = savedSpeakerphone
            }
            am.mode = if (savedMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else savedMode
        }.onFailure { Log.w(TAG, "disengage failed", it) }
        Log.i(TAG, "released: mode=${am.mode}")
    }

    private fun applyMode() {
        val am = audioManager ?: return
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onFailure { Log.w(TAG, "setMode failed", it) }
        }
    }

    /** Loudspeaker as the only route; never Bluetooth SCO/LE, wired headset or earpiece. */
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

    /** Something (Chromium, a headset, the system) moved the audio: put it back on the loudspeaker. */
    private fun reassert(reason: String) {
        if (!engaged) return
        val am = audioManager ?: return
        val modeLost = am.mode != AudioManager.MODE_IN_COMMUNICATION
        applySpeaker()
        if (!modeLost) return
        // The mode can be dropped by the system (e.g. no audio for a while); avoid a fight loop.
        val now = SystemClock.elapsedRealtime()
        while (reassertTimes.isNotEmpty() && now - reassertTimes.first() > REASSERT_WINDOW_MS) reassertTimes.removeFirst()
        if (reassertTimes.size >= MAX_REASSERTS) {
            Log.w(TAG, "mode keeps dropping ($reason); not forcing it again now")
            return
        }
        reassertTimes.addLast(now)
        applyMode()
        applySpeaker()
        Log.i(TAG, "reasserted after $reason: mode=${am.mode} device=${currentDeviceName()}")
        owners.values.forEach { notifyRouteChanged(it) }
    }

    private fun postReassert(reason: String, delayMs: Long) {
        handler.postAtTime({ reassert(reason) }, reassertToken, SystemClock.uptimeMillis() + delayMs)
    }

    private fun notifyRouteChanged(webView: WebView) {
        webView.evaluateJavascript("window.campainhaAudioRouteChanged?.();", null)
    }

    private fun registerListeners(am: AudioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                postReassert("device added", 300)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                postReassert("device removed", 300)
            }
        }
        am.registerAudioDeviceCallback(callback, handler)
        deviceCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerApi31Listeners(am)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31Listeners(am: AudioManager) {
        val deviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) postReassert("communication device changed", 0)
        }
        val modeChanged = AudioManager.OnModeChangedListener { mode ->
            if (mode != AudioManager.MODE_IN_COMMUNICATION) postReassert("mode changed to $mode", 200)
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
        handler.removeCallbacksAndMessages(reassertToken)
    }

    private fun currentDeviceName(): String {
        val am = audioManager ?: return "?"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.type?.toString() ?: "none"
        } else {
            @Suppress("DEPRECATION")
            "speakerphone=${am.isSpeakerphoneOn}"
        }
    }
}
