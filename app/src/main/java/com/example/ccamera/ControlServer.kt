package com.example.ccamera

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException

class ControlServer(
    port: Int = 8080,
    private val callback: ControlCallback
) : NanoHTTPD("0.0.0.0", port) {

    interface ControlCallback {
        fun onConnectRequested(mode: String) // "usb" или "wifi"
        fun onDisconnectRequested()
        fun onActionRequested(action: String) // "switch_camera", "toggle_torch", "toggle_blackout", etc.
        fun onOrientationRequested(mode: String) // "vertical" или "horizontal"
        fun onConfigUpdated(resolution: String, fps: Int, bitrate: Int)
        fun getStatus(): StatusInfo
    }

    data class StatusInfo(
        val isStreaming: Boolean,
        val cameraFacing: String,
        val isTorchOn: Boolean,
        val orientation: String = "vertical"
    )

    companion object {
        private const val TAG = "ControlServer"
    }

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
                "/api/connect" -> {
                    val mode = jsonObj?.optString("mode", "usb") ?: queryMode ?: "usb"
                    callback.onConnectRequested(mode)
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\",\"message\":\"Connected\"}")
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
                    val resolution = jsonObj?.optString("resolution", "1080p") ?: parms["resolution"]?.firstOrNull() ?: "1080p"
                    val fps = jsonObj?.optInt("fps", 30) ?: parms["fps"]?.firstOrNull()?.toIntOrNull() ?: 30
                    val bitrate = jsonObj?.optInt("bitrate", 7_000_000) ?: parms["bitrate"]?.firstOrNull()?.toIntOrNull() ?: 7_000_000
                    callback.onConfigUpdated(resolution, fps, bitrate)
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\"}")
                }
                "/api/action" -> {
                    var action = jsonObj?.optString("action", "") ?: parms["action"]?.firstOrNull() ?: ""
                    if (action.isBlank() && postData.isNotBlank()) {
                        if (postData.contains("toggle_blackout")) action = "toggle_blackout"
                        else if (postData.contains("switch_camera")) action = "switch_camera"
                        else if (postData.contains("toggle_torch")) action = "toggle_torch"
                    }
                    if (action.isNotBlank()) {
                        callback.onActionRequested(action)
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
