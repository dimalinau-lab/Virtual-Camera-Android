package com.example.ccamera

import android.Manifest
import android.app.AlertDialog
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.LinearLayout
import android.widget.TextView
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var isAutoDimmingEnabled = false
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var hasInitialAccel = false
    private var lastMotionTime = 0L

    private val motionSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                if (!hasInitialAccel) {
                    lastAccelX = x
                    lastAccelY = y
                    lastAccelZ = z
                    hasInitialAccel = true
                    return
                }
                val delta = Math.abs(x - lastAccelX) + Math.abs(y - lastAccelY) + Math.abs(z - lastAccelZ)
                lastAccelX = x
                lastAccelY = y
                lastAccelZ = z

                if (delta > 0.40f) {
                    onDeviceMoved()
                }
            } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val rotSpeed = Math.sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
                if (rotSpeed > 0.18f) {
                    onDeviceMoved()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun onDeviceMoved() {
        val now = System.currentTimeMillis()
        if (now - lastMotionTime < 600) return
        lastMotionTime = now

        if (binding.blackoutOverlay.visibility == View.VISIBLE) {
            disableBlackout()
            Toast.makeText(this, getString(R.string.motion_detected), Toast.LENGTH_SHORT).show()
        } else if (isAutoDimmingEnabled && cameraService?.isStreamingActive() == true) {
            scheduleAutoBlackout()
        }
    }

    private fun startMotionSensor() {
        if (sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }
        val sm = sensorManager ?: return
        accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasInitialAccel = false

        accelerometer?.let {
            sm.registerListener(motionSensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sm.registerListener(motionSensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopMotionSensor() {
        try {
            sensorManager?.unregisterListener(motionSensorListener)
        } catch (_: Exception) {}
    }

    private val autoBlackoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoBlackoutRunnable = Runnable {
        if (isAutoDimmingEnabled && cameraService?.isStreamingActive() == true && binding.blackoutOverlay.visibility != View.VISIBLE) {
            enableBlackout()
            Toast.makeText(this, getString(R.string.eco_blackout_tip), Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleAutoBlackout() {
        autoBlackoutHandler.removeCallbacks(autoBlackoutRunnable)
    }

    private fun cancelAutoBlackout() {
        autoBlackoutHandler.removeCallbacks(autoBlackoutRunnable)
    }

    private fun updateEcoBlackoutUI() {
        binding.btnEcoBlackout.imageTintList = ColorStateList.valueOf(
            if (isAutoDimmingEnabled) Color.parseColor("#10B981") else Color.parseColor("#71717A")
        )
        binding.btnEcoBlackout.backgroundTintList = ColorStateList.valueOf(
            if (isAutoDimmingEnabled) Color.parseColor("#3310B981") else Color.parseColor("#26FFFFFF")
        )
    }

    private var isBottomPanelHidden = false

    private fun hideBottomPanel() {
        if (isBottomPanelHidden) return
        isBottomPanelHidden = true
        binding.bottomPanelContainer.animate()
            .translationY(300f)
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                binding.bottomPanelContainer.visibility = View.GONE
                binding.btnShowBottomPanel.visibility = View.VISIBLE
                binding.btnShowBottomPanel.alpha = 0f
                binding.btnShowBottomPanel.translationY = 40f
                binding.btnShowBottomPanel.animate().translationY(0f).alpha(1f).setDuration(180).start()
            }
            .start()
    }

    private fun showBottomPanel() {
        if (!isBottomPanelHidden) return
        isBottomPanelHidden = false
        binding.btnShowBottomPanel.animate().alpha(0f).setDuration(150).withEndAction {
            binding.btnShowBottomPanel.visibility = View.GONE
        }.start()

        binding.bottomPanelContainer.visibility = View.VISIBLE
        binding.bottomPanelContainer.translationY = 300f
        binding.bottomPanelContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .start()
    }

    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private fun showZenUi() {
        isZenUiHidden = false
        binding.topBarLayout.animate().alpha(1f).setDuration(220).withStartAction {
            binding.topBarLayout.visibility = View.VISIBLE
        }.start()
        if (!isBottomPanelHidden) {
            binding.bottomPanelContainer.animate().alpha(1f).translationY(0f).setDuration(220).withStartAction {
                binding.bottomPanelContainer.visibility = View.VISIBLE
            }.start()
        } else {
            binding.btnShowBottomPanel.visibility = View.VISIBLE
        }
    }

    private fun hideZenUi() {
        isZenUiHidden = true
        binding.topBarLayout.animate().alpha(0f).setDuration(220).withEndAction {
            binding.topBarLayout.visibility = View.INVISIBLE
        }.start()
        binding.bottomPanelContainer.animate().alpha(0f).setDuration(220).withEndAction {
            binding.bottomPanelContainer.visibility = View.INVISIBLE
        }.start()
        binding.btnShowBottomPanel.visibility = View.GONE
    }

    fun updateLensBar() {
        val service = cameraService ?: return
        val status = service.getStatus()
        val lenses = status.availableLenses
        val currentLens = status.currentLens
        val currentUnit = status.currentCameraUnit
        val currentFacing = service.currentCameraFacing

        binding.lensBarLayout.post {
            binding.lensBarLayout.removeAllViews()
            if (lenses.isEmpty()) {
                binding.lensBarLayout.visibility = View.GONE
                return@post
            }
            binding.lensBarLayout.visibility = View.VISIBLE

            for (lens in lenses) {
                val tv = TextView(this).apply {
                    val isSelected = when {
                        lens.equals("front", ignoreCase = true) || lens.equals("селфи", ignoreCase = true) ->
                            currentFacing == "front"
                        currentFacing == "front" -> false
                        lens == "0.5x" -> (currentLens == "0.5x" || currentUnit == "0.5x" || currentUnit == "50" || currentUnit == "58")
                        lens == "macro" -> (currentLens == "macro" || currentUnit == "macro" || currentUnit == "54")
                        lens == "1x" -> (currentLens == "1x" || currentUnit == "0" || currentUnit == "1.0x" || (currentLens.isEmpty() && currentUnit != "0.5x" && currentUnit != "macro"))
                        else -> lens.equals(currentLens, ignoreCase = true)
                    }
                    text = when (lens.lowercase()) {
                        "macro" -> getString(R.string.lens_macro)
                        "front", "селфи", "фронт" -> getString(R.string.lens_front)
                        else -> lens
                    }
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    setPadding(26, 10, 26, 10)
                    setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#D4D4D8"))
                    if (isSelected) {
                        setBackgroundResource(R.drawable.bg_overlay_dot_core)
                        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E11D48")) // Rose-600
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    } else {
                        setBackgroundColor(Color.TRANSPARENT)
                        setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(6, 0, 6, 0)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        service.setLens(lens)
                        updateLensBar()
                    }
                }
                binding.lensBarLayout.addView(tv)
            }
        }
    }

    private val statusListener = object : FloatingCameraService.ServiceStatusListener {
        override fun onStreamingStatusChanged(isStreaming: Boolean) {
            runOnUiThread {
                updateStreamingStatusUI(isStreaming)
            }
        }

        override fun onStatusUpdated() {
            runOnUiThread {
                updateLensBar()
                cameraService?.let { service ->
                    updateFlipCameraUI(service.currentCameraFacing == "front")
                    updateTorchUI(service.isTorchOn)
                }
            }
        }

        override fun onBlackoutToggled(isBlackout: Boolean) {
            runOnUiThread {
                if (isBlackout) {
                    enableBlackout()
                } else {
                    disableBlackout()
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempRaw / 10f
                val isHot = tempC >= 42.0f
                val overheatStr = getString(R.string.battery_overheat)
                binding.batteryTempText.text = if (isHot) "🌡️ ${tempC.toInt()}°C $overheatStr" else "🌡️ ${tempC.toInt()}°C"
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
        actions.add(RemoteAction(flipIcon, getString(R.string.action_camera), getString(R.string.action_camera), flipPending))

        val isMuted = cameraService?.isMicMuted() ?: false
        val micIntent = Intent(PIP_ACTION_TOGGLE_MIC).setPackage(packageName)
        val micPending = PendingIntent.getBroadcast(
            this,
            102,
            micIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val micIcon = Icon.createWithResource(this, if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        val micTitle = getString(R.string.action_mic)
        actions.add(RemoteAction(micIcon, micTitle, micTitle, micPending))

        val stopIntent = Intent(PIP_ACTION_STOP).setPackage(packageName)
        val stopPending = PendingIntent.getBroadcast(
            this,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        actions.add(RemoteAction(stopIcon, getString(R.string.action_close), getString(R.string.action_close), stopPending))

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
            updateAutoFramingUI(cameraService?.isAutoFramingEnabled ?: false)
            updateStabilizationUI(cameraService?.isStabilization ?: false, cameraService?.hasOis ?: false, cameraService?.hasEis ?: false)
            updateFlipCameraUI(cameraService?.currentCameraFacing == "front")
            updateTorchUI(cameraService?.isTorchOn ?: false)
            updateLensBar()
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
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            Toast.makeText(this, getString(R.string.permission_granted_floating), Toast.LENGTH_SHORT).show()
            isFloatingEnabled = true
            cameraService?.setFloatingOverlayEnabled(true)
            saveFloatingPref(true)
            updateFloatingModeUI(true)
        } else {
            Toast.makeText(this, getString(R.string.permission_denied_floating), Toast.LENGTH_SHORT).show()
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
            }

            windowInsets
        }

        // 1.5. Быстрый жест: одинарный тап для возврата Zen UI (двойной тап отключен по запросу пользователя)
        val previewGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isZenUiHidden) {
                    showZenUi()
                }
                return true
            }
        })

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val service = cameraService ?: return false
                val status = service.getStatus()
                val factor = detector.scaleFactor
                val targetZoom = (status.currentZoom * factor).coerceIn(status.minZoom, status.maxZoom)
                service.setZoomRatio(targetZoom)
                updateLensBar()
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            previewGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            true
        }

        // 2. Выход из блэкаута по касанию любого места экрана
        binding.blackoutOverlay.setOnClickListener {
            disableBlackout()
        }
        binding.blackoutOverlay.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_DOWN) {
                disableBlackout()
                v.performClick()
            }
            true
        }

        binding.btnLanguageSelector.setOnClickListener {
            showLanguageDialog()
        }
        updateLanguageSelectorUI()

        binding.btnHideBottomPanel.setOnClickListener {
            hideBottomPanel()
        }

        binding.btnShowBottomPanel.setOnClickListener {
            showBottomPanel()
        }

        binding.btnFlipCamera.setOnClickListener {
            val service = cameraService ?: return@setOnClickListener
            service.switchCamera()
            val isFront = (service.currentCameraFacing == "front")
            updateFlipCameraUI(isFront)
            binding.viewFinder.postDelayed({
                updateLensBar()
                updateFlipCameraUI(service.currentCameraFacing == "front")
                updateTorchUI(service.isTorchOn)
            }, 650)
            Toast.makeText(this, if (isFront) getString(R.string.cam_front) else getString(R.string.cam_back), Toast.LENGTH_SHORT).show()
        }

        binding.btnTorch.setOnClickListener {
            val service = cameraService ?: return@setOnClickListener
            if (service.currentCameraFacing == "front") {
                Toast.makeText(this, getString(R.string.torch_back_only), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            service.toggleTorch()
            updateTorchUI(service.isTorchOn)
            Toast.makeText(this, if (service.isTorchOn) getString(R.string.torch_on) else getString(R.string.torch_off), Toast.LENGTH_SHORT).show()
        }

        binding.btnAutoFraming.setOnClickListener {
            val service = cameraService ?: return@setOnClickListener
            val newState = !service.isAutoFramingEnabled
            service.setAutoFramingEnabled(newState)
            updateAutoFramingUI(newState)
            Toast.makeText(this, if (newState) getString(R.string.autoframing_on) else getString(R.string.autoframing_off), Toast.LENGTH_SHORT).show()
        }

        binding.btnStabilization.setOnClickListener {
            val service = cameraService ?: return@setOnClickListener
            val newState = !service.isStabilization
            service.setStabilizationEnabled(newState)
            updateStabilizationUI(newState, service.hasOis, service.hasEis)
            val tag = when {
                service.hasOis && service.hasEis -> " (OIS + EIS)"
                service.hasOis -> " (OIS)"
                service.hasEis -> " (EIS)"
                else -> ""
            }
            Toast.makeText(this, if (newState) getString(R.string.stabilization_on, tag) else getString(R.string.stabilization_off), Toast.LENGTH_SHORT).show()
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

        binding.btnEcoBlackout.setOnClickListener {
            cameraService?.toggleBlackout()
        }

        binding.btnEcoBlackout.setOnLongClickListener {
            isAutoDimmingEnabled = !isAutoDimmingEnabled
            updateEcoBlackoutUI()
            if (isAutoDimmingEnabled) {
                scheduleAutoBlackout()
                Toast.makeText(this, getString(R.string.gyro_eco_on), Toast.LENGTH_SHORT).show()
            } else {
                cancelAutoBlackout()
                if (binding.blackoutOverlay.visibility == View.VISIBLE) {
                    disableBlackout()
                }
                Toast.makeText(this, getString(R.string.gyro_eco_off), Toast.LENGTH_SHORT).show()
            }
            true
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
        updateEcoBlackoutUI()
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

        // Круглая кнопка в панели действий
        binding.btnFloatingMode.setImageDrawable(iconDrawable)
        binding.btnFloatingMode.imageTintList = ColorStateList.valueOf(if (enabled) Color.parseColor("#38BDF8") else Color.parseColor("#71717A"))
        binding.btnFloatingMode.backgroundTintList = ColorStateList.valueOf(if (enabled) Color.parseColor("#3338BDF8") else Color.parseColor("#26FFFFFF"))
        binding.btnFloatingMode.contentDescription = if (enabled) "Работа поверх приложений: ВКЛ" else "Работа поверх приложений: ВЫКЛ"

        updatePipParams()
    }

    private fun toggleFloatingMode() {
        val newState = !isFloatingEnabled
        if (newState && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.grant_overlay_permission), Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }

        isFloatingEnabled = newState
        cameraService?.setFloatingOverlayEnabled(newState)
        saveFloatingPref(newState)
        updateFloatingModeUI(newState)

        if (newState) {
            Toast.makeText(this, getString(R.string.floating_on_tip), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.floating_off_tip), Toast.LENGTH_SHORT).show()
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

    private fun updateAutoFramingUI(enabled: Boolean) {
        binding.btnAutoFraming.imageTintList = ColorStateList.valueOf(
            if (enabled) Color.parseColor("#38BDF8") else Color.parseColor("#A1A1AA")
        )
        binding.btnAutoFraming.backgroundTintList = ColorStateList.valueOf(
            if (enabled) Color.parseColor("#3338BDF8") else Color.parseColor("#26FFFFFF")
        )
    }

    private fun updateStabilizationUI(enabled: Boolean, hasOis: Boolean = true, hasEis: Boolean = true) {
        val activeColor = if (hasOis || hasEis) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        binding.btnStabilization.imageTintList = ColorStateList.valueOf(
            if (enabled) activeColor else Color.parseColor("#A1A1AA")
        )
        binding.btnStabilization.backgroundTintList = ColorStateList.valueOf(
            if (enabled) Color.parseColor("#3310B981") else Color.parseColor("#26FFFFFF")
        )
    }

    private fun updateFlipCameraUI(isFront: Boolean) {
        binding.btnFlipCamera.imageTintList = ColorStateList.valueOf(
            if (isFront) Color.parseColor("#38BDF8") else Color.parseColor("#FFFFFF")
        )
        binding.btnFlipCamera.backgroundTintList = ColorStateList.valueOf(
            if (isFront) Color.parseColor("#3338BDF8") else Color.parseColor("#26FFFFFF")
        )
    }

    private fun updateTorchUI(isTorchOn: Boolean) {
        binding.btnTorch.post {
            binding.btnTorch.imageTintList = ColorStateList.valueOf(
                if (isTorchOn) Color.parseColor("#FBBF24") else Color.parseColor("#A1A1AA")
            )
            binding.btnTorch.backgroundTintList = ColorStateList.valueOf(
                if (isTorchOn) Color.parseColor("#33FBBF24") else Color.parseColor("#26FFFFFF")
            )
        }
    }

    private fun updateStreamingStatusUI(isStreaming: Boolean) {
        if (isStreaming) {
            binding.statusText.setText(R.string.status_streaming)
            binding.statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            showZenUi()
        } else {
            binding.statusText.setText(R.string.status_waiting_pc)
            binding.statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFC107"))
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
        lp.screenBrightness = 0.0f
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
        startMotionSensor()
        updateEcoBlackoutUI()
        if (isAutoDimmingEnabled && cameraService?.isStreamingActive() == true) {
            scheduleAutoBlackout()
        }
    }

    override fun onPause() {
        super.onPause()
        stopMotionSensor()
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
            supportActionBar?.hide()
        } else {
            binding.topBarLayout.visibility = View.VISIBLE
            binding.bottomActionBar.visibility = View.VISIBLE
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
            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val (bufW, bufH) = if (isPortrait) Pair(height, width) else Pair(width, height)
            surfaceView.holder.setFixedSize(bufW, bufH)
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
        cameraService?.setBlackoutEnabled(false)
        if (cameraService?.isStreamingActive() == true && isAutoDimmingEnabled) {
            scheduleAutoBlackout()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (binding.blackoutOverlay.visibility == View.VISIBLE) {
                disableBlackout()
                return true
            }
            if (isZenUiHidden) {
                showZenUi()
            }
            if (cameraService?.isStreamingActive() == true) {
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
                binding.tvUpdateTitle.text = getString(R.string.update_available_title, info.latestVersion)
                binding.tvUpdateDesc.text = getString(R.string.update_tap_to_install, sizeStr)
                binding.updateBanner.visibility = View.VISIBLE
            }
        }
    }

    private fun startApkUpdate(update: UpdateManager.UpdateInfo) {
        isNavigatingToSettings = true
        binding.updateProgressBar.visibility = View.VISIBLE
        binding.updateProgressBar.progress = 0
        binding.tvUpdateDesc.setText(R.string.update_downloading)
        binding.updateBanner.isClickable = false

        updateManager.downloadAndInstall(
            update,
            onProgress = { progress, downloaded, total ->
                binding.updateProgressBar.progress = progress
                val dlMb = (downloaded / (1024f * 1024f))
                val totMb = (total / (1024f * 1024f))
                binding.tvUpdateDesc.text = getString(R.string.update_download_progress, progress, dlMb, totMb)
                if (progress >= 100) {
                    binding.tvUpdateDesc.setText(R.string.update_launching)
                }
            },
            onError = { error ->
                binding.updateProgressBar.visibility = View.GONE
                binding.tvUpdateDesc.text = getString(R.string.update_error, error)
                binding.updateBanner.isClickable = true
                Toast.makeText(this, getString(R.string.update_error_toast, error), Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024f * 1024f)
        return String.format("%.1f MB", mb)
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_ru),
            getString(R.string.lang_uk),
            getString(R.string.lang_en)
        )
        val current = LocaleHelper.getSavedLanguage(this)
        val selectedIndex = when (current) {
            "ru" -> 1
            "uk" -> 2
            "en" -> 3
            else -> 0
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val newLang = when (which) {
                    1 -> "ru"
                    2 -> "uk"
                    3 -> "en"
                    else -> "system"
                }
                LocaleHelper.setLanguage(this, newLang)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLanguageSelectorUI() {
        binding.tvLanguageCode.text = LocaleHelper.getDisplayCode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMotionSensor()
        cancelAutoBlackout()
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
