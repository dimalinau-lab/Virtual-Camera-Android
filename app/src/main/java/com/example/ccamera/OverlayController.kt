package com.example.ccamera

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView

class OverlayController(
    private val context: Context,
    private val listener: OverlayListener? = null
) {

    interface OverlayListener {
        fun onSurfaceAvailable(surface: Surface)
        fun onSurfaceDestroyed()
        fun onFlipCamera()
        fun onToggleMute(): Boolean
        fun onExpandToApp()
        fun onToggleEco()
        fun onCloseService()
    }

    enum class Status {
        WAITING,    // Жёлтый (#FFC107) - Ожидание подключения
        STREAMING,  // Зелёный (#4CAF50) - Стрим активен
        ERROR       // Красный (#F44336) - Ошибка
    }

    companion object {
        private const val TAG = "OverlayController"
        private const val COLOR_WAITING = "#FFC107"
        private const val COLOR_STREAMING = "#10B981"
        private const val COLOR_ERROR = "#EF4444"
        private const val AUTO_HIDE_DELAY_MS = 3500L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var textureView: TextureView? = null
    private var controlsView: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var micMutedBadge: ImageView? = null
    private var btnMute: ImageView? = null

    private var surface: Surface? = null
    private var params: WindowManager.LayoutParams? = null
    private var isOverlayShown = false
    val isShown: Boolean get() = isOverlayShown
    private var currentStatus: Status = Status.WAITING
    private var isMicMuted: Boolean = false

    private val autoHideRunnable = Runnable {
        hideControls()
    }

    fun showOverlay() {
        if (isOverlayShown) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Нет разрешения SYSTEM_ALERT_WINDOW на отображение поверх окон")
            return
        }

        try {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.overlay_floating_widget, null)
            overlayView = view

            textureView = view.findViewById(R.id.overlayTextureView)
            controlsView = view.findViewById(R.id.overlayControls)
            statusDot = view.findViewById(R.id.overlayStatusDot)
            statusText = view.findViewById(R.id.overlayStatusText)
            micMutedBadge = view.findViewById(R.id.overlayMicMutedBadge)

            setupTextureView()
            setupControls(view)
            setupLayoutParams()
            setupTouchAndDrag(view)

            windowManager.addView(view, params)
            isOverlayShown = true

            updateStatusVisuals(currentStatus)
            updateMicStateVisuals(isMicMuted)
            Log.i(TAG, "Плавающий видеовиджет отображен на экране")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при добавлении плавающего видеовиджета", e)
        }
    }

    fun hideOverlay() {
        if (!isOverlayShown) return
        try {
            mainHandler.removeCallbacks(autoHideRunnable)
            surface?.release()
            surface = null
            listener?.onSurfaceDestroyed()

            overlayView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            }
            overlayView = null
            textureView = null
            controlsView = null
            statusDot = null
            statusText = null
            micMutedBadge = null
            btnMute = null
            isOverlayShown = false
            Log.i(TAG, "Плавающий видеовиджет скрыт")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при удалении плавающего видеовиджета", e)
        }
    }

    fun setStatus(status: Status) {
        currentStatus = status
        if (isOverlayShown) {
            mainHandler.post { updateStatusVisuals(status) }
        }
    }

    fun setMicMuted(muted: Boolean) {
        isMicMuted = muted
        if (isOverlayShown) {
            mainHandler.post { updateMicStateVisuals(muted) }
        }
    }

    private fun setupTextureView() {
        val tv = textureView ?: return
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface?.release()
                val newSurface = Surface(st)
                surface = newSurface
                Log.i(TAG, "TextureView оверлея доступен, передаем Surface в камеру: $newSurface")
                listener?.onSurfaceAvailable(newSurface)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                Log.i(TAG, "TextureView оверлея уничтожен")
                surface?.release()
                surface = null
                listener?.onSurfaceDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        if (tv.isAvailable) {
            val st = tv.surfaceTexture
            if (st != null) {
                surface?.release()
                val newSurface = Surface(st)
                surface = newSurface
                listener?.onSurfaceAvailable(newSurface)
            }
        }
    }

    private fun setupControls(root: View) {
        val flipBtn = root.findViewById<ImageView>(R.id.btnOverlayFlip)
        val muteBtn = root.findViewById<ImageView>(R.id.btnOverlayMute)
        val ecoBtn = root.findViewById<ImageView>(R.id.btnOverlayEco)
        val expandBtn = root.findViewById<ImageView>(R.id.btnOverlayExpand)
        val closeBtn = root.findViewById<ImageView>(R.id.btnOverlayClose)
        btnMute = muteBtn

        flipBtn?.setOnClickListener {
            listener?.onFlipCamera()
            resetAutoHideTimer()
        }

        muteBtn?.setOnClickListener {
            val newMute = listener?.onToggleMute() ?: false
            setMicMuted(newMute)
            resetAutoHideTimer()
        }

        ecoBtn?.setOnClickListener {
            listener?.onToggleEco()
            resetAutoHideTimer()
        }

        expandBtn?.setOnClickListener {
            hideOverlay()
            listener?.onExpandToApp()
        }

        closeBtn?.setOnClickListener {
            listener?.onCloseService()
        }
    }

    private fun toggleControls() {
        val cv = controlsView ?: return
        if (cv.visibility == View.VISIBLE) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        val cv = controlsView ?: return
        cv.visibility = View.VISIBLE
        cv.alpha = 0f
        cv.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
        resetAutoHideTimer()
    }

    private fun hideControls() {
        val cv = controlsView ?: return
        mainHandler.removeCallbacks(autoHideRunnable)
        cv.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                cv.visibility = View.GONE
            }
            .start()
    }

    private fun resetAutoHideTimer() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun setupLayoutParams() {
        val density = context.resources.displayMetrics.density
        val widgetW = (112 * density).toInt()
        val widgetH = (168 * density).toInt()

        val layoutParams = WindowManager.LayoutParams(
            widgetW,
            widgetH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START

        val metrics = getScreenMetrics()
        val marginEdge = (16 * density).toInt()
        val marginBottom = (120 * density).toInt()

        layoutParams.x = metrics.widthPixels - widgetW - marginEdge
        layoutParams.y = metrics.heightPixels - widgetH - marginBottom

        params = layoutParams
    }

    private fun setupTouchAndDrag(view: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        p.x = initialX + dx
                        p.y = initialY + dy
                        if (view.isAttachedToWindow) {
                            try {
                                windowManager.updateViewLayout(view, p)
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                        toggleControls()
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
        val widgetW = (112 * density).toInt()
        val margin = (16 * density).toInt()

        val targetX = if (currentX + widgetW / 2 < displayWidth / 2) {
            margin
        } else {
            displayWidth - widgetW - margin
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 240L
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
        val txt = statusText ?: return

        val hexColor = when (status) {
            Status.WAITING -> COLOR_WAITING
            Status.STREAMING -> COLOR_STREAMING
            Status.ERROR -> COLOR_ERROR
        }

        txt.text = when (status) {
            Status.WAITING -> "WAIT"
            Status.STREAMING -> "LIVE"
            Status.ERROR -> "ERR"
        }

        val drawable = (dot.background as? GradientDrawable) ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        drawable.setColor(Color.parseColor(hexColor))
        dot.background = drawable
    }

    private fun updateMicStateVisuals(muted: Boolean) {
        micMutedBadge?.visibility = if (muted) View.VISIBLE else View.GONE
        btnMute?.let { btn ->
            if (muted) {
                btn.setImageResource(R.drawable.ic_mic_off)
                btn.setColorFilter(Color.parseColor("#EF4444"))
            } else {
                btn.setImageResource(R.drawable.ic_mic)
                btn.setColorFilter(Color.WHITE)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics
    }
}
