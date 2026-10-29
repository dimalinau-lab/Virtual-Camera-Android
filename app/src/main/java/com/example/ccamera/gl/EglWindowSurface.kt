package com.example.ccamera.gl

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Обертка над EGLSurface для отрисовки в Android Surface (MediaCodec или Display).
 */
class EglWindowSurface(
    private val eglCore: EglCore,
    private val surface: Surface
) {
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        eglSurface = eglCore.createWindowSurface(surface)
    }

    val width: Int get() = if (eglSurface !== EGL14.EGL_NO_SURFACE) eglCore.querySurface(eglSurface, EGL14.EGL_WIDTH) else 0
    val height: Int get() = if (eglSurface !== EGL14.EGL_NO_SURFACE) eglCore.querySurface(eglSurface, EGL14.EGL_HEIGHT) else 0

    fun makeCurrent() {
        eglCore.makeCurrent(eglSurface)
    }

    fun swapBuffers(): Boolean {
        return eglCore.swapBuffers(eglSurface)
    }

    fun setPresentationTime(nsecs: Long) {
        eglCore.setPresentationTime(eglSurface, nsecs)
    }

    fun release() {
        if (eglSurface !== EGL14.EGL_NO_SURFACE) {
            eglCore.releaseSurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}
