package com.example.ccamera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private const val CHUNK_SIZE = 1024
    }

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientSocket: Socket? = null
    @Volatile
    private var outStream: OutputStream? = null

    @Volatile
    var isMuted: Boolean = true

    private var audioRecord: AudioRecord? = null

    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val recordExecutor = Executors.newSingleThreadExecutor()

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Запуск AudioStreamer на порту $port")
        startServerSocket()
    }

    private fun startServerSocket() {
        serverExecutor.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                Log.i(TAG, "Аудио-сервер слушает интерфейс 0.0.0.0 на порту $port")

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

                        Log.i(TAG, "ПК подключился к аудио-серверу: ${socket.remoteSocketAddress}")

                        startAudioRecording()

                        val inStream = socket.getInputStream()
                        val dummyBuffer = ByteArray(128)
                        while (isRunning.get() && isConnected.get()) {
                            val read = inStream.read(dummyBuffer)
                            if (read == -1) break
                        }
                    } catch (e: IOException) {
                        if (!isRunning.get()) break
                    } finally {
                        stopAudioRecording()
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
                Log.i(TAG, "Запись аудио запущена (VOICE_COMMUNICATION, 48000Hz, MONO, 16BIT)")

                val buffer = ByteArray(CHUNK_SIZE)
                while (isRunning.get() && isConnected.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = record.read(buffer, 0, CHUNK_SIZE)
                    if (bytesRead > 0) {
                        if (isMuted) {
                            java.util.Arrays.fill(buffer, 0, bytesRead, 0.toByte())
                        }
                        val stream = outStream
                        if (stream != null) {
                            try {
                                stream.write(buffer, 0, bytesRead)
                                // ВНИМАНИЕ: Не вызывать flush() на каждый микрочанкер!
                            } catch (e: Exception) {
                                Log.e(TAG, "Ошибка записи в аудио сокет: ${e.message}")
                                break
                            }
                        } else {
                            break
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
