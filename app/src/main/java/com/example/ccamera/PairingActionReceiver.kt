package com.example.ccamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PairingActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ALLOW_ALWAYS = "com.example.ccamera.PAIRING_ALLOW_ALWAYS"
        const val ACTION_ALLOW_ONCE = "com.example.ccamera.PAIRING_ALLOW_ONCE"
        const val ACTION_DECLINE = "com.example.ccamera.PAIRING_DECLINE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("PairingActionReceiver", "Received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_ALLOW_ALWAYS -> PairingManager.submitDecision(PairingDecision.ALLOW_ALWAYS)
            ACTION_ALLOW_ONCE -> PairingManager.submitDecision(PairingDecision.ALLOW_ONCE)
            ACTION_DECLINE -> PairingManager.submitDecision(PairingDecision.REJECT)
        }
    }
}
