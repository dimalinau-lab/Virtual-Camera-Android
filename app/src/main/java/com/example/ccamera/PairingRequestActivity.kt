package com.example.ccamera

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

class PairingRequestActivity : Activity() {

    private var decisionSubmitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow display over lockscreen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val clientName = intent.getStringExtra("client_name")
            ?: PairingManager.activeClientName.ifBlank { "PC" }

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.pairing_title)
            .setMessage(getString(R.string.pairing_message, clientName))
            .setCancelable(false)
            .setPositiveButton(R.string.pairing_allow_always) { _, _ ->
                decisionSubmitted = true
                PairingManager.submitDecision(PairingDecision.ALLOW_ALWAYS)
                finish()
            }
            .setNeutralButton(R.string.pairing_allow_once) { _, _ ->
                decisionSubmitted = true
                PairingManager.submitDecision(PairingDecision.ALLOW_ONCE)
                finish()
            }
            .setNegativeButton(R.string.pairing_decline) { _, _ ->
                decisionSubmitted = true
                PairingManager.submitDecision(PairingDecision.REJECT)
                finish()
            }
            .setOnDismissListener {
                if (!decisionSubmitted) {
                    decisionSubmitted = true
                    PairingManager.submitDecision(PairingDecision.REJECT)
                }
            }

        val dialog = builder.create()
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!decisionSubmitted && !isChangingConfigurations) {
            decisionSubmitted = true
            PairingManager.submitDecision(PairingDecision.REJECT)
        }
    }
}
