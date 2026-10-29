package com.example.ccamera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RawH265Streamer(
    private val port: Int = 8554,
    @Volatile private var width: Int = 1280,
    @Volatile private var height: Int = 720,
    bitRate: Int = 5_500_000,
    @Volatile var frameRate: Int = 60,
    private val listener: StatusListener? = null
) {
    interface StatusListener {
        fun onStatusChanged(status: String, isConnected: Boolean)
    }

    companion object {
        private const val TAG = "RawH265Streamer"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_HEVC

        const val PKT_VIDEO_CONFIG: Byte = 1
        const val PKT_VIDEO_FRAME: Byte  = 2
        const val PKT_AUDIO_CONFIG: Byte = 3
        const val PKT_AUDIO: Byte        = 4
        const val PKT_STATUS: Byte       = 5
        const val PKT_PING: Byte         = 6
    }

    @Volatile
    var bitRate: Int = bitRate
        private set

    private var nominalBitrate: Int = bitRate
    private var lastAbrDropMs: Long = 0L
    private var lastAbrRecoverMs: Long = 0L

    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientSocket: Socket? = null
    @Volatile
    private var dataOutputStream: DataOutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    @Volatile
    var isPaused: Boolean = false
        private set

    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val codecExecutor = Executors.newSingleThreadExecutor()

    class StreamPacket(
        var type: Byte = PKT_VIDEO_FRAME,
        var flags: Byte = 0,
        var data: ByteArray = ByteArray(256 * 1024),
        var size: Int = 0,
        var ptsUs: Long = 0L,
        var isVideo: Boolean = true,
        var isKey: Boolean = false,
        var tNs: Long = System.nanoTime()
    )

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val packetPool = ConcurrentLinkedQueue<StreamPacket>()
    private val packetQueue = LinkedBlockingDeque<StreamPacket>()

    val queueAgeMs: Long
        get() {
            val first = packetQueue.peekFirst() ?: return 0L
            return (System.nanoTime() - first.tNs) / 1_000_000L
        }

    private fun obtainPacket(minCapacity: Int): StreamPacket {
        val pkt = packetPool.poll() ?: StreamPacket(data = ByteArray(maxOf(minCapacity, 256 * 1024)))
        if (pkt.data.size < minCapacity) {
            pkt.data = ByteArray(maxOf(minCapacity, pkt.data.size * 2))
        }
        return pkt
    }

    private fun recyclePacket(pkt: StreamPacket) {
        pkt.size = 0
        if (packetPool.size < 32) {
            packetPool.offer(pkt)
        }
    }

    fun enqueuePacket(type: Byte, flags: Byte, src: ByteArray, length: Int, ptsUs: Long, isVideo: Boolean, isKey: Boolean = false) {
        if (!isConnected.get() || !isRunning.get()) return
        if (isPaused && isVideo) return

        val pkt = obtainPacket(length)
        pkt.type = type
        pkt.flags = flags
        pkt.size = length
        pkt.ptsUs = ptsUs
        pkt.isVideo = isVideo
        pkt.isKey = isKey
        pkt.tNs = System.nanoTime()
        if (length > 0) {
            System.arraycopy(src, 0, pkt.data, 0, length)
        }

        while (packetQueue.size >= 16) {
            val old = packetQueue.poll()
            if (old != null) recyclePacket(old)
        }

        packetQueue.offer(pkt)
    }

    fun enqueuePacket(type: Byte, flags: Byte, buffer: ByteBuffer, offset: Int, length: Int, ptsUs: Long, isVideo: Boolean, isKey: Boolean = false) {
        if (!isConnected.get() || !isRunning.get()) return
        if (isPaused && isVideo) return

        val pkt = obtainPacket(length)
        pkt.type = type
        pkt.flags = flags
        pkt.size = length
        pkt.ptsUs = ptsUs
        pkt.isVideo = isVideo
        pkt.isKey = isKey
        pkt.tNs = System.nanoTime()
        if (length > 0) {
            val origPos = buffer.position()
            buffer.position(offset)
            buffer.get(pkt.data, 0, length)
            buffer.position(origPos)
        }

        while (packetQueue.size >= 16) {
            val old = packetQueue.poll()
            if (old != null) recyclePacket(old)
        }

        packetQueue.offer(pkt)
    }

    fun enqueueAudio(pcm: ByteArray, size: Int, ptsUs: Long) {
        if (!isConnected.get() || isPaused) return
        enqueuePacket(PKT_AUDIO, 0, pcm, size, ptsUs, isVideo = false)
    }

    fun enqueueStatus(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        enqueuePacket(PKT_STATUS, 0, bytes, bytes.size, 0L, isVideo = false)
    }

    fun checkAbrCongestion(ageMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (ageMs > 200L) {
            if (now - lastAbrDropMs > 2000L && bitRate > 1_500_000) {
                val reduced = (bitRate * 0.70).toInt().coerceAtLeast(1_000_000)
                Log.w(TAG, "[ABR] Очередь $ageMs мс > 200 мс! Снижение битрейта: $bitRate -> $reduced")
                updateBitrate(reduced)
                lastAbrDropMs = now
            }
        } else if (ageMs < 50L && bitRate < nominalBitrate) {
            if (now - lastAbrDropMs > 5000L && now - lastAbrRecoverMs > 3000L) {
                val recovered = (bitRate + 500_000).coerceAtMost(nominalBitrate)
                Log.i(TAG, "[ABR] Сеть свободна ($ageMs мс). Восстановление: $bitRate -> $recovered")
                updateBitrate(recovered)
                lastAbrRecoverMs = now
            }
        }
    }

    fun checkLatencyDrop(ageMs: Long) {
        if (ageMs > 450L) {
            Log.w(TAG, "[LATENCY DROP] Задержка очереди $ageMs мс > 450 мс! Сброс видеоочереди...")
            flushVideoQueue()
            requestKeyFrame()
        }
    }

    fun flushVideoQueue() {
        val it = packetQueue.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.isVideo) {
                it.remove()
                recyclePacket(p)
            }
        }
    }

    private var cachedCsdBuffer: ByteArray? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        initMediaCodec()
        startServerSocket()
        startNetworkSender()
        startCodecLoop()
    }

    fun updateBitrate(newBitrate: Int) {
        this.bitRate = newBitrate
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            }
            mediaCodec?.setParameters(params)
            Log.i("ROUTING_DEBUG", "Битрейт динамически изменен на: $newBitrate bps")
            Log.i(TAG, "Битрейт динамически обновлен: $newBitrate bps")
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "Ошибка динамической смены битрейта", e)
        }
    }

    fun pauseStreaming() {
        isPaused = true
    }

    fun resumeStreaming() {
        isPaused = false
        requestKeyFrame()
    }

    @Synchronized
    fun reinitCodec(newWidth: Int, newHeight: Int, newFps: Int, newBitrate: Int): Surface? {
        Log.i(TAG, "reinitCodec: $newWidth x $newHeight @ $newFps FPS, $newBitrate bps")
        this.width = newWidth
        this.height = newHeight
        this.frameRate = newFps
        this.bitRate = newBitrate

        stopCodecOnly()
        initMediaCodec()
        return inputSurface
    }

    fun disconnectClient() {
        synchronized(this) {
            try {
                dataOutputStream?.close()
                clientSocket?.close()
            } catch (_: Exception) {}
            dataOutputStream = null
            clientSocket = null
            isConnected.set(false)
            while (true) {
                val leftover = packetQueue.poll() ?: break
                recyclePacket(leftover)
            }
        }
        if (isRunning.get()) {
            listener?.onStatusChanged("Ожидание подключения C++ клиента...", false)
        }
    }

    fun stopCodecOnly() {
        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }

    private fun initMediaCodec() {
        try {
            stopCodecOnly()

            val repeatFrameUs = 1000000L / frameRate

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate)
                
                // Cyclic Intra Refresh (равномерный битрейт без забивания сети резкими I-кадрами)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, frameRate)
                }
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3) // Устраняем тепловой конфликт/дублирование с Intra-Refresh

                // Ультра-низкая задержка (Ultra Low-Latency):
                setInteger("latency", 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Согласуем частоту чипа VPU с реальным FPS вместо принудительного оверклока Short.MAX_VALUE
                    setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                // 3. Отключаем B-кадры и скрытую задержку чипа
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec

            synchronized(this) {
                cachedCsdBuffer = null
            }
            Log.i(TAG, "MediaCodec Surface готов ($width x $height @ $frameRate fps, CBR, repeatFrameAfter=${repeatFrameUs}us)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации MediaCodec", e)
            listener?.onStatusChanged("Ошибка кодека: ${e.message}", false)
        }
    }

    private fun startServerSocket() {
        serverExecutor.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                Log.i(TAG, "Видео-сервер слушает интерфейс 0.0.0.0 на порту $port")

                while (isRunning.get()) {
                    try {
                        val socket = ss.accept()
                        socket.tcpNoDelay = true // Отключаем задержку Nagle для мгновенной отправки
                        socket.sendBufferSize = 512 * 1024 // 512 КБ буфер сокета для сглаживания всплесков I-кадров

                        var stream: DataOutputStream
                        synchronized(this) {
                            clientSocket?.close()
                            clientSocket = socket
                            stream = DataOutputStream(java.io.BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
                            dataOutputStream = stream
                            isConnected.set(true)
                        }

                        listener?.onStatusChanged("Стриминг активен", true)

                        // 1. Отправляем конфигурацию аудио (48000 Гц, 1 канал, 16-бит PCM)
                        val audioCfg = ByteBuffer.allocate(8).apply {
                            putInt(48000)
                            putShort(1.toShort())
                            putShort(16.toShort())
                        }.array()
                        enqueuePacket(PKT_AUDIO_CONFIG, 0, audioCfg, audioCfg.size, 0L, isVideo = false)

                        // 2. Отправляем кэшированный CSD (VPS/SPS/PPS) в заголовке MUX
                        synchronized(this) {
                            cachedCsdBuffer?.let { csd ->
                                enqueuePacket(PKT_VIDEO_CONFIG, 0x03, csd, csd.size, 0L, isVideo = true, isKey = true)
                            }
                        }

                        // 3. Отправляем текущий статус потока
                        enqueueStatus("{\"width\":$width,\"height\":$height,\"fps\":$frameRate,\"bitrate\":$bitRate}")

                        // 4. Немедленно запрашиваем ключевой кадр (IDR / Key-frame) для нового клиента
                        requestKeyFrame()

                        val inStream = socket.getInputStream()
                        val buffer = ByteArray(128)
                        while (isRunning.get() && isConnected.get()) {
                            val read = inStream.read(buffer)
                            if (read == -1) break
                        }
                    } catch (e: IOException) {
                        if (!isRunning.get()) break
                    } finally {
                        disconnectClient()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Ошибка сервера видео", e)
                }
            }
        }
    }

    fun requestSyncFrame() {
        requestKeyFrame()
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.i("ROUTING_DEBUG", "Запрошен ключевой кадр (IDR / Key-frame) для нового клиента")
            Log.i(TAG, "Запрошен ключевой кадр (IDR / Sync Frame)")
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "Ошибка запроса ключевого кадра", e)
        }
    }

    private fun startNetworkSender() {
        networkExecutor.execute {
            Log.i(TAG, "Асинхронный поток отправки пакетов MUX (NetworkSender) запущен")
            val header = ByteArray(16)
            header[0] = 0x4D.toByte() // 'M'
            header[1] = 0x43.toByte() // 'C'

            while (isRunning.get()) {
                try {
                    val pkt = packetQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    if (!isConnected.get() || (isPaused && pkt.isVideo)) {
                        recyclePacket(pkt)
                        continue
                    }

                    val stream = dataOutputStream ?: run {
                        recyclePacket(pkt)
                        continue
                    }

                    header[2] = pkt.type
                    header[3] = pkt.flags

                    val len = pkt.size
                    header[4] = ((len ushr 24) and 0xFF).toByte()
                    header[5] = ((len ushr 16) and 0xFF).toByte()
                    header[6] = ((len ushr 8) and 0xFF).toByte()
                    header[7] = (len and 0xFF).toByte()

                    val pts = pkt.ptsUs
                    header[8] = ((pts ushr 56) and 0xFF).toByte()
                    header[9] = ((pts ushr 48) and 0xFF).toByte()
                    header[10] = ((pts ushr 40) and 0xFF).toByte()
                    header[11] = ((pts ushr 32) and 0xFF).toByte()
                    header[12] = ((pts ushr 24) and 0xFF).toByte()
                    header[13] = ((pts ushr 16) and 0xFF).toByte()
                    header[14] = ((pts ushr 8) and 0xFF).toByte()
                    header[15] = (pts and 0xFF).toByte()

                    stream.write(header, 0, 16)
                    if (len > 0) {
                        stream.write(pkt.data, 0, len)
                    }
                    stream.flush()
                    recyclePacket(pkt)
                } catch (e: Exception) {
                    if (isRunning.get() && isConnected.get()) {
                        Log.e(TAG, "Ошибка передачи пакета по сети", e)
                        disconnectClient()
                    }
                }
            }
        }
    }

    private fun startCodecLoop() {
        codecExecutor.execute {
            val bufferInfo = MediaCodec.BufferInfo()
            var lastFrameTimeNs = 0L
            var frameCount = 0
            var totalDeltaMs = 0L

            Log.i("ROUTING_DEBUG", "Поток чтения MediaCodec запущен")

            while (isRunning.get()) {
                val codec = mediaCodec
                if (codec == null) {
                    try { Thread.sleep(10) } catch (_: Exception) {}
                    continue
                }

                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if (frameCount < 5) {
                                Log.i("ROUTING_DEBUG", "Кодек выдал кадр #${frameCount}, размер: ${bufferInfo.size} байт, флаги: ${bufferInfo.flags}")
                                frameCount++
                            }

                            val nowNs = System.nanoTime()
                            if (lastFrameTimeNs != 0L) {
                                val deltaMs = (nowNs - lastFrameTimeNs) / 1_000_000f
                                totalDeltaMs += deltaMs.toLong()
                                if (frameCount % 60 == 0) {
                                    val avgDeltaMs = totalDeltaMs.toFloat() / 60f
                                    val calcFps = if (avgDeltaMs > 0) 1000f / avgDeltaMs else 0f
                                    Log.i(TAG, "MediaCodec output: дельта между буферами = ${String.format(java.util.Locale.US, "%.1f", deltaMs)}мс (~${String.format(java.util.Locale.US, "%.1f", calcFps)} FPS)")
                                    totalDeltaMs = 0L
                                }
                            }
                            lastFrameTimeNs = nowNs

                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isCodecConfig) {
                                cacheCsdBuffer(outputBuffer, bufferInfo)
                                enqueuePacket(PKT_VIDEO_CONFIG, 0x03, outputBuffer, bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, isVideo = true, isKey = true)
                            } else if (isConnected.get() && !isPaused && bufferInfo.size > 0) {
                                val ageMs = queueAgeMs
                                checkAbrCongestion(ageMs)
                                checkLatencyDrop(ageMs)

                                val flags: Byte = if (isKeyFrame) 0x01 else 0x00
                                enqueuePacket(PKT_VIDEO_FRAME, flags, outputBuffer, bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, isVideo = true, isKey = isKeyFrame)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun cacheCsdBuffer(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val size = bufferInfo.size
        if (size <= 0) return
        val csd = ByteArray(size)
        val originalPos = buffer.position()
        buffer.get(csd, 0, size)
        buffer.position(originalPos)
        synchronized(this) {
            cachedCsdBuffer = csd
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        disconnectClient()
        packetPool.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        // ОБЯЗАТЕЛЬНО явно освобождаем inputSurface перед остановкой и освобождением кодека!
        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        serverExecutor.shutdownNow()
        codecExecutor.shutdownNow()
        networkExecutor.shutdownNow()

        Log.i(TAG, "RawH265Streamer остановлен, inputSurface и MediaCodec полностью освобождены")
    }
}
