package com.example.ccamera.gl

import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.params.Face
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log

/**
 * Контроллер автокадрирования (Auto-Framing).
 * Анализирует координаты лиц из Camera2 Face Detection, сглаживает рамку через
 * экспоненциальный фильтр (EMA) с зоной нечувствительности (deadband)
 * и вычисляет матрицу кропа для шейдера OpenGL ES.
 */
class AutoFramingController {
    companion object {
        private const val TAG = "AutoFraming"
        private const val SMOOTHING_ALPHA = 0.08f // Коэффициент сглаживания (кинематографичное движение)
        private const val DEADBAND = 0.012f       // Порог нечувствительности для устранения микро-дребезга
        private const val FACE_TIMEOUT_MS = 1500L // Время удержания кадра после потери лица перед сбросом в 1.0x
        private const val MAX_ZOOM = 2.2f         // Максимальное приближение лица (сохраняет детализацию)
    }

    @Volatile
    var isEnabled: Boolean = false

    // Текущие сглаженные нормализованные координаты области кропа сенсора [0..1]
    private var curXMin = 0.0f
    private var curYMin = 0.0f
    private var curXMax = 1.0f
    private var curYMax = 1.0f

    // Целевые координаты области кропа сенсора [0..1]
    private var targetXMin = 0.0f
    private var targetYMin = 0.0f
    private var targetXMax = 1.0f
    private var targetYMax = 1.0f

    private var lastFaceTimestampMs = 0L

    // 4x4 матрица кропа текстурных координат для OpenGL шейдера
    val cropMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
    }

    fun reset() {
        isEnabled = false
        targetXMin = 0.0f
        targetYMin = 0.0f
        targetXMax = 1.0f
        targetYMax = 1.0f
        curXMin = 0.0f
        curYMin = 0.0f
        curXMax = 1.0f
        curYMax = 1.0f
        Matrix.setIdentityM(cropMatrix, 0)
    }

    /**
     * Обработка детекции лиц из Camera2 CaptureResult.
     * Координаты лиц вычисляются непосредственно в координатном пространстве активной матрицы сенсора,
     * что обеспечивает корректное кадрирование при любом угле переворота экрана (0°, 90°, 180°, 270°).
     */
    fun onFacesDetected(
        faces: Array<Face>?,
        sensorRect: Rect?,
        streamOrientation: Int = 90,
        isFront: Boolean = false
    ) {
        if (!isEnabled || sensorRect == null || sensorRect.width() <= 0 || sensorRect.height() <= 0) {
            targetXMin = 0.0f
            targetYMin = 0.0f
            targetXMax = 1.0f
            targetYMax = 1.0f
            return
        }

        val now = SystemClock.uptimeMillis()
        if (faces != null && faces.isNotEmpty()) {
            lastFaceTimestampMs = now

            // Находим охватывающий прямоугольник для всех обнаруженных лиц в координатах матрицы сенсора
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            for (face in faces) {
                val b = face.bounds
                if (b.left < minX) minX = b.left
                if (b.top < minY) minY = b.top
                if (b.right > maxX) maxX = b.right
                if (b.bottom > maxY) maxY = b.bottom
            }

            val sW = sensorRect.width().toFloat()
            val sH = sensorRect.height().toFloat()

            // Нормализованные координаты на сенсоре [0..1]
            val normXMin = ((minX - sensorRect.left).toFloat() / sW).coerceIn(0f, 1f)
            val normXMax = ((maxX - sensorRect.left).toFloat() / sW).coerceIn(0f, 1f)
            val normYMin = ((minY - sensorRect.top).toFloat() / sH).coerceIn(0f, 1f)
            val normYMax = ((maxY - sensorRect.top).toFloat() / sH).coerceIn(0f, 1f)

            val centerX = (normXMin + normXMax) / 2.0f
            val centerY = (normYMin + normYMax) / 2.0f

            val faceW = (normXMax - normXMin).coerceAtLeast(0.01f)
            val faceH = (normYMax - normYMin).coerceAtLeast(0.01f)

            // Пропорциональный размер кадра с комфортным отступом (голова, плечи, окружение)
            val rawSpan = maxOf(faceW, faceH)
            val minCropSpan = 1.0f / MAX_ZOOM
            val cropSpan = (rawSpan * 1.85f).coerceIn(minCropSpan, 1.0f)

            // Вычисляем целевой охват кадра с центрированием
            val newXMin = (centerX - cropSpan / 2.0f).coerceIn(0.0f, 1.0f - cropSpan)
            val newYMin = (centerY - cropSpan / 2.0f).coerceIn(0.0f, 1.0f - cropSpan)

            targetXMin = newXMin
            targetXMax = newXMin + cropSpan
            targetYMin = newYMin
            targetYMax = newYMin + cropSpan
        } else {
            // Если лиц нет дольше FACE_TIMEOUT_MS — плавно возвращаемся к общему виду 1.0x
            if (now - lastFaceTimestampMs > FACE_TIMEOUT_MS) {
                targetXMin = 0.0f
                targetYMin = 0.0f
                targetXMax = 1.0f
                targetYMax = 1.0f
            }
        }
    }

    /**
     * Обновляет сглаженные координаты и матрицу для текущего отрисовываемого кадра.
     */
    fun updateMatrix() {
        if (!isEnabled) {
            Matrix.setIdentityM(cropMatrix, 0)
            curXMin = 0.0f
            curYMin = 0.0f
            curXMax = 1.0f
            curYMax = 1.0f
            return
        }

        // Экспоненциальное сглаживание с мертвой зоной (устраняет микро-дребезг)
        val dXMin = targetXMin - curXMin
        val dYMin = targetYMin - curYMin
        val dXMax = targetXMax - curXMax
        val dYMax = targetYMax - curYMax

        if (Math.abs(dXMin) > DEADBAND) curXMin += dXMin * SMOOTHING_ALPHA
        if (Math.abs(dYMin) > DEADBAND) curYMin += dYMin * SMOOTHING_ALPHA
        if (Math.abs(dXMax) > DEADBAND) curXMax += dXMax * SMOOTHING_ALPHA
        if (Math.abs(dYMax) > DEADBAND) curYMax += dYMax * SMOOTHING_ALPHA

        val spanX = (curXMax - curXMin).coerceIn(0.1f, 1.0f)
        val spanY = (curYMax - curYMin).coerceIn(0.1f, 1.0f)
        // Сохраняем строго изотропный масштаб (1:1), чтобы исключить любые искажения пропорций
        val span = maxOf(spanX, spanY)

        val transX = curXMin.coerceIn(0.0f, 1.0f - span)
        // В OpenGL vTextureCoord.y инвертирован относительно матричных строк сенсора (origin внизу слева)
        val transY = (1.0f - (curYMin + span)).coerceIn(0.0f, 1.0f - span)

        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, transX, transY, 0.0f)
        Matrix.scaleM(cropMatrix, 0, span, span, 1.0f)
    }
}
