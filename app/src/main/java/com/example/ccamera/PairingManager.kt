package com.example.ccamera

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

enum class PairingDecision {
    ALLOW_ALWAYS,
    ALLOW_ONCE,
    REJECT
}

object PairingManager {
    private const val TAG = "PairingManager"
    const val PAIRING_NOTIFICATION_ID = 9002

    @Volatile
    var activeCallback: ((PairingDecision) -> Unit)? = null
    @Volatile
    var activeClientId: String = ""
    @Volatile
    var activeClientName: String = ""
    @Volatile
    var appContext: Context? = null

    // Ephemeral in-memory tokens for ALLOW_ONCE: token -> expiration timestamp (ms)
    val ephemeralTokens = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun registerPendingRequest(
        context: Context,
        clientId: String,
        clientName: String,
        callback: (PairingDecision) -> Unit
    ) {
        appContext = context.applicationContext
        // Fail-closed: cancel previous request if any
        val prev = activeCallback
        activeCallback = null
        prev?.invoke(PairingDecision.REJECT)

        activeClientId = clientId
        activeClientName = clientName
        activeCallback = callback
    }

    @Synchronized
    fun submitDecision(decision: PairingDecision) {
        Log.i(TAG, "Pairing decision submitted: $decision for client=$activeClientId ($activeClientName)")

        // Cancel notification if active
        appContext?.let { ctx ->
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(PAIRING_NOTIFICATION_ID)
        }

        val cb = activeCallback
        activeCallback = null
        cb?.invoke(decision)
    }

    fun isEphemeralTokenValid(token: String): Boolean {
        if (token.isBlank()) return false
        val expiry = ephemeralTokens[token] ?: return false
        if (System.currentTimeMillis() > expiry) {
            ephemeralTokens.remove(token)
            return false
        }
        return true
    }

    fun addEphemeralToken(token: String, durationMs: Long = 24 * 3600 * 1000L) {
        ephemeralTokens[token] = System.currentTimeMillis() + durationMs
    }

    fun revokeToken(context: Context, token: String) {
        ephemeralTokens.remove(token)
        val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var found = false
        for ((key, value) in prefs.all) {
            if (value == token) {
                editor.remove(key)
                found = true
            }
        }
        if (found) {
            editor.apply()
        }
    }
}
