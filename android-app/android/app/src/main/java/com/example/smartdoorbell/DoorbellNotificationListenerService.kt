package com.example.smartdoorbell

import android.app.Notification
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class DoorbellNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (!isHomeAssistantPackage(sbn.packageName)) return

            val notification = sbn.notification ?: return
            val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId else ""
            val isDedicatedTrigger = sbn.tag == TRIGGER_TAG || channelId == TRIGGER_CHANNEL
            if (!isDedicatedTrigger) return

            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val isDoorbell = title.contains(BuildConfig.DOORBELL_TITLE, ignoreCase = true)
            if (!isDoorbell || !acquireTriggerWindow()) return

            // O HA é apenas transporte. Remova o gatilho visual assim que o APK o consumir.
            runCatching { cancelNotification(sbn.key) }

            Log.i(TAG, "Gatilho HA consumido; abrindo chamada nativa da campainha")
            DoorbellNotifier.showIncomingCall(
                this,
                title.ifBlank { BuildConfig.DOORBELL_TITLE },
                text.ifBlank { "Alguém tocou a campainha" },
                DoorbellConfig.DEFAULT_ATTEND_URL
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao processar gatilho da campainha", e)
        }
    }

    private fun isHomeAssistantPackage(packageName: String): Boolean {
        return packageName.startsWith(BuildConfig.HA_PACKAGE_PREFIX)
    }

    private fun acquireTriggerWindow(): Boolean = synchronized(TRIGGER_LOCK) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerElapsedMs < MIN_TRIGGER_INTERVAL_MS) return@synchronized false
        lastTriggerElapsedMs = now
        true
    }

    companion object {
        private const val TAG = "DoorbellNotificationListener"
        private val TRIGGER_TAG = BuildConfig.TRIGGER_TAG
        private val TRIGGER_CHANNEL = BuildConfig.TRIGGER_CHANNEL
        private const val MIN_TRIGGER_INTERVAL_MS = 5_000L
        private val TRIGGER_LOCK = Any()
        private var lastTriggerElapsedMs = 0L
    }
}
