package com.example.ccamera

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ControlServer(
    port: Int = 8080,
    private val context: Context,
    private val callback: ControlCallback
) : NanoHTTPD("0.0.0.0", port) {

    interface ControlCallback {
        fun onConnectRequested(mode: String) // "usb" или "wifi"
        fun onDisconnectRequested()
        fun onActionRequested(action: String) // "switch_camera", "toggle_torch", "toggle_blackout", etc.
        fun onOrientationRequested(mode: String) // "vertical" или "horizontal"
        fun onConfigUpdated(resolution: String, fps: Int, bitrate: Int)
        fun getStatus(): StatusInfo
        fun onRequestUserPairing(clientId: String, clientName: String, onDecision: (Boolean) -> Unit)
    }

    data class StatusInfo(
        val isStreaming: Boolean,
        val cameraFacing: String,
        val isTorchOn: Boolean,
        val orientation: String = "vertical",
        val isMicMuted: Boolean = false
    )

    companion object {
        private const val TAG = "ControlServer"
    }

    private var lastConfigTime = 0L

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.i(TAG, "HTTP запрос: $method $uri")

        try {
            val parms = session.parameters
            val queryMode = parms["mode"]?.firstOrNull()

            var jsonObj: JSONObject? = null
            var postData = ""
            if (method == Method.POST) {
                val map = HashMap<String, String>()
                try {
                    session.parseBody(map)
                } catch (_: IOException) {
                } catch (_: ResponseException) {
                }

                postData = map["postData"] ?: ""
                if (postData.isNotBlank()) {
                    try {
                        jsonObj = JSONObject(postData)
                    } catch (_: Exception) {}
                }
            }

            when (uri) {
                "/api/pair" -> {
                    val clientId = jsonObj?.optString("client_id") ?: parms["client_id"]?.firstOrNull() ?: ""
                    val clientName = jsonObj?.optString("client_name", "PC") ?: parms["client_name"]?.firstOrNull() ?: "PC"

                    if (clientId.isBlank()) {
                        return newJsonResponse(Response.Status.BAD_REQUEST, "{\"status\":\"error\",\"message\":\"Missing client_id\"}")
                    }

                    val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)

                    if (prefs.contains(clientId)) {
                        val token = prefs.getString(clientId, "") ?: ""
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"paired\",\"auth_token\":\"$token\"}"
                        )
                    }

                    val decisionPromise = CompletableFuture<Boolean>()

                    callback.onRequestUserPairing(clientId, clientName) { allowed ->
                        decisionPromise.complete(allowed)
                    }

                    return try {
                        val allowed = decisionPromise.get(20, TimeUnit.SECONDS)
                        if (allowed) {
                            val newToken = UUID.randomUUID().toString()
                            prefs.edit().putString(clientId, newToken).apply()
                            newJsonResponse(
                                Response.Status.OK,
                                "{\"status\":\"paired\",\"auth_token\":\"$newToken\"}"
                            )
                        } else {
                            newJsonResponse(
                                Response.Status.UNAUTHORIZED,
                                "{\"status\":\"rejected\",\"message\":\"User rejected pairing\"}"
                            )
                        }
                    } catch (_: Exception) {
                        newJsonResponse(
                            Response.Status.REQUEST_TIMEOUT,
                            "{\"status\":\"timeout\",\"message\":\"Pairing timed out\"}"
                        )
                    }
                }
                "/api/connect" -> {
                    val mode = jsonObj?.optString("mode", "usb") ?: queryMode ?: "usb"
                    val clientToken = jsonObj?.optString("auth_token", "") ?: parms["auth_token"]?.firstOrNull() ?: ""

                    if (mode != "usb") {
                        val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
                        val allTokens = prefs.all.values
                        val isAuthorized = clientToken.isNotBlank() && allTokens.contains(clientToken)

                        if (!isAuthorized) {
                            Log.w("ROUTING_DEBUG", "Отказ подключения /api/connect: устройства нет в сопряженных или токен недействителен")
                            return newJsonResponse(
                                Response.Status.UNAUTHORIZED,
                                "{\"status\":\"unauthorized\",\"message\":\"Device not paired or pairing revoked\"}"
                            )
                        }
                    }

                    callback.onConnectRequested(mode)
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"connected\"}")
                }
                "/api/unpair" -> {
                    val tokenToRemove = jsonObj?.optString("token", "") ?: parms["token"]?.firstOrNull() ?: ""
                    val clientIdToRemove = jsonObj?.optString("client_id", "") ?: parms["client_id"]?.firstOrNull() ?: ""

                    val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
                    val editor = prefs.edit()

                    if (clientIdToRemove.isNotBlank()) {
                        editor.remove(clientIdToRemove)
                    }

                    if (tokenToRemove.isNotBlank()) {
                        for ((key, value) in prefs.all) {
                            if (value == tokenToRemove) {
                                editor.remove(key)
                            }
                        }
                    }

                    editor.apply()
                    Log.i("ROUTING_DEBUG", "Сопряжение успешно отозвано (/api/unpair)")
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"unpaired\"}")
                }
                "/api/disconnect" -> {
                    callback.onDisconnectRequested()
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\",\"message\":\"Disconnected\"}")
                }
                "/api/orientation" -> {
                    val mode = queryMode ?: jsonObj?.optString("mode", "vertical") ?: "vertical"
                    callback.onOrientationRequested(mode.lowercase())
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        "{\"status\":\"ok\",\"mode\":\"${mode.lowercase()}\"}"
                    )
                }
                "/api/config" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastConfigTime < 300) {
                        Log.w("ROUTING_DEBUG", "Пропуск дублирующего запроса config (дребезг ${now - lastConfigTime}мс)")
                        return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\",\"message\":\"debounced\"}")
                    }
                    lastConfigTime = now

                    val resolution = jsonObj?.optString("resolution", "1080p") ?: parms["resolution"]?.firstOrNull() ?: "1080p"
                    val fps = jsonObj?.optInt("fps", 30) ?: parms["fps"]?.firstOrNull()?.toIntOrNull() ?: 30
                    val bitrate = jsonObj?.optInt("bitrate", 7_000_000) ?: parms["bitrate"]?.firstOrNull()?.toIntOrNull() ?: 7_000_000
                    Log.i("ROUTING_DEBUG", "ControlServer: /api/config -> resolution=$resolution, fps=$fps, bitrate=$bitrate")
                    callback.onConfigUpdated(resolution, fps, bitrate)
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\"}")
                }
                "/api/action" -> {
                    var action = jsonObj?.optString("action", "") ?: parms["action"]?.firstOrNull() ?: ""
                    if (action.isBlank() && postData.isNotBlank()) {
                        if (postData.contains("toggle_blackout")) action = "toggle_blackout"
                        else if (postData.contains("switch_camera")) action = "switch_camera"
                        else if (postData.contains("toggle_torch")) action = "toggle_torch"
                        else if (postData.contains("toggle_mic_mute")) action = "toggle_mic_mute"
                    }
                    if (action.isNotBlank()) {
                        callback.onActionRequested(action)
                    }
                    if (action == "toggle_mic_mute") {
                        val status = callback.getStatus()
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"ok\",\"action\":\"$action\",\"mic_muted\":${status.isMicMuted}}"
                        )
                    }
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\",\"action\":\"$action\"}")
                }
                "/api/status" -> {
                    val status = callback.getStatus()
                    val responseJson = JSONObject().apply {
                        put("status", "ready")
                        put("streaming", status.isStreaming)
                        put("camera", status.cameraFacing)
                        put("torch", status.isTorchOn)
                        put("orientation", status.orientation)
                        put("mic_muted", status.isMicMuted)
                    }.toString()
                    return newJsonResponse(Response.Status.OK, responseJson)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки HTTP запроса", e)
            return newJsonResponse(Response.Status.INTERNAL_ERROR, "{\"error\":\"${e.message}\"}")
        }

        return newJsonResponse(Response.Status.NOT_FOUND, "{\"error\":\"not_found\"}")
    }

    private fun newJsonResponse(status: Response.Status, json: String): Response {
        val resp = newFixedLengthResponse(status, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return resp
    }
}
