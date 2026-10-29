package com.example.ccamera.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Высокопроизводительный OpenGL ES 2.0 рендерер камеры.
 * Забирает кадры с Camera2 через GL_TEXTURE_EXTERNAL_OES (SurfaceTexture),
 * накладывает в реальном времени шейдерное автокадрирование (Auto-Framing)
 * и выводит кадры с точными наносекундными таймстемпами в MediaCodec Surface
 * (и локальный видоискатель).
 */
class CameraGlRenderer(
    private val width: Int,
    private val height: Int,
    private val codecSurface: Surface
) {
    companion object {
        private const val TAG = "CameraGlRenderer"

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform mat4 uCropMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vec4 cropped = uCropMatrix * aTextureCoord;
                vTextureCoord = (uTexMatrix * cropped).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform int uEffectMode;
            void main() {
                if (uEffectMode == 2) {
                    gl_FragColor = vec4(0.04, 0.04, 0.06, 1.0);
                    return;
                }
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        private val FULL_QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )

        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
    }

    val autoFraming = AutoFramingController()

    private val glThread = HandlerThread("CameraGlRendererThread").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglCore: EglCore? = null
    private var codecWindowSurface: EglWindowSurface? = null
    private var previewWindowSurface: EglWindowSurface? = null
    private var previewSurface: Surface? = null

    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    var cameraSurface: Surface? = null
        private set

    private var programId: Int = 0
    private var uTexMatrixLoc: Int = -1
    private var uCropMatrixLoc: Int = -1
    private var uEffectModeLoc: Int = -1
    private var aPositionLoc: Int = -1
    private var aTextureCoordLoc: Int = -1

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_COORDS)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_TEX_COORDS)
            position(0)
        }

    private val texMatrix = FloatArray(16)
    @Volatile
    private var isRunning = true
    @Volatile
    var effectMode: Int = 0 // 0 = normal, 1 = blur/bokeh, 2 = black/idle
    @Volatile
    var orientationDegrees: Int = 0
    @Volatile
    var isFrontCamera: Boolean = false

    private val codecTexMatrix = FloatArray(16)
    private val cancelRotMatrix = FloatArray(16)

    fun setOrientation(degrees: Int) {
        orientationDegrees = degrees
    }

    private fun getCodecTexMatrix(): FloatArray {
        // SurfaceTexture.getTransformMatrix() автоматически включает поворот сенсора (90° для задней камеры, 270° для фронтальной).
        // Поскольку ПК (D3D11 оптика) сам аппаратно разворачивает кадры по протоколу MagerCam (g_streamOrientation),
        // для потока в кодек (codecWin) мы компенсируем этот поворот, делая буфер кодека 100% идентичным прямому режиму (Direct Surface).
        val cancelAngle = if (isFrontCamera) 90.0f else -90.0f
        Matrix.setIdentityM(cancelRotMatrix, 0)
        Matrix.translateM(cancelRotMatrix, 0, 0.5f, 0.5f, 0.0f)
        Matrix.rotateM(cancelRotMatrix, 0, cancelAngle, 0.0f, 0.0f, 1.0f)
        Matrix.translateM(cancelRotMatrix, 0, -0.5f, -0.5f, 0.0f)

        Matrix.multiplyMM(codecTexMatrix, 0, texMatrix, 0, cancelRotMatrix, 0)
        return codecTexMatrix
    }

    init {
        val readyLock = Object()
        var initSuccess = false

        glHandler.post {
            synchronized(readyLock) {
                try {
                    val core = EglCore(flags = EglCore.FLAG_RECORDABLE)
                    eglCore = core
                    codecWindowSurface = EglWindowSurface(core, codecSurface).apply {
                        makeCurrent()
                    }

                    initGlProgram()
                    initSurfaceTexture()

                    initSuccess = true
                    Log.i(TAG, "CameraGlRenderer успешно инициализирован ($width x $height)")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка инициализации GL рендерера", e)
                } finally {
                    readyLock.notifyAll()
                }
            }
        }

        synchronized(readyLock) {
            if (!initSuccess && cameraSurface == null) {
                readyLock.wait(1500)
            }
        }
    }

    private fun initGlProgram() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("GL Program link error: $log")
        }

        programId = program
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCropMatrixLoc = GLES20.glGetUniformLocation(program, "uCropMatrix")
        uEffectModeLoc = GLES20.glGetUniformLocation(program, "uEffectMode")
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
    }

    private fun initSurfaceTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({
            if (isRunning) {
                glHandler.post { drawFrame() }
            }
        }, glHandler)

        surfaceTexture = st
        cameraSurface = Surface(st)
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation error: $log")
        }
        return shader
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        val codecWin = codecWindowSurface ?: return

        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val ptsNs = st.timestamp

            autoFraming.updateMatrix()

            // 1. Отрисовка в MediaCodec Input Surface (поток на ПК)
            codecWin.makeCurrent()
            GLES20.glViewport(0, 0, width, height)
            renderQuad(isForPreview = false, targetWidth = width, targetHeight = height)
            codecWin.setPresentationTime(ptsNs)
            codecWin.swapBuffers()

            // 2. Отрисовка в локальный видоискатель (экран телефона / плавающее окно)
            previewWindowSurface?.let { previewWin ->
                previewWin.makeCurrent()
                val pW = previewWin.width
                val pH = previewWin.height
                if (pW > 0 && pH > 0) {
                    GLES20.glViewport(0, 0, pW, pH)
                    renderQuad(isForPreview = true, targetWidth = pW, targetHeight = pH)
                    previewWin.swapBuffers()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка drawFrame: ", e)
        }
    }

    private fun renderQuad(isForPreview: Boolean, targetWidth: Int, targetHeight: Int) {
        GLES20.glUseProgram(programId)

        val activeTexMatrix = if (!isForPreview) getCodecTexMatrix() else texMatrix
        val activeCropMatrix = autoFraming.cropMatrix

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, activeTexMatrix, 0)
        GLES20.glUniformMatrix4fv(uCropMatrixLoc, 1, false, activeCropMatrix, 0)
        GLES20.glUniform1i(uEffectModeLoc, effectMode)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
    }

    fun setPreviewSurface(surface: Surface?) {
        glHandler.post {
            previewWindowSurface?.release()
            previewWindowSurface = null
            previewSurface = surface
            if (surface != null && surface.isValid) {
                eglCore?.let { core ->
                    try {
                        previewWindowSurface = EglWindowSurface(core, surface)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка создания previewWindowSurface", e)
                    }
                }
            }
        }
    }

    fun release() {
        isRunning = false
        val releaseLock = Object()

        glHandler.post {
            synchronized(releaseLock) {
                try {
                    previewWindowSurface?.release()
                    previewWindowSurface = null
                    codecWindowSurface?.release()
                    codecWindowSurface = null

                    if (textureId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                        textureId = 0
                    }
                    if (programId != 0) {
                        GLES20.glDeleteProgram(programId)
                        programId = 0
                    }

                    cameraSurface?.release()
                    cameraSurface = null
                    surfaceTexture?.release()
                    surfaceTexture = null

                    eglCore?.release()
                    eglCore = null
                    Log.i(TAG, "CameraGlRenderer ресурсы полностью освобождены")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при освобождении GL ресурсов", e)
                } finally {
                    releaseLock.notifyAll()
                }
            }
        }

        synchronized(releaseLock) {
            releaseLock.wait(1000)
        }

        glThread.quitSafely()
    }
}
