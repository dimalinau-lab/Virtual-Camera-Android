package com.example.ccamera

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.ccamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PIP_ACTION_FLIP_CAMERA = "com.example.ccamera.PIP_ACTION_FLIP_CAMERA"
        private const val PIP_ACTION_TOGGLE_MIC = "com.example.ccamera.PIP_ACTION_TOGGLE_MIC"
        private const val PIP_ACTION_STOP = "com.example.ccamera.PIP_ACTION_STOP"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var gestureDetector: GestureDetector
    private lateinit var updateManager: UpdateManager
    private var availableUpdate: UpdateManager.UpdateInfo? = null

    private var cameraService: FloatingCameraService? = null
    private var isServiceBound = false
    private var isFloatingEnabled = true
    private var isNavigatingToSettings = false

    private var isZenUiHidden = false
    private val zenHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val zenHideRunnable = Runnable {
        hideZenUi()
    }

    private val autoBlackoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoBlackoutRunnable = Runnable {
        if (cameraService?.isStreamingActive() == true && binding.blackoutOverlay.visibility != View.VISIBLE) {
            enableBlackout()
            Toast.makeText(this, "Энергосбережение: экран затемнен (коснитесь для пробуждения)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleAutoBlackout() {
        autoBlackoutHandler.removeCallbacks(autoBlackoutRunnable)
        if (cameraService?.isStreamingActive() == true && binding.blackoutOverlay.visibility != View.VISIBLE) {
            autoBlackoutHandler.postDelayed(autoBlackoutRunnable, 30_000L)
        }
    }

    private fun cancelAutoBlackout() {
        autoBlackoutHandler.removeCallbacks(autoBlackoutRunnable)
    }

    private fun toggleZenUi() {
        if (isZenUiHidden) {
            showZenUi()
        } else {
            hideZenUi()
        }
    }

    private fun showZenUi() {
        isZenUiHidden = false
        binding.topBarLayout.animate().alpha(1f).setDuration(250).withStartAction {
            binding.topBarLayout.visibility = View.VISIBLE
        }.start()
        binding.bottomActionBar.animate().alpha(1f).setDuration(250).withStartAction {
            binding.bottomActionBar.visibility = View.VISIBLE
        }.start()
        binding.btnFloatingTogglePill.animate().alpha(1f).setDuration(250).withStartAction {
            binding.btnFloatingTogglePill.visibility = View.VISIBLE
        }.start()

        zenHideHandler.removeCallbacks(zenHideRunnable)
        if (cameraService?.isStreamingActive() == true) {
            zenHideHandler.postDelayed(zenHideRunnable, 3500)
        }
    }

    private fun hideZenUi() {
        isZenUiHidden = true
        binding.topBarLayout.animate().alpha(0f).setDuration(300).withEndAction {
            binding.topBarLayout.visibility = View.INVISIBLE
        }.start()
        binding.bottomActionBar.animate().alpha(0f).setDuration(300).withEndAction {
            binding.bottomActionBar.visibility = View.INVISIBLE
        }.start()
        binding.btnFloatingTogglePill.animate().alpha(0f).setDuration(300).withEndAction {
            binding.btnFloatingTogglePill.visibility = View.INVISIBLE
        }.start()
    }

    private val statusListener = object : FloatingCameraService.ServiceStatusListener {
        override fun onStreamingStatusChanged(isStreaming: Boolean) {
            runOnUiThread {
                updateStreamingStatusUI(isStreaming)
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempRaw / 10f
                val isHot = tempC >= 42.0f
                binding.batteryTempText.text = if (isHot) "🌡️ ${tempC.toInt()}°C 🔥 ПЕРЕГРЕВ" else "🌡️ ${tempC.toInt()}°C"
                binding.batteryTempText.setTextColor(if (isHot) Color.parseColor("#EF4444") else Color.parseColor("#A1A1AA"))
            }
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PIP_ACTION_FLIP_CAMERA -> {
                    cameraService?.switchCamera()
                }
                PIP_ACTION_TOGGLE_MIC -> {
                    val isMuted = cameraService?.toggleMicMute() ?: true
                    updateMuteButtonUI(isMuted)
                    updatePipParams()
                }
                PIP_ACTION_STOP -> {
                    val stopIntent = Intent(this@MainActivity, FloatingCameraService::class.java).apply {
                        action = FloatingCameraService.ACTION_STOP_SERVICE
                    }
                    startService(stopIntent)
                    finish()
                }
            }
        }
    }

    private fun getPipActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val actions = mutableListOf<RemoteAction>()

        val flipIntent = Intent(PIP_ACTION_FLIP_CAMERA).setPackage(packageName)
        val flipPending = PendingIntent.getBroadcast(
            this,
            101,
            flipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val flipIcon = Icon.createWithResource(this, R.drawable.ic_switch_camera)
        actions.add(RemoteAction(flipIcon, "Камера", "Сменить камеру", flipPending))

        val isMuted = cameraService?.isMicMuted() ?: false
        val micIntent = Intent(PIP_ACTION_TOGGLE_MIC).setPackage(packageName)
        val micPending = PendingIntent.getBroadcast(
            this,
            102,
            micIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val micIcon = Icon.createWithResource(this, if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        val micTitle = if (isMuted) "Вкл. микр." else "Выкл. микр."
        actions.add(RemoteAction(micIcon, micTitle, micTitle, micPending))

        val stopIntent = Intent(PIP_ACTION_STOP).setPackage(packageName)
        val stopPending = PendingIntent.getBroadcast(
            this,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        actions.add(RemoteAction(stopIcon, "Стоп", "Остановить камеру", stopPending))

        return actions
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val paramsBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .setActions(getPipActions())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    paramsBuilder.setAutoEnterEnabled(isFloatingEnabled)
                }
                setPictureInPictureParams(paramsBuilder.build())
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления параметров PiP", e)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? FloatingCameraService.LocalBinder
            cameraService = localBinder?.getService()
            isServiceBound = true

            cameraService?.addStatusListener(statusListener)
            setupPreviewSurface()
            cameraService?.startCameraOnce()
            isFloatingEnabled = cameraService?.isFloatingOverlayEnabled() ?: isFloatingEnabled
            updateFloatingModeUI(isFloatingEnabled)
            updateMuteButtonUI(cameraService?.isMicMuted() ?: true)
            updateStreamingStatusUI(cameraService?.isStreamingActive() ?: false)
            updatePipParams()
            Log.i(TAG, "Успешно привязаны к FloatingCameraService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService?.removeStatusListener(statusListener)
            cameraService = null
            isServiceBound = false
            Log.i(TAG, "Отвязаны от FloatingCameraService")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted && audioGranted) {
            startAndBindCameraService()
        } else {
            Toast.makeText(this, "Требуются разрешения на использование камеры и микрофона", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            Toast.makeText(this, "Разрешение получено: режим поверх приложений включен", Toast.LENGTH_SHORT).show()
            isFloatingEnabled = true
            cameraService?.setFloatingOverlayEnabled(true)
            saveFloatingPref(true)
            updateFloatingModeUI(true)
        } else {
            Toast.makeText(this, "Разрешение не предоставлено: режим выключен", Toast.LENGTH_SHORT).show()
            isFloatingEnabled = false
            cameraService?.setFloatingOverlayEnabled(false)
            saveFloatingPref(false)
            updateFloatingModeUI(false)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.w(TAG, "Разрешение на уведомления отклонено пользователем")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Принудительный портретный режим
        try {
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestedOrientation suppressed", e)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Адаптивные отступы Safe Area (Samsung Galaxy S22/S23/S24/Ultra, Dynamic AMOLED, punch-hole и скругления)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            val density = resources.displayMetrics.density
            val minTopPx = (52 * density).toInt()
            val minHorizPx = (24 * density).toInt()

            val safeTop = maxOf(statusBars.top + (8 * density).toInt(), minTopPx)
            val safeLeft = maxOf(statusBars.left, minHorizPx)
            val safeRight = maxOf(statusBars.right, minHorizPx)

            binding.topBarLayout.setPadding(
                safeLeft,
                safeTop,
                safeRight,
                binding.topBarLayout.paddingBottom
            )

            val bottomLp = binding.bottomActionBar.layoutParams as? ViewGroup.MarginLayoutParams
            if (bottomLp != null) {
                val baseMargin = (32 * density).toInt()
                val bottomMargin = maxOf(navBars.bottom + (16 * density).toInt(), baseMargin)
                bottomLp.bottomMargin = bottomMargin
                binding.bottomActionBar.layoutParams = bottomLp

                val pillLp = binding.btnFloatingTogglePill.layoutParams as? ViewGroup.MarginLayoutParams
                if (pillLp != null) {
                    pillLp.bottomMargin = bottomMargin + (56 * density).toInt()
                    binding.btnFloatingTogglePill.layoutParams = pillLp
                }
            }

            windowInsets
        }

        // 1.5. Быстрый жест: двойной тап для переворота камеры и одинарный тап для Zen Mode
        val previewGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                cameraService?.switchCamera()
                Toast.makeText(this@MainActivity, "📷 Камера переключена", Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleZenUi()
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { v, event ->
            previewGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            true
        }

        // 2. Выход из блэкаута по касанию любого места экрана
        binding.blackoutOverlay.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_DOWN) {
                disableBlackout()
                v.performClick()
            }
            true
        }

        binding.btnFlipCamera.setOnClickListener {
            cameraService?.switchCamera()
        }

        binding.btnFloatingMode.setOnClickListener {
            toggleFloatingMode()
        }

        binding.btnFloatingMode.setOnLongClickListener {
            if (isFloatingEnabled) {
                if (Settings.canDrawOverlays(this)) {
                    cameraService?.showFloatingOverlay()
                    moveTaskToBack(true)
                } else {
                    requestOverlayPermission()
                }
            } else {
                Toast.makeText(this, "Включите режим поверх приложений нажатием на кнопку", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.btnFloatingTogglePill.setOnClickListener {
            toggleFloatingMode()
        }

        binding.btnFloatingTogglePill.setOnLongClickListener {
            if (isFloatingEnabled) {
                if (Settings.canDrawOverlays(this)) {
                    cameraService?.showFloatingOverlay()
                    moveTaskToBack(true)
                } else {
                    requestOverlayPermission()
                }
            } else {
                Toast.makeText(this, "Включите режим поверх приложений нажатием на плашку", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.btnEcoBlackout.setOnClickListener {
            val isBlackout = cameraService?.toggleBlackout() ?: false
            if (isBlackout) {
                enableBlackout()
            } else {
                disableBlackout()
            }
        }

        binding.btnMuteMic.setOnClickListener {
            val isMuted = cameraService?.toggleMicMute() ?: true
            updateMuteButtonUI(isMuted)
        }

        // Автообновление приложения
        updateManager = UpdateManager(this)
        checkAppUpdates()

        binding.btnDismissUpdate.setOnClickListener {
            binding.updateBanner.visibility = View.GONE
        }

        binding.updateBanner.setOnClickListener {
            val update = availableUpdate ?: return@setOnClickListener
            startApkUpdate(update)
        }

        val filter = IntentFilter().apply {
            addAction(PIP_ACTION_FLIP_CAMERA)
            addAction(PIP_ACTION_TOGGLE_MIC)
            addAction(PIP_ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, filter)
        }

        val prefs = getSharedPreferences("vcam_camera_prefs", Context.MODE_PRIVATE)
        isFloatingEnabled = prefs.getBoolean("pref_floating_enabled", true)
        updateFloatingModeUI(isFloatingEnabled)

        updateMuteButtonUI(cameraService?.isMicMuted() ?: false)
        checkAndRequestPermissions()
    }

    private fun getSafeDrawable(resId: Int): android.graphics.drawable.Drawable? {
        return try {
            AppCompatResources.getDrawable(this, resId)
        } catch (_: Exception) {
            try {
                ContextCompat.getDrawable(this, resId)
            } catch (_: Exception) {
                AppCompatResources.getDrawable(this, R.drawable.ic_floating_window)
            }
        }
    }

    private fun updateFloatingModeUI(enabled: Boolean) {
        val drawableRes = if (enabled) R.drawable.ic_floating_window else R.drawable.ic_floating_window_off
        val iconDrawable = getSafeDrawable(drawableRes)

        // 1. Информативная плашка с текстом и индикатором статуса
        binding.ivFloatingPillIcon.setImageDrawable(iconDrawable)
        binding.ivFloatingPillIcon.imageTintList = ColorStateList.valueOf(if (enabled) Color.parseColor("#38BDF8") else Color.parseColor("#71717A"))
        binding.tvFloatingPillText.text = if (enabled) "Поверх приложений: ВКЛ" else "Поверх приложений: ВЫКЛ"
        binding.tvFloatingPillText.setTextColor(if (enabled) Color.parseColor("#38BDF8") else Color.parseColor("#A1A1AA"))
        binding.dotFloatingPill.backgroundTintList = ColorStateList.valueOf(if (enabled) Color.parseColor("#10B981") else Color.parseColor("#71717A"))

        // 2. Круглая кнопка в панели действий
        binding.btnFloatingMode.setImageDrawable(iconDrawable)
        binding.btnFloatingMode.imageTintList = ColorStateList.valueOf(if (enabled) Color.parseColor("#38BDF8") else Color.parseColor("#71717A"))
        binding.btnFloatingMode.backgroundTintList = ColorStateList.valueOf(if (enabled) Color.parseColor("#3338BDF8") else Color.parseColor("#26FFFFFF"))
        binding.btnFloatingMode.contentDescription = if (enabled) "Работа поверх приложений: ВКЛ" else "Работа поверх приложений: ВЫКЛ"

        updatePipParams()
    }

    private fun toggleFloatingMode() {
        val newState = !isFloatingEnabled
        if (newState && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Предоставьте разрешение для работы поверх приложений", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }

        isFloatingEnabled = newState
        cameraService?.setFloatingOverlayEnabled(newState)
        saveFloatingPref(newState)
        updateFloatingModeUI(newState)

        if (newState) {
            Toast.makeText(this, "✅ Работа поверх приложений: ВКЛ\nПри сворачивании откроется плавающее окно", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "🛑 Работа поверх приложений: ВЫКЛ\nПри сворачивании приложение выключится", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        isNavigatingToSettings = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun saveFloatingPref(enabled: Boolean) {
        try {
            val prefs = getSharedPreferences("vcam_camera_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("pref_floating_enabled", enabled).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения pref_floating_enabled", e)
        }
    }

    private fun updateMuteButtonUI(isMuted: Boolean) {
        if (isMuted) {
            binding.btnMuteMic.setImageResource(R.drawable.ic_mic_off)
            binding.btnMuteMic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935"))
            binding.btnMuteMic.imageTintList = ColorStateList.valueOf(Color.WHITE)
        } else {
            binding.btnMuteMic.setImageResource(R.drawable.ic_mic)
            binding.btnMuteMic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            binding.btnMuteMic.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        updatePipParams()
    }

    private fun updateStreamingStatusUI(isStreaming: Boolean) {
        if (isStreaming) {
            binding.statusText.text = "В ЭФИРЕ"
            binding.statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            // Zen Mode: автоскрытие рамок управления через 3 секунды после начала стрима
            zenHideHandler.removeCallbacks(zenHideRunnable)
            zenHideHandler.postDelayed(zenHideRunnable, 3000)
            scheduleAutoBlackout()
        } else {
            binding.statusText.text = "ОЖИДАНИЕ ПК"
            binding.statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFC107"))
            zenHideHandler.removeCallbacks(zenHideRunnable)
            cancelAutoBlackout()
            if (binding.blackoutOverlay.visibility == View.VISIBLE) {
                disableBlackout()
            }
            showZenUi()
        }
    }

    private fun enableBlackout() {
        binding.blackoutOverlay.visibility = View.VISIBLE
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
        cancelAutoBlackout()
    }

    override fun onStart() {
        super.onStart()
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && audioGranted) {
            startAndBindCameraService()
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigatingToSettings = false
        if (isServiceBound) {
            cameraService?.startCameraOnce()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isFloatingEnabled) {
            if (Settings.canDrawOverlays(this)) {
                cameraService?.showFloatingOverlay()
            } else {
                enterPipMode()
            }
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val paramsBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .setActions(getPipActions())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    paramsBuilder.setAutoEnterEnabled(true)
                }
                enterPictureInPictureMode(paramsBuilder.build())
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка входа в PiP режим", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            binding.blackoutOverlay.visibility = View.GONE
            binding.topBarLayout.visibility = View.GONE
            binding.bottomActionBar.visibility = View.GONE
            binding.btnFloatingTogglePill.visibility = View.GONE
            supportActionBar?.hide()
        } else {
            binding.topBarLayout.visibility = View.VISIBLE
            binding.bottomActionBar.visibility = View.VISIBLE
            binding.btnFloatingTogglePill.visibility = View.VISIBLE
            supportActionBar?.show()
            updatePipParams()
        }
    }

    private fun setupPreviewSurface(width: Int = 1280, height: Int = 720) {
        binding.viewFinder.post {
            // Находим SurfaceView внутри контейнера viewFinder
            val surfaceView = (binding.viewFinder.getChildAt(0) as? SurfaceView) ?: run {
                // Если контейнер пустой, создаем и добавляем SurfaceView программно
                SurfaceView(this).also { sv ->
                    binding.viewFinder.removeAllViews()
                    binding.viewFinder.addView(sv)
                }
            }

            surfaceView.holder.setKeepScreenOn(true)
            surfaceView.holder.setFixedSize(width, height)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "Экранный SurfaceHolder создан: ${holder.surface}")
                    cameraService?.setPreviewDisplaySurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                    if (holder.surface.isValid) {
                        cameraService?.setPreviewDisplaySurface(holder.surface)
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.i(TAG, "Экранный SurfaceHolder уничтожен")
                    cameraService?.setPreviewDisplaySurface(null)
                }
            })

            if (surfaceView.holder.surface.isValid) {
                cameraService?.setPreviewDisplaySurface(surfaceView.holder.surface)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return
        }
        if (!isFloatingEnabled) {
            Log.i(TAG, "Сворачивание при выключенном режиме поверх приложений -> освобождение камеры и остановка трансляции")
            cameraService?.stopStreamingInternal()
        }
        if (isServiceBound) {
            cameraService?.removeStatusListener(statusListener)
            cameraService?.setPreviewDisplaySurface(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startAndBindCameraService()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (isFloatingEnabled && !Settings.canDrawOverlays(this)) {
            try {
                Toast.makeText(this, "Предоставьте разрешение для оверлей-виджета", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка открытия настроек оверлея", e)
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                isNavigatingToSettings = true
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось открыть диалог оптимизации батареи", e)
            }
        }
    }

    private fun startAndBindCameraService() {
        if (isServiceBound) return
        val serviceIntent = Intent(this, FloatingCameraService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска FloatingCameraService", e)
        }
    }

    private fun disableBlackout() {
        binding.blackoutOverlay.visibility = View.GONE
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        if (cameraService?.isStreamingActive() == true) {
            scheduleAutoBlackout()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (binding.blackoutOverlay.visibility == View.VISIBLE) {
                disableBlackout()
                return true
            } else if (cameraService?.isStreamingActive() == true) {
                scheduleAutoBlackout()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun checkAppUpdates() {
        updateManager.checkForUpdate { info ->
            if (info != null && info.hasUpdate) {
                availableUpdate = info
                val sizeStr = if (info.apkSize > 0) " (${formatSize(info.apkSize)})" else ""
                binding.tvUpdateTitle.text = "✨ Доступно обновление v${info.latestVersion}"
                binding.tvUpdateDesc.text = "Нажмите для загрузки и установки$sizeStr"
                binding.updateBanner.visibility = View.VISIBLE
            }
        }
    }

    private fun startApkUpdate(update: UpdateManager.UpdateInfo) {
        isNavigatingToSettings = true
        binding.updateProgressBar.visibility = View.VISIBLE
        binding.updateProgressBar.progress = 0
        binding.tvUpdateDesc.text = "Загрузка обновления..."
        binding.updateBanner.isClickable = false

        updateManager.downloadAndInstall(
            update,
            onProgress = { progress, downloaded, total ->
                binding.updateProgressBar.progress = progress
                val dlMb = (downloaded / (1024f * 1024f))
                val totMb = (total / (1024f * 1024f))
                binding.tvUpdateDesc.text = String.format("Загрузка: %d%% (%.1f / %.1f MB)", progress, dlMb, totMb)
                if (progress >= 100) {
                    binding.tvUpdateDesc.text = "Запуск установщика..."
                }
            },
            onError = { error ->
                binding.updateProgressBar.visibility = View.GONE
                binding.tvUpdateDesc.text = "Ошибка: $error. Нажмите для повтора"
                binding.updateBanner.isClickable = true
                Toast.makeText(this, "Ошибка обновления: $error", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024f * 1024f)
        return String.format("%.1f MB", mb)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoBlackout()
        zenHideHandler.removeCallbacks(zenHideRunnable)
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}

        if (isServiceBound) {
            cameraService?.setPreviewDisplaySurface(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
