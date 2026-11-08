package com.example.ccamera

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class UdpBeaconBroadcaster(private val deviceId: String) {
    @Volatile private var isRunning = false
    private var broadcastThread: Thread? = null
    private var probeListenerThread: Thread? = null

    companion object {
        private const val TAG = "UdpBeacon"
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    val bcast = addr.broadcast
                    if (bcast != null) {
                        addresses.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка получения broadcast адресов", e)
        }
        return addresses.distinct()
    }

    fun start(port: Int = 8888, httpPort: Int = 8080) {
        if (isRunning) return
        isRunning = true

        val json = JSONObject().apply {
            put("service", "VirtualCamNative")
            put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("device_id", deviceId)
            put("port", httpPort)
            put("http_port", httpPort)
            put("stream_port", 8554)
        }.toString()
        val bytes = json.toByteArray()

        // 1. Поток периодического широковещания (быстрый старт + subnet broadcast)
        broadcastThread = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true

                var count = 0
                while (isRunning) {
                    val bcastList = getBroadcastAddresses()
                    for (bcast in bcastList) {
                        try {
                            val packet = DatagramPacket(bytes, bytes.size, bcast, port)
                            socket.send(packet)
                        } catch (_: Exception) {}
                    }
                    count++
                    val sleepMs = if (count < 10) 200L else 450L
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка в потоке UdpBeaconBroadcaster", e)
            } finally {
                socket?.close()
            }
        }.apply { isDaemon = true; start() }

        // 2. Двунаправленный слушатель активных Probe-запросов от ПК (порт 8889)
        probeListenerThread = Thread {
            var probeSocket: DatagramSocket? = null
            try {
                probeSocket = DatagramSocket(8889)
                probeSocket.broadcast = true
                val buf = ByteArray(512)

                while (isRunning) {
                    val inPacket = DatagramPacket(buf, buf.size)
                    probeSocket.receive(inPacket)
                    val receivedStr = String(inPacket.data, 0, inPacket.length)
                    if (receivedStr.contains("VirtualCamNative")) {
                        // Мгновенный направленный ответ на IP-адрес запросившего ПК
                        try {
                            val replyPacket = DatagramPacket(bytes, bytes.size, inPacket.address, port)
                            probeSocket.send(replyPacket)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                probeSocket?.close()
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        isRunning = false
        broadcastThread?.interrupt()
        probeListenerThread?.interrupt()
        broadcastThread = null
        probeListenerThread = null
    }
}
