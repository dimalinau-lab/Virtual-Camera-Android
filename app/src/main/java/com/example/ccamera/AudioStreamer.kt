package com.example.ccamera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamer(
    private val port: Int = 8555
) {
    companion object {
        private const val TAG = "AudioStreamer"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private const val CHUNK_SIZE = 1024
    }

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientSocket: Socket? = null
    @Volatile
    private var outStream: OutputStream? = null

    @Volatile
    var audioSink: ((pcm: ByteArray, size: Int, ptsUs: Long) -> Unit)? = null

    @Volatile
    var isMuted: Boolean = false

    private var lastMuteState = false

    private var audioRecord: AudioRecord? = null

    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val recordExecutor = Executors.newSingleThreadExecutor()

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Запуск AudioStreamer на порту $port")
        startServerSocket()
        startAudioRecording()
    }

    private fun startServerSocket() {
        serverExecutor.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                Log.i(TAG, "Аудио-сервер слушает интерфейс 0.0.0.0 на порту $port (Legacy fallback)")

                while (isRunning.get()) {
                    try {
                        val socket = ss.accept()
                        socket.tcpNoDelay = true
                        socket.sendBufferSize = 64 * 1024

                        synchronized(this) {
                            disconnectClientInternal()
                            clientSocket = socket
                            outStream = socket.getOutputStream()
                            isConnected.set(true)
                        }

                        Log.i(TAG, "ПК подключился к legacy аудио-серверу: ${socket.remoteSocketAddress}")

                        val inStream = socket.getInputStream()
                        val dummyBuffer = ByteArray(128)
                        while (isRunning.get() && isConnected.get()) {
                            val read = inStream.read(dummyBuffer)
                            if (read == -1) break
                        }
                    } catch (e: IOException) {
                        if (!isRunning.get()) break
                    } finally {
                        disconnectClientInternal()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Ошибка аудио-сервера", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        recordExecutor.execute {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 4)

            try {
                val record = AudioRecord(
                    AUDIO_SOURCE,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Не удалось инициализировать AudioRecord")
                    record.release()
                    return@execute
                }

                audioRecord = record
                record.startRecording()
                Log.i(TAG, "Запись аудио запущена (VOICE_COMMUNICATION, 48000Hz, MONO, 16BIT, TIMEBASE_BOOTTIME)")

                val buffer = ByteArray(CHUNK_SIZE)
                val audioTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) AudioTimestamp() else null

                while (isRunning.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = record.read(buffer, 0, CHUNK_SIZE)
                    if (bytesRead > 0) {
                        applyMuteWithRamping(buffer, bytesRead, isMuted)

                        var ptsUs: Long = 0L
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && audioTimestamp != null) {
                            if (record.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                                ptsUs = audioTimestamp.nanoTime / 1000L
                            }
                        }
                        if (ptsUs <= 0L) {
                            ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
                        }

                        // Мультиплексированная отправка с точным аппаратным PTS (Zero-Drift Lip-Sync)
                        val sink = audioSink
                        if (sink != null) {
                            sink.invoke(buffer.copyOf(bytesRead), bytesRead, ptsUs)
                        } else {
                            // Legacy fallback сокет (если мультиплексирование не активно)
                            val stream = outStream
                            if (stream != null && isConnected.get()) {
                                try {
                                    stream.write(buffer, 0, bytesRead)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Ошибка записи в legacy аудио сокет: ${e.message}")
                                    disconnectClientInternal()
                                }
                            }
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "Ошибка чтения AudioRecord: $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в потоке записи аудио", e)
            } finally {
                stopAudioRecording()
            }
        }
    }

    private fun stopAudioRecording() {
        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }

    private fun applyMuteWithRamping(buffer: ByteArray, bytesRead: Int, muted: Boolean) {
        val sampleCount = bytesRead / 2
        if (muted) {
            if (!lastMuteState) {
                val rampSamples = minOf(64, sampleCount)
                for (s in 0 until rampSamples) {
                    val factor = 1.0f - (s.toFloat() / rampSamples)
                    val idx = s * 2
                    val sample = (buffer[idx].toInt() and 0xFF) or (buffer[idx + 1].toInt() shl 8)
                    val shortSample = sample.toShort()
                    val ramped = (shortSample * factor).toInt().coerceIn(-32768, 32767).toShort()
                    buffer[idx] = (ramped.toInt() and 0xFF).toByte()
                    buffer[idx + 1] = ((ramped.toInt() shr 8) and 0xFF).toByte()
                }
                if (bytesRead > rampSamples * 2) {
                    java.util.Arrays.fill(buffer, rampSamples * 2, bytesRead, 0.toByte())
                }
                lastMuteState = true
            } else {
                java.util.Arrays.fill(buffer, 0, bytesRead, 0.toByte())
            }
        } else {
            if (lastMuteState) {
                val rampSamples = minOf(64, sampleCount)
                for (s in 0 until rampSamples) {
                    val factor = s.toFloat() / rampSamples
                    val idx = s * 2
                    val sample = (buffer[idx].toInt() and 0xFF) or (buffer[idx + 1].toInt() shl 8)
                    val shortSample = sample.toShort()
                    val ramped = (shortSample * factor).toInt().coerceIn(-32768, 32767).toShort()
                    buffer[idx] = (ramped.toInt() and 0xFF).toByte()
                    buffer[idx + 1] = ((ramped.toInt() shr 8) and 0xFF).toByte()
                }
                lastMuteState = false
            }
        }
    }

    private fun disconnectClientInternal() {
        synchronized(this) {
            try {
                outStream?.close()
                clientSocket?.close()
            } catch (_: Exception) {}
            outStream = null
            clientSocket = null
            isConnected.set(false)
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        stopAudioRecording()
        disconnectClientInternal()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        serverExecutor.shutdownNow()
        recordExecutor.shutdownNow()

        Log.i(TAG, "AudioStreamer остановлен, сокеты и AudioRecord освобождены")
    }
}
