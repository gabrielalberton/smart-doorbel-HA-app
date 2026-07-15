package com.example.smartdoorbell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DoorbellActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DoorbellConfig.initialize(context)) return
        when (intent.action) {
            DoorbellConfig.ACTION_TEST_RING -> DoorbellNotifier.showIncomingCall(
                context,
                intent.getStringExtra(DoorbellConfig.EXTRA_TITLE) ?: DoorbellConfig.DOORBELL_TITLE,
                intent.getStringExtra(DoorbellConfig.EXTRA_BODY) ?: "Teste de chamada da campainha",
                intent.getStringExtra(DoorbellConfig.EXTRA_OPEN_URL) ?: DoorbellConfig.DEFAULT_ATTEND_URL
            )
            DoorbellConfig.ACTION_ANSWER -> {
                DoorbellNotifier.cancelIncomingCall(context)
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(DoorbellConfig.EXTRA_OPEN_URL, intent.getStringExtra(DoorbellConfig.EXTRA_OPEN_URL) ?: DoorbellConfig.DEFAULT_ATTEND_URL)
                }
                context.startActivity(open)
            }
            DoorbellConfig.ACTION_DECLINE -> DoorbellNotifier.cancelIncomingCall(context)
        }
    }
}
