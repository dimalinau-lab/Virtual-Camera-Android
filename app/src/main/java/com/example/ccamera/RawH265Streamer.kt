package com.example.ccamera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RawH265Streamer(
    private val port: Int = 8554,
    @Volatile private var width: Int = 1280,
    @Volatile private var height: Int = 720,
    bitRate: Int = 5_500_000,
    @Volatile private var frameRate: Int = 60,
    private val listener: StatusListener? = null
) {
    interface StatusListener {
        fun onStatusChanged(status: String, isConnected: Boolean)
    }

    companion object {
        private const val TAG = "RawH265Streamer"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_HEVC
    }

    @Volatile
    var bitRate: Int = bitRate
        private set

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
    class PooledFrame(var data: ByteArray, var size: Int = 0)

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val framePool = ArrayBlockingQueue<PooledFrame>(16)
    private val frameQueue = ArrayBlockingQueue<PooledFrame>(6)

    init {
        for (i in 0 until 16) {
            framePool.offer(PooledFrame(ByteArray(256 * 1024), 0))
        }
    }

    private fun obtainFrame(minCapacity: Int): PooledFrame {
        val pooled = framePool.poll() ?: PooledFrame(ByteArray(maxOf(minCapacity, 256 * 1024)), 0)
        if (pooled.data.size < minCapacity) {
            pooled.data = ByteArray(maxOf(minCapacity, pooled.data.size * 2))
        }
        return pooled
    }

    private fun recycleFrame(frame: PooledFrame) {
        frame.size = 0
        framePool.offer(frame)
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
                val leftover = frameQueue.poll() ?: break
                recycleFrame(leftover)
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

                        // 1. Отправляем кэшированный CSD (VPS/SPS/PPS) целиком единым пакетом
                        synchronized(this) {
                            cachedCsdBuffer?.let { csd ->
                                stream.writeInt(csd.size)
                                stream.write(csd)
                                stream.flush()
                            }
                        }

                        // 2. Немедленно запрашиваем ключевой кадр (IDR / Key-frame) для нового клиента
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
            Log.i(TAG, "Асинхронный поток отправки NALU (NetworkSender) запущен")
            while (isRunning.get()) {
                try {
                    val frame = frameQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    if (!isConnected.get() || isPaused) {
                        recycleFrame(frame)
                        continue
                    }

                    val stream = dataOutputStream ?: run {
                        recycleFrame(frame)
                        continue
                    }
                    stream.writeInt(frame.size)
                    stream.write(frame.data, 0, frame.size)
                    stream.flush()
                    recycleFrame(frame)
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
                            if (isCodecConfig) {
                                cacheCsdBuffer(outputBuffer, bufferInfo)
                            }

                            if (isConnected.get() && !isPaused && bufferInfo.size > 0) {
                                val size = bufferInfo.size
                                val frame = obtainFrame(size)
                                val originalPos = outputBuffer.position()
                                outputBuffer.get(frame.data, 0, size)
                                outputBuffer.position(originalPos)
                                frame.size = size

                                // Если Wi-Fi временно перегружен (очередь 6 кадров заполнена), сбрасываем старый кадр и просим чистый IDR
                                if (!frameQueue.offer(frame)) {
                                    val dropped = frameQueue.poll()
                                    if (dropped != null) recycleFrame(dropped)
                                    if (!frameQueue.offer(frame)) {
                                        recycleFrame(frame)
                                    }
                                    requestKeyFrame()
                                }
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
        framePool.clear()

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
