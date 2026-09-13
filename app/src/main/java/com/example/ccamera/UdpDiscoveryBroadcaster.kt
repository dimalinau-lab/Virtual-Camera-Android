package com.example.ccamera

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpDiscoveryBroadcaster {
    private var job: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        job = Thread {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                val payload = "{\"service\":\"VirtualCamNative\",\"http_port\":8080,\"stream_port\":8554}".toByteArray()
                val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 8888)

                while (running) {
                    try {
                        socket.send(packet)
                    } catch (_: Exception) {}
                    Thread.sleep(1000)
                }
                socket.close()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        job?.interrupt()
        job = null
    }
}
