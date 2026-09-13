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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RawH265Streamer(
    private val port: Int = 8554,
    private val width: Int = 1280,
    private val height: Int = 720,
    bitRate: Int = 7_000_000,
    private val frameRate: Int = 30,
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

    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val codecExecutor = Executors.newSingleThreadExecutor()

    private val cachedCsdNals = ArrayList<ByteArray>()

    fun start() {
        if (isRunning.getAndSet(true)) return

        initMediaCodec()
        startServerSocket()
        startCodecLoop()
    }

    fun updateBitrate(newBitrate: Int) {
        this.bitRate = newBitrate
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "Битрейт динамически обновлен: $newBitrate bps")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления битрейта", e)
        }
    }

    private fun initMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

                setInteger("latency", 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            Log.i(TAG, "MediaCodec Surface готов ($width x $height @ $frameRate fps)")
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
                        socket.tcpNoDelay = true
                        socket.sendBufferSize = 256 * 1024

                        var stream: DataOutputStream
                        synchronized(this) {
                            clientSocket?.close()
                            clientSocket = socket
                            stream = DataOutputStream(java.io.BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
                            dataOutputStream = stream
                            isConnected.set(true)
                        }

                        listener?.onStatusChanged("Стриминг активен", true)

                        synchronized(cachedCsdNals) {
                            for (nal in cachedCsdNals) {
                                stream.writeInt(nal.size)
                                stream.write(nal)
                            }
                            stream.flush()
                        }

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

    private fun disconnectClient() {
        synchronized(this) {
            try {
                dataOutputStream?.close()
                clientSocket?.close()
            } catch (_: Exception) {}
            dataOutputStream = null
            clientSocket = null
            isConnected.set(false)
        }
        if (isRunning.get()) {
            listener?.onStatusChanged("Ожидание подключения C++ клиента...", false)
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (_: Exception) {}
    }

    private fun startCodecLoop() {
        codecExecutor.execute {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning.get()) {
                val codec = mediaCodec ?: break
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (isCodecConfig) {
                                cacheCsdNals(outputBuffer, bufferInfo)
                            }

                            if (isConnected.get()) {
                                synchronized(this) {
                                    val stream = dataOutputStream
                                    if (stream != null) {
                                        try {
                                            sendNalUnits(outputBuffer, bufferInfo, stream)
                                        } catch (e: Exception) {
                                            disconnectClient()
                                        }
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun cacheCsdNals(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val nals = extractNalUnits(buffer, bufferInfo)
        synchronized(cachedCsdNals) {
            cachedCsdNals.clear()
            cachedCsdNals.addAll(nals)
        }
    }

    private fun extractNalUnits(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        val array = ByteArray(bufferInfo.size)
        val originalPos = buffer.position()
        buffer.get(array, 0, bufferInfo.size)
        buffer.position(originalPos)

        var i = 0
        val end = array.size
        val startIndices = ArrayList<Int>()
        val prefixLengths = ArrayList<Int>()

        while (i <= end - 3) {
            if (array[i] == 0.toByte() && array[i + 1] == 0.toByte()) {
                if (i <= end - 4 && array[i + 2] == 0.toByte() && array[i + 3] == 1.toByte()) {
                    startIndices.add(i + 4)
                    prefixLengths.add(4)
                    i += 4
                    continue
                } else if (array[i + 2] == 1.toByte()) {
                    startIndices.add(i + 3)
                    prefixLengths.add(3)
                    i += 3
                    continue
                }
            }
            i++
        }

        if (startIndices.isEmpty()) {
            if (array.isNotEmpty()) result.add(array)
            return result
        }

        for (k in 0 until startIndices.size) {
            val nalStart = startIndices[k]
            val nalEnd = if (k < startIndices.size - 1) {
                startIndices[k + 1] - prefixLengths[k + 1]
            } else {
                end
            }
            val nalSize = nalEnd - nalStart
            if (nalSize > 0) {
                val nalBytes = ByteArray(nalSize)
                System.arraycopy(array, nalStart, nalBytes, 0, nalSize)
                result.add(nalBytes)
            }
        }
        return result
    }

    private fun sendNalUnits(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, stream: DataOutputStream) {
        val array = ByteArray(bufferInfo.size)
        val originalPos = buffer.position()
        buffer.get(array, 0, bufferInfo.size)
        buffer.position(originalPos)

        var i = 0
        val end = array.size
        val startIndices = ArrayList<Int>()
        val prefixLengths = ArrayList<Int>()

        while (i <= end - 3) {
            if (array[i] == 0.toByte() && array[i + 1] == 0.toByte()) {
                if (i <= end - 4 && array[i + 2] == 0.toByte() && array[i + 3] == 1.toByte()) {
                    startIndices.add(i + 4)
                    prefixLengths.add(4)
                    i += 4
                    continue
                } else if (array[i + 2] == 1.toByte()) {
                    startIndices.add(i + 3)
                    prefixLengths.add(3)
                    i += 3
                    continue
                }
            }
            i++
        }

        if (startIndices.isEmpty()) {
            if (array.isNotEmpty()) {
                stream.writeInt(array.size)
                stream.write(array)
                stream.flush()
            }
            return
        }

        for (k in 0 until startIndices.size) {
            val nalStart = startIndices[k]
            val nalEnd = if (k < startIndices.size - 1) {
                startIndices[k + 1] - prefixLengths[k + 1]
            } else {
                end
            }
            val nalSize = nalEnd - nalStart
            if (nalSize > 0) {
                stream.writeInt(nalSize)
                stream.write(array, nalStart, nalSize)
            }
        }
        stream.flush()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        disconnectClient()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        inputSurface?.release()
        inputSurface = null

        serverExecutor.shutdownNow()
        codecExecutor.shutdownNow()

        Log.i(TAG, "Стример остановлен")
    }
}