package com.example.ccamera

import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpBeaconBroadcaster(private val deviceId: String) {
    @Volatile private var isRunning = false
    private var thread: Thread? = null

    fun start(port: Int = 8888, httpPort: Int = 8080) {
        if (isRunning) return
        isRunning = true
        thread = Thread {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true

                val json = JSONObject().apply {
                    put("service", "VirtualCamNative")
                    put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("device_id", deviceId)
                    put("port", httpPort)
                    put("http_port", httpPort)
                    put("stream_port", 8554)
                }.toString()

                val bytes = json.toByteArray()

                while (isRunning) {
                    try {
                        val packet = DatagramPacket(
                            bytes, bytes.size,
                            InetAddress.getByName("255.255.255.255"), port
                        )
                        socket.send(packet)
                    } catch (_: Exception) {}
                    try {
                        Thread.sleep(1500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                socket.close()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        isRunning = false
        thread?.interrupt()
        thread = null
    }
}
