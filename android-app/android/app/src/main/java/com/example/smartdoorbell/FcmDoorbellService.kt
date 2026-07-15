package com.example.smartdoorbell

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmDoorbellService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val isDoorbell = data["type"] == "doorbell_ring" || data["event"] == "ring" || data["ring"] == "true"
        if (isDoorbell || message.notification != null) {
            DoorbellNotifier.showIncomingCall(
                this,
                data["title"] ?: message.notification?.title ?: BuildConfig.DOORBELL_TITLE,
                data["body"] ?: message.notification?.body ?: "Alguém tocou a campainha",
                data["openUrl"] ?: DoorbellConfig.DEFAULT_ATTEND_URL
            )
        }
    }

    override fun onNewToken(token: String) {
        android.util.Log.i("FcmDoorbellService", "Novo token FCM da campainha recebido; cadastre este token no backend/HA se usar push FCM.")
    }
}
