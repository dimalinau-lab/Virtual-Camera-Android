package com.example.ccamera

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

class OverlayController(private val context: Context) {

    enum class Status {
        WAITING,    // Жёлтый (#FFC107) - Ожидание подключения
        STREAMING,  // Зелёный (#4CAF50) - Стрим активен
        ERROR       // Красный (#F44336) - Ошибка
    }

    companion object {
        private const val TAG = "OverlayController"
        private const val COLOR_WAITING = "#FFC107"
        private const val COLOR_STREAMING = "#4CAF50"
        private const val COLOR_ERROR = "#F44336"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var statusDot: View? = null
    private var pulseRing: View? = null
    private var pulseAnimator: ObjectAnimator? = null

    private var params: WindowManager.LayoutParams? = null
    private var isOverlayShown = false
    private var currentStatus: Status = Status.WAITING

    fun showOverlay() {
        if (isOverlayShown) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Нет разрешения SYSTEM_ALERT_WINDOW на отображение поверх окон")
            return
        }

        try {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.overlay_floating_widget, null) as FrameLayout
            overlayView = view

            statusDot = view.findViewById(R.id.statusDot)
            pulseRing = view.findViewById(R.id.pulseRing)

            setupLayoutParams()
            setupTouchAndDrag(view)

            windowManager.addView(view, params)
            isOverlayShown = true

            updateStatusVisuals(currentStatus)
            Log.i(TAG, "Плавающая точка-индикатор отображена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при добавлении точки-индикатора", e)
        }
    }

    fun hideOverlay() {
        if (!isOverlayShown) return
        try {
            stopPulseAnimation()
            overlayView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            }
            overlayView = null
            statusDot = null
            pulseRing = null
            isOverlayShown = false
            Log.i(TAG, "Плавающая точка-индикатор скрыта")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при удалении точки-индикатора", e)
        }
    }

    fun setStatus(status: Status) {
        currentStatus = status
        if (isOverlayShown) {
            updateStatusVisuals(status)
        }
    }

    private fun setupLayoutParams() {
        val density = context.resources.displayMetrics.density
        val widgetSize = (40 * density).toInt()

        val layoutParams = WindowManager.LayoutParams(
            widgetSize,
            widgetSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START

        val metrics = getScreenMetrics()
        val marginEdge = (16 * density).toInt()
        val marginBottom = (110 * density).toInt()

        // Размещаем в нижней части экрана у правого края
        layoutParams.x = metrics.widthPixels - widgetSize - marginEdge
        layoutParams.y = metrics.heightPixels - widgetSize - marginBottom

        params = layoutParams
    }

    private fun setupTouchAndDrag(view: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    p.x = initialX + dx
                    p.y = initialY + dy
                    if (view.isAttachedToWindow) {
                        windowManager.updateViewLayout(view, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val distance = Math.hypot(dx.toDouble(), dy.toDouble())

                    if (distance < touchSlop) {
                        view.performClick()
                        onWidgetClick()
                    } else {
                        snapToNearestEdge(p.x)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge(currentX: Int) {
        val view = overlayView ?: return
        val p = params ?: return

        val metrics = getScreenMetrics()
        val displayWidth = metrics.widthPixels
        val density = context.resources.displayMetrics.density
        val widgetSize = (40 * density).toInt()
        val margin = (16 * density).toInt()

        val targetX = if (currentX + widgetSize / 2 < displayWidth / 2) {
            margin
        } else {
            displayWidth - widgetSize - margin
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                p.x = anim.animatedValue as Int
                if (view.isAttachedToWindow) {
                    try {
                        windowManager.updateViewLayout(view, p)
                    } catch (_: Exception) {}
                }
            }
        }
        animator.start()
    }

    private fun updateStatusVisuals(status: Status) {
        val dot = statusDot ?: return
        val ring = pulseRing ?: return

        val hexColor = when (status) {
            Status.WAITING -> COLOR_WAITING
            Status.STREAMING -> COLOR_STREAMING
            Status.ERROR -> COLOR_ERROR
        }

        // Обновляем цвет точки
        val drawable = (dot.background as? GradientDrawable) ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((1.5f * context.resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        drawable.setColor(Color.parseColor(hexColor))
        dot.background = drawable

        if (status == Status.STREAMING) {
            ring.visibility = View.VISIBLE
            startPulseAnimation(ring)
        } else {
            ring.visibility = View.GONE
            stopPulseAnimation()
        }
    }

    private fun startPulseAnimation(ringView: View) {
        stopPulseAnimation()
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.6f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.6f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0.1f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(ringView, scaleX, scaleY, alpha).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRing?.let { ring ->
            ring.scaleX = 1.0f
            ring.scaleY = 1.0f
            ring.alpha = 1.0f
        }
    }

    private fun onWidgetClick() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при перезапуске MainActivity из оверлея", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics
    }
}
