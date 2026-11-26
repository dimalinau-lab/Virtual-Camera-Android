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
        fun onActionRequested(action: String, params: JSONObject = JSONObject()) // "switch_camera", "set_lens", "set_zoom", "toggle_torch", etc.
        fun onOrientationRequested(mode: String) // "vertical" или "horizontal"
        fun onConfigUpdated(resolution: String, fps: Int, bitrate: Int)
        fun getStatus(): StatusInfo
        fun getDiagnosticsJson(): String = "{}"
        fun onRequestUserPairing(clientId: String, clientName: String, onDecision: (PairingDecision) -> Unit)
    }

    data class StatusInfo(
        val isStreaming: Boolean,
        val cameraFacing: String,
        val isTorchOn: Boolean,
        val orientation: String = "vertical",
        val orient: Int = 90,
        val isMicMuted: Boolean = false,
        val currentLens: String = "1x",
        val currentZoom: Float = 1.0f,
        val minZoom: Float = 1.0f,
        val maxZoom: Float = 8.0f,
        val batteryLevel: Int = -1,
        val batteryTemp: Float = -1.0f,
        val isCharging: Boolean = false,
        val thermalStatus: String = "normal",
        val isManualExposure: Boolean = false,
        val iso: Int = 400,
        val exposureTimeNs: Long = 16_666_667L,
        val minIso: Int = 100,
        val maxIso: Int = 3200,
        val minExposureNs: Long = 100_000L,
        val maxExposureNs: Long = 100_000_000L,
        val isManualFocus: Boolean = false,
        val focusDistance: Float = 0.0f,
        val maxFocusDistance: Float = 10.0f,
        val awbMode: String = "auto",
        val maxFps: Int = 60,
        val supportedFps: List<Int> = listOf(30, 60),
        val currentFps: Int = 60,
        val currentFpsRange: String = "[60, 60]",
        val width: Int = 1920,
        val height: Int = 1080,
        val resolution: String = "1920x1080",
        val isAutoFraming: Boolean = false,
        val pipelineMode: String = "gl",
        val availableLenses: List<String> = listOf("1x"),
        val isStabilization: Boolean = false,
        val hasOis: Boolean = false,
        val hasEis: Boolean = false,
        val currentCameraUnit: String = "0",
        val cameraUnits: List<CameraUnitJson> = emptyList()
    )

    data class CameraUnitJson(
        val id: String,
        val name: String,
        val type: String,
        val facing: String,
        val focalLengthMm: Float,
        val hasOis: Boolean,
        val isPhysical: Boolean
    )

    companion object {
        private const val TAG = "ControlServer"
    }

    @Volatile
    private var lastConfigTime = 0L
    @Volatile
    private var lastConfigSignature: String = ""

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isLoopbackIp(ip: String): Boolean {
        return ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1"
    }

    private fun isRequestAuthorized(
        session: IHTTPSession,
        jsonObj: JSONObject?,
        parms: Map<String, List<String>>
    ): Boolean {
        val remoteIp = session.remoteIpAddress ?: ""
        // Локальный USB-туннель (adb forward) доверен, но отклоняем браузерные cross-origin CSRF-запросы
        if (isLoopbackIp(remoteIp)) {
            val origin = session.headers["origin"]
            val referer = session.headers["referer"]
            if (origin.isNullOrBlank() && referer.isNullOrBlank()) {
                return true
            }
        }
        val headerToken = session.headers["x-auth-token"] ?: ""
        val token = headerToken.ifBlank {
            jsonObj?.optString("auth_token", "")
                ?.ifBlank { parms["auth_token"]?.firstOrNull() ?: "" }
                ?: (parms["auth_token"]?.firstOrNull() ?: "")
        }
        if (token.isBlank()) return false

        val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
        if (prefs.all.values.contains(token)) return true
        return PairingManager.isEphemeralTokenValid(token)
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

            if (uri != "/api/pair" && uri != "/api/connect" && uri != "/api/unpair") {
                if (!isRequestAuthorized(session, jsonObj, parms)) {
                    Log.w(TAG, "Неавторизованный запрос отклонен: $uri от ${session.remoteIpAddress}")
                    return newJsonResponse(
                        Response.Status.UNAUTHORIZED,
                        "{\"status\":\"unauthorized\",\"error\":\"Valid auth_token required\"}"
                    )
                }
            }

            when (uri) {
                "/api/pair" -> {
                    val clientId = jsonObj?.optString("client_id", "")?.ifBlank { null }
                        ?: parms["client_id"]?.firstOrNull()?.ifBlank { null }
                        ?: ""
                    val clientName = jsonObj?.optString("client_name", "PC")?.ifBlank { "PC" }
                        ?: parms["client_name"]?.firstOrNull()?.ifBlank { "PC" }
                        ?: "PC"

                    if (clientId.isBlank()) {
                        return newJsonResponse(Response.Status.BAD_REQUEST, "{\"status\":\"error\",\"message\":\"Missing client_id\"}")
                    }

                    val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
                    // Не раскрываем старый токен без явного подтверждения пользователя (защита от кражи токена по client_id)
                    val decisionPromise = CompletableFuture<PairingDecision>()

                    callback.onRequestUserPairing(clientId, clientName) { decision ->
                        decisionPromise.complete(decision)
                    }

                    return try {
                        val decision = decisionPromise.get(25, TimeUnit.SECONDS)
                        when (decision) {
                            PairingDecision.ALLOW_ALWAYS -> {
                                val newToken = UUID.randomUUID().toString()
                                prefs.edit().putString(clientId, newToken).apply()
                                newJsonResponse(
                                    Response.Status.OK,
                                    "{\"status\":\"paired\",\"auth_token\":\"$newToken\",\"type\":\"always\"}"
                                )
                            }
                            PairingDecision.ALLOW_ONCE -> {
                                val newToken = UUID.randomUUID().toString()
                                PairingManager.addEphemeralToken(newToken)
                                newJsonResponse(
                                    Response.Status.OK,
                                    "{\"status\":\"paired\",\"auth_token\":\"$newToken\",\"type\":\"once\"}"
                                )
                            }
                            PairingDecision.REJECT -> {
                                newJsonResponse(
                                    Response.Status.UNAUTHORIZED,
                                    "{\"status\":\"rejected\",\"message\":\"User rejected pairing\"}"
                                )
                            }
                        }
                    } catch (_: Exception) {
                        newJsonResponse(
                            Response.Status.REQUEST_TIMEOUT,
                            "{\"status\":\"timeout\",\"message\":\"Pairing timed out\"}"
                        )
                    }
                }
                "/api/connect" -> {
                    val remoteIp = session.remoteIpAddress ?: ""
                    val isUsbLoopback = isLoopbackIp(remoteIp)
                    val mode = if (isUsbLoopback) (jsonObj?.optString("mode", "usb") ?: queryMode ?: "usb") else "wifi"
                    val clientToken = (session.headers["x-auth-token"] ?: "").ifBlank {
                        jsonObj?.optString("auth_token", "")?.ifBlank { parms["auth_token"]?.firstOrNull() ?: "" }
                            ?: (parms["auth_token"]?.firstOrNull() ?: "")
                    }

                    val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
                    val allTokens = prefs.all.values
                    val isPersistentAuthorized = clientToken.isNotBlank() && allTokens.contains(clientToken)
                    val isEphemeralAuthorized = clientToken.isNotBlank() && PairingManager.isEphemeralTokenValid(clientToken)
                    var isAuthorized = isPersistentAuthorized || isEphemeralAuthorized || isUsbLoopback
                    var issuedToken = clientToken

                    if (!isAuthorized) {
                        val pcMac = jsonObj?.optString("pc_mac", "") ?: ""
                        val clientId = jsonObj?.optString("client_id", "")?.ifBlank { null }
                            ?: ("pc-" + (pcMac.ifBlank { remoteIp.ifBlank { "unknown" } }))
                        val clientName = jsonObj?.optString("client_name", "")?.ifBlank { null }
                            ?: remoteIp.ifBlank { "PC" }
                        val decisionPromise = CompletableFuture<PairingDecision>()

                        callback.onRequestUserPairing(clientId, clientName) { decision ->
                            decisionPromise.complete(decision)
                        }

                        try {
                            val decision = decisionPromise.get(25, TimeUnit.SECONDS)
                            when (decision) {
                                PairingDecision.ALLOW_ALWAYS -> {
                                    val newToken = UUID.randomUUID().toString()
                                    prefs.edit().putString(clientId, newToken).apply()
                                    issuedToken = newToken
                                    isAuthorized = true
                                }
                                PairingDecision.ALLOW_ONCE -> {
                                    val newToken = UUID.randomUUID().toString()
                                    PairingManager.addEphemeralToken(newToken)
                                    issuedToken = newToken
                                    isAuthorized = true
                                }
                                PairingDecision.REJECT -> {
                                    isAuthorized = false
                                }
                            }
                        } catch (_: Exception) {
                            isAuthorized = false
                        }
                    }

                    if (!isAuthorized) {
                        Log.w("ROUTING_DEBUG", "Отказ подключения /api/connect: устройства нет в сопряженных или токен недействителен")
                        return newJsonResponse(
                            Response.Status.UNAUTHORIZED,
                            "{\"status\":\"unauthorized\",\"message\":\"Device not paired or pairing revoked\"}"
                        )
                    }

                    val pcMac = jsonObj?.optString("pc_mac", "") ?: ""
                    val pcIp = jsonObj?.optString("pc_ip", "")?.ifBlank { null } ?: remoteIp
                    PcWakeManager.savePcInfo(context, mac = pcMac, ip = pcIp, mode = mode)

                    callback.onConnectRequested(mode)
                    val respJson = if (issuedToken.isNotBlank()) {
                        "{\"status\":\"connected\",\"auth_token\":\"${escapeJson(issuedToken)}\"}"
                    } else {
                        "{\"status\":\"connected\"}"
                    }
                    return newJsonResponse(Response.Status.OK, respJson)
                }
                "/api/unpair" -> {
                    val tokenToRemove = jsonObj?.optString("token", "") ?: parms["token"]?.firstOrNull() ?: ""
                    val clientIdToRemove = jsonObj?.optString("client_id", "") ?: parms["client_id"]?.firstOrNull() ?: ""
                    val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
                    val hasValidToken = tokenToRemove.isNotBlank() &&
                        (prefs.all.values.contains(tokenToRemove) || PairingManager.isEphemeralTokenValid(tokenToRemove))
                    if (!isRequestAuthorized(session, jsonObj, parms) && !hasValidToken) {
                        return newJsonResponse(
                            Response.Status.UNAUTHORIZED,
                            "{\"status\":\"unauthorized\",\"error\":\"Valid auth_token required\"}"
                        )
                    }

                    if (tokenToRemove.isNotBlank()) {
                        PairingManager.revokeToken(context, tokenToRemove)
                    }
                    if (clientIdToRemove.isNotBlank()) {
                        val prefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
                        prefs.edit().remove(clientIdToRemove).apply()
                    }

                    // Вариант 3-А: Очищаем также сохраненные данные ПК в PcWakeManager, чтобы смартфон не будил забытый ПК
                    PcWakeManager.clearPcInfo(context)

                    Log.i("ROUTING_DEBUG", "Сопряжение успешно отозвано (/api/unpair), кэш ПК сброшен")
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"unpaired\"}")
                }
                "/api/disconnect" -> {
                    callback.onDisconnectRequested()
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\",\"message\":\"Disconnected\"}")
                }
                "/api/orientation" -> {
                    val mode = (queryMode ?: jsonObj?.optString("mode", "vertical") ?: "vertical").lowercase()
                    callback.onOrientationRequested(mode)
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"mode\":\"${escapeJson(mode)}\"}"
                    )
                }
                "/api/config" -> {
                    val resolution = jsonObj?.optString("resolution", "1080p") ?: parms["resolution"]?.firstOrNull() ?: "1080p"
                    val fps = jsonObj?.optInt("fps", 30) ?: parms["fps"]?.firstOrNull()?.toIntOrNull() ?: 30
                    val bitrate = jsonObj?.optInt("bitrate", 7_000_000) ?: parms["bitrate"]?.firstOrNull()?.toIntOrNull() ?: 7_000_000
                    val sig = "$resolution:$fps:$bitrate"
                    val now = System.currentTimeMillis()
                    if (sig == lastConfigSignature && now - lastConfigTime < 150) {
                        Log.w("ROUTING_DEBUG", "Пропуск идентичного запроса config ($sig, ${now - lastConfigTime}мс)")
                        return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\",\"message\":\"debounced\"}")
                    }
                    lastConfigSignature = sig
                    lastConfigTime = now
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
                        else if (postData.contains("set_lens")) action = "set_lens"
                        else if (postData.contains("set_zoom")) action = "set_zoom"
                        else if (postData.contains("toggle_stabilization")) action = "toggle_stabilization"
                        else if (postData.contains("set_stabilization")) action = "set_stabilization"
                        else if (postData.contains("set_camera_unit")) action = "set_camera_unit"
                    }
                    val params = jsonObj ?: JSONObject()
                    if (action.isNotBlank()) {
                        callback.onActionRequested(action, params)
                    }
                    if (action == "toggle_stabilization" || action == "set_stabilization") {
                        val status = callback.getStatus()
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"ok\",\"action\":\"${escapeJson(action)}\",\"stabilization\":${status.isStabilization},\"has_ois\":${status.hasOis},\"has_eis\":${status.hasEis}}"
                        )
                    }
                    if (action == "set_camera_unit") {
                        val status = callback.getStatus()
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"ok\",\"action\":\"${escapeJson(action)}\",\"current_camera_unit\":\"${escapeJson(status.currentCameraUnit)}\"}"
                        )
                    }
                    if (action == "toggle_mic_mute") {
                        val status = callback.getStatus()
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"ok\",\"action\":\"${escapeJson(action)}\",\"mic_muted\":${status.isMicMuted}}"
                        )
                    }
                    if (action == "set_lens") {
                        val status = callback.getStatus()
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"ok\",\"action\":\"${escapeJson(action)}\",\"lens\":\"${escapeJson(status.currentLens)}\",\"zoom\":${status.currentZoom}}"
                        )
                    }
                    if (action == "set_zoom") {
                        val status = callback.getStatus()
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"ok\",\"action\":\"${escapeJson(action)}\",\"zoom\":${status.currentZoom}}"
                        )
                    }
                    if (action == "set_manual_exposure" || action == "set_manual_focus" || action == "set_awb_mode") {
                        val status = callback.getStatus()
                        return newJsonResponse(
                            Response.Status.OK,
                            "{\"status\":\"ok\",\"action\":\"${escapeJson(action)}\",\"manual_exposure\":${status.isManualExposure},\"iso\":${status.iso},\"manual_focus\":${status.isManualFocus},\"focus_distance\":${status.focusDistance},\"awb_mode\":\"${escapeJson(status.awbMode)}\"}"
                        )
                    }
                    return newJsonResponse(Response.Status.OK, "{\"status\":\"ok\",\"action\":\"${escapeJson(action)}\"}")
                }
                "/api/manual_exposure" -> {
                    val p = jsonObj ?: JSONObject()
                    callback.onActionRequested("set_manual_exposure", p)
                    val status = callback.getStatus()
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"manual_exposure\":${status.isManualExposure},\"iso\":${status.iso},\"exposure_time_ns\":${status.exposureTimeNs}}"
                    )
                }
                "/api/manual_focus" -> {
                    val p = jsonObj ?: JSONObject()
                    callback.onActionRequested("set_manual_focus", p)
                    val status = callback.getStatus()
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"manual_focus\":${status.isManualFocus},\"focus_distance\":${status.focusDistance}}"
                    )
                }
                "/api/awb" -> {
                    val p = jsonObj ?: JSONObject()
                    callback.onActionRequested("set_awb_mode", p)
                    val status = callback.getStatus()
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"awb_mode\":\"${escapeJson(status.awbMode)}\"}"
                    )
                }
                "/api/lens" -> {
                    val lens = jsonObj?.optString("lens") ?: parms["lens"]?.firstOrNull() ?: "1x"
                    val p = JSONObject().apply { put("lens", lens) }
                    callback.onActionRequested("set_lens", p)
                    val status = callback.getStatus()
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"lens\":\"${escapeJson(status.currentLens)}\",\"zoom\":${status.currentZoom}}"
                    )
                }
                "/api/zoom" -> {
                    val ratioStr = jsonObj?.optString("ratio") ?: parms["ratio"]?.firstOrNull() ?: "1.0"
                    val ratio = ratioStr.toDoubleOrNull() ?: 1.0
                    val p = JSONObject().apply { put("ratio", ratio) }
                    callback.onActionRequested("set_zoom", p)
                    val status = callback.getStatus()
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"zoom\":${status.currentZoom}}"
                    )
                }
                "/api/stabilization" -> {
                    val enabled = jsonObj?.optBoolean("enabled") ?: parms["enabled"]?.firstOrNull()?.toBoolean() ?: true
                    val p = JSONObject().apply { put("enabled", enabled) }
                    callback.onActionRequested("set_stabilization", p)
                    val status = callback.getStatus()
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"stabilization\":${status.isStabilization},\"has_ois\":${status.hasOis},\"has_eis\":${status.hasEis}}"
                    )
                }
                "/api/camera_unit" -> {
                    val unitId = jsonObj?.optString("id") ?: parms["id"]?.firstOrNull() ?: "0"
                    val isPhys = jsonObj?.optBoolean("is_physical") ?: parms["is_physical"]?.firstOrNull()?.toBoolean() ?: false
                    val p = JSONObject().apply {
                        put("id", unitId)
                        put("is_physical", isPhys)
                    }
                    callback.onActionRequested("set_camera_unit", p)
                    val status = callback.getStatus()
                    return newJsonResponse(
                        Response.Status.OK,
                        "{\"status\":\"ok\",\"current_camera_unit\":\"${escapeJson(status.currentCameraUnit)}\"}"
                    )
                }
                "/api/status" -> {
                    val status = callback.getStatus()
                    val responseJson = JSONObject().apply {
                        put("status", "ready")
                        put("streaming", status.isStreaming)
                        put("camera", status.cameraFacing)
                        put("torch", status.isTorchOn)
                        put("orientation", status.orientation)
                        put("orient", status.orient)
                        put("mic_muted", status.isMicMuted)
                        put("lens", status.currentLens)
                        put("zoom", status.currentZoom)
                        put("min_zoom", status.minZoom)
                        put("max_zoom", status.maxZoom)
                        put("available_lenses", org.json.JSONArray(status.availableLenses))
                        put("battery_level", status.batteryLevel)
                        put("battery_temp", status.batteryTemp)
                        put("is_charging", status.isCharging)
                        put("thermal_status", status.thermalStatus)
                        put("manual_exposure", status.isManualExposure)
                        put("iso", status.iso)
                        put("exposure_time_ns", status.exposureTimeNs)
                        put("min_iso", status.minIso)
                        put("max_iso", status.maxIso)
                        put("min_exposure_ns", status.minExposureNs)
                        put("max_exposure_ns", status.maxExposureNs)
                        put("manual_focus", status.isManualFocus)
                        put("focus_distance", status.focusDistance)
                        put("max_focus_distance", status.maxFocusDistance)
                        put("awb_mode", status.awbMode)
                        put("max_fps", status.maxFps)
                        put("supported_fps", org.json.JSONArray(status.supportedFps))
                        put("current_fps", status.currentFps)
                        put("current_fps_range", status.currentFpsRange)
                        put("width", status.width)
                        put("height", status.height)
                        put("resolution", status.resolution)
                        put("auto_framing", status.isAutoFraming)
                        put("pipeline_mode", status.pipelineMode)
                        put("stabilization", status.isStabilization)
                        put("has_ois", status.hasOis)
                        put("has_eis", status.hasEis)
                        put("current_camera_unit", status.currentCameraUnit)
                        val unitsArr = org.json.JSONArray()
                        for (u in status.cameraUnits) {
                            unitsArr.put(org.json.JSONObject().apply {
                                put("id", u.id)
                                put("name", u.name)
                                put("type", u.type)
                                put("facing", u.facing)
                                put("focal_length", u.focalLengthMm)
                                put("has_ois", u.hasOis)
                                put("is_physical", u.isPhysical)
                            })
                        }
                        put("camera_units", unitsArr)
                    }.toString()
                    return newJsonResponse(Response.Status.OK, responseJson)
                }
                "/api/diagnostics" -> {
                    return newJsonResponse(Response.Status.OK, callback.getDiagnosticsJson())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки HTTP запроса", e)
            return newJsonResponse(Response.Status.INTERNAL_ERROR, "{\"error\":\"${escapeJson(e.message ?: "internal_error")}\"}")
        }

        return newJsonResponse(Response.Status.NOT_FOUND, "{\"error\":\"not_found\"}")
    }

    private fun newJsonResponse(status: Response.Status, json: String): Response {
        val resp = newFixedLengthResponse(status, "application/json", json)
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }
}
