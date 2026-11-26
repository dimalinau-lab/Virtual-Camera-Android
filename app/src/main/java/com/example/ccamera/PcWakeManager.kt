package com.example.ccamera

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import kotlin.concurrent.thread

object PcWakeManager {
    private const val TAG = "PcWakeManager"
    private const val PREFS_NAME = "vcam_pc_info"
    private const val KEY_MAC = "last_pc_mac"
    private const val KEY_IP = "last_pc_ip"
    private const val KEY_MODE = "last_pc_mode"

    fun savePcInfo(context: Context, mac: String? = null, ip: String? = null, mode: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (!mac.isNullOrBlank()) editor.putString(KEY_MAC, mac)
        if (!ip.isNullOrBlank()) editor.putString(KEY_IP, ip)
        if (!mode.isNullOrBlank()) editor.putString(KEY_MODE, mode)
        editor.apply()
        Log.i(TAG, "Сохранены данные ПК: mac=$mac, ip=$ip, mode=$mode")
    }

    fun getPcMac(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MAC, "") ?: ""

    fun getPcIp(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_IP, "") ?: ""

    fun getPcMode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MODE, "usb") ?: "usb"

    fun clearPcInfo(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.i(TAG, "Данные сопряжения ПК полностью удалены (vcam_pc_info)")
    }

    /**
     * Отправка Wake-on-LAN Magic Packet (UDP порт 9)
     */
    fun sendWakeOnLan(macAddress: String) {
        if (macAddress.isBlank()) return
        thread(isDaemon = true, name = "PcWake-WoL") {
            try {
                val cleanMac = macAddress.replace(":", "").replace("-", "")
                if (cleanMac.length != 12) {
                    Log.w(TAG, "Некорректный MAC для WoL: $macAddress")
                    return@thread
                }

                val macBytes = ByteArray(6)
                for (i in 0 until 6) {
                    macBytes[i] = cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }

                // 102 байта: 6x 0xFF + 16x MAC
                val bytes = ByteArray(102)
                for (i in 0 until 6) {
                    bytes[i] = 0xFF.toByte()
                }
                for (i in 0 until 16) {
                    System.arraycopy(macBytes, 0, bytes, 6 + i * 6, 6)
                }

                val broadcastAddresses = mutableListOf<InetAddress>()
                try {
                    broadcastAddresses.add(InetAddress.getByName("255.255.255.255"))
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        if (iface.isLoopback || !iface.isUp) continue
                        for (addr in iface.interfaceAddresses) {
                            val bcast = addr.broadcast
                            if (bcast != null) broadcastAddresses.add(bcast)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка получения broadcast адресов для WoL", e)
                }

                val socket = DatagramSocket()
                socket.broadcast = true
                for (bcast in broadcastAddresses.distinct()) {
                    try {
                        val packet = DatagramPacket(bytes, bytes.size, bcast, 9)
                        socket.send(packet)
                    } catch (_: Exception) {}
                }
                socket.close()
                Log.i(TAG, "Wake-on-LAN Magic Packet отправлен на MAC: $macAddress")
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка отправки WoL пакета", e)
            }
        }
    }

    /**
     * Автоматический запуск / пробуждение ПК при запуске приложения на телефоне
     */
    fun wakePcOnLaunch(context: Context) {
        thread(isDaemon = true, name = "PcWake-Auto") {
            val cachedMac = getPcMac(context)
            val cachedIp = getPcIp(context)

            // Проверяем, есть ли сопряжение с данным ПК
            val pairedPrefs = context.getSharedPreferences("vcam_paired_clients", Context.MODE_PRIVATE)
            val allTokens = pairedPrefs.all.values
            val authToken = allTokens.firstOrNull() as? String ?: ""

            // Если данных ПК нет или устройство забыто, авто-пробуждение не отправляется
            if (cachedMac.isBlank() && cachedIp.isBlank()) {
                Log.d(TAG, "Авто-пробуждение пропущено: устройство не привязано к ПК")
                return@thread
            }

            // 1. Отправляем Wake-on-LAN, если сохранен MAC адрес ПК
            if (cachedMac.isNotBlank()) {
                sendWakeOnLan(cachedMac)
            }

            // 2. Пытаемся разбудить по USB через ADB Reverse (http://127.0.0.1:8000/api/wake)
            var awakened = trySendWakeHttp("http://127.0.0.1:8000/api/wake", "usb", authToken, context)

            // 3. Если USB не ответил и сохранен IP ПК в сети Wi-Fi, шлем запрос по Wi-Fi
            if (!awakened && cachedIp.isNotBlank()) {
                awakened = trySendWakeHttp("http://$cachedIp:8000/api/wake", "wifi", authToken, context)
            }

            if (awakened) {
                Log.i(TAG, "ПК успешно разбужен и окно VirtualCam активировано!")
            } else {
                Log.d(TAG, "Авто-пробуждение: ПК еще не доступен или устройство не сопряжено")
            }
        }
    }

    private fun trySendWakeHttp(urlStr: String, mode: String, authToken: String, context: Context): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 800
                readTimeout = 800
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JSONObject().apply {
                put("mode", mode)
                put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                if (authToken.isNotBlank()) {
                    put("auth_token", authToken)
                }
            }.toString()

            conn.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { it.write(payload) }
            }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                val mac = json.optString("mac", "")
                savePcInfo(context, mac = mac, mode = mode)
                conn.disconnect()
                true
            } else {
                conn.disconnect()
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
