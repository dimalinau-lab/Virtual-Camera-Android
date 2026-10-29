package com.example.ccamera.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * Управление EGL контекстом и дисплеем (EGL14 + OpenGL ES 2.0/3.0).
 * Поддерживает EGL_RECORDABLE_ANDROID для прямой записи в MediaCodec Surface.
 */
class EglCore(
    sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT,
    flags: Int = FLAG_RECORDABLE
) {
    companion object {
        private const val TAG = "EglCore"
        const val FLAG_RECORDABLE = 0x01
        const val FLAG_TRY_GLES3 = 0x02

        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    private var eglConfig: EGLConfig? = null

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("eglInitialize failed")
        }

        val config = getConfig(flags) ?: throw RuntimeException("Unable to find suitable EGLConfig")
        eglConfig = config

        val attrib3List = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        val attrib2List = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        var context = EGL14.EGL_NO_CONTEXT
        if ((flags and FLAG_TRY_GLES3) != 0) {
            context = EGL14.eglCreateContext(eglDisplay, config, sharedContext, attrib3List, 0)
        }
        if (context === EGL14.EGL_NO_CONTEXT) {
            context = EGL14.eglCreateContext(eglDisplay, config, sharedContext, attrib2List, 0)
        }

        if (context === EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }
        eglContext = context
    }

    private fun getConfig(flags: Int): EGLConfig? {
        val renderableType = EGL14.EGL_OPENGL_ES2_BIT
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL_RECORDABLE_ANDROID, if ((flags and FLAG_RECORDABLE) != 0) 1 else 0,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            Log.w(TAG, "eglChooseConfig failed with recordable flag, trying fallback...")
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, configs.size, numConfigs, 0)) {
                return null
            }
        }
        return configs[0]
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        if (surface !is Surface && surface !is android.graphics.SurfaceTexture) {
            throw RuntimeException("Invalid surface object: $surface")
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed")
        }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
            throw RuntimeException("eglMakeNothingCurrent failed")
        }
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun querySurface(eglSurface: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, what, value, 0)
        return value[0]
    }

    fun release() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    protected fun finalize() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "WARNING: EglCore was not explicitly released before garbage collection")
            release()
        }
    }
}
