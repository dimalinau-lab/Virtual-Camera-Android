package com.example.ccamera

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import org.json.JSONObject
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import com.example.ccamera.gl.CameraGlRenderer
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.hardware.SensorManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class FloatingCameraService : Service(),
    LifecycleOwner,
    RawH265Streamer.StatusListener,
    ControlServer.ControlCallback {

    companion object {
        private const val TAG = "FloatingCameraService"
        private const val CHANNEL_ID = "virtual_cam_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.example.ccamera.ACTION_STOP"
        const val ACTION_STOP_SERVICE = "com.example.ccamera.ACTION_STOP_SERVICE"
        const val ACTION_FLIP_CAMERA = "com.example.ccamera.ACTION_FLIP_CAMERA"
        const val ACTION_TOGGLE_MIC = "com.example.ccamera.ACTION_TOGGLE_MIC"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FloatingCameraService = this@FloatingCameraService
    }

    private var controlServer: ControlServer? = null
    private var streamer: RawH265Streamer? = null
    private var audioStreamer: AudioStreamer? = null
    private var glRenderer: CameraGlRenderer? = null
    @Volatile
    private var isAutoFraming: Boolean = false
    val isAutoFramingEnabled: Boolean
        get() = isAutoFraming
    val currentCameraFacing: String
        get() = currentCameraFacingName
    @Volatile
    var useGlPipeline: Boolean = false

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraCaptureCallback: CameraCaptureSession.CaptureCallback? = null
    private var displaySurface: Surface? = null
    private var activityDisplaySurface: Surface? = null
    private var overlayDisplaySurface: Surface? = null

    private val cameraLock = Any()
    @Volatile
    private var isCameraBusy = false

    @Volatile
    private var isCameraInitialized = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var overlayController: OverlayController? = null
    private var blackoutOverlayView: View? = null
    private var isBlackoutEnabled = false
    private var isFloatingOverlayEnabled = true
    private var udpBeaconBroadcaster: UdpBeaconBroadcaster? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private var currentCameraFacingName: String = "back"

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    var isTorchOn: Boolean = false
    private var isStreaming: Boolean = false
    private var isHeatThrottled: Boolean = false
    private var preHeatFps: Int = 60
    private var preHeatBitrate: Int = 12_000_000

    private val heatGuardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val tempC = tempTenths / 10.0f
            checkHeatGuard(tempC)
        }
    }

    private fun checkHeatGuard(tempC: Float) {
        if (tempC <= 0f) return
        if (tempC >= 43.5f && !isHeatThrottled) {
            isHeatThrottled = true
            preHeatFps = targetFps
            preHeatBitrate = targetBitrate
            Log.w(TAG, "[HEAT GUARD] Перегрев батареи: ${tempC}°C >= 43.5°C! Включение термозащиты (FPS -> 30, Bitrate -> 6 Mbps)")
            val newFps = minOf(targetFps, 30)
            val newBitrate = minOf(targetBitrate, 6_000_000)
            if (newFps != targetFps || newBitrate != targetBitrate) {
                applyStreamConfig(if (targetWidth >= 1920) "1080p" else "720p", newFps, newBitrate, persist = false)
            }
        } else if (tempC <= 40.5f && isHeatThrottled) {
            isHeatThrottled = false
            Log.i(TAG, "[HEAT GUARD] Температура нормализована: ${tempC}°C <= 40.5°C. Восстановление исходных параметров: $preHeatFps FPS, $preHeatBitrate bps")
            applyStreamConfig(if (targetWidth >= 1920) "1080p" else "720p", preHeatFps, preHeatBitrate, persist = false)
        }
    }
    private var currentOrientationMode: String = "auto"

    @Volatile
    private var currentZoomRatio: Float = 1.0f
    @Volatile
    private var currentLensName: String = "1x"
    private var minZoomRatio: Float = 1.0f
    private var maxZoomRatio: Float = 8.0f
    var ultraWideCameraId: String? = null
    var ultraWidePhysicalId: String? = null
    var macroCameraId: String? = null
    var telePhysicalId: String? = null
    var teleCameraId: String? = null
    var mainCameraId: String = "0"
    var hasWideCameraHardware: Boolean = false
    var hasMacroCameraHardware: Boolean = false
    var activePhysicalCameraId: String? = null
    var currentExplicitCameraId: String? = null
    @Volatile
    var isStabilization: Boolean = false
    @Volatile
    var hasOis: Boolean = false
    @Volatile
    var hasEis: Boolean = false

    data class CameraUnitInfo(
        val id: String,
        val name: String,
        val type: String,
        val facing: String,
        val focalLengthMm: Float,
        val hasOis: Boolean,
        val isPhysical: Boolean
    )
    private var zoomRatioRange: Range<Float>? = null
    private var sensorCropRegion: Rect? = null
    private var sensorOrientation: Int = 90
    private var currentCaptureRequestBuilder: CaptureRequest.Builder? = null

    @Volatile
    private var isManualExposure: Boolean = false
    @Volatile
    private var currentIso: Int = 400
    @Volatile
    private var currentExposureTimeNs: Long = 16_666_667L
    private var minIso: Int = 100
    private var maxIso: Int = 3200
    private var minExposureNs: Long = 100_000L
    private var maxExposureNs: Long = 100_000_000L

    @Volatile
    private var isManualFocus: Boolean = false
    @Volatile
    private var currentFocusDistance: Float = 0.0f
    private var maxFocusDistance: Float = 10.0f

    @Volatile
    private var currentAwbMode: String = "auto"

    private var activeAfModes: IntArray? = null
    private var activeAeModes: IntArray? = null
    private var activeAwbModes: IntArray? = null

    private var targetWidth: Int = 1920
    private var targetHeight: Int = 1080
    private var targetFps: Int = 60
    private var userConfiguredFps: Int = 60
    private var targetBitrate: Int = 5_500_000
    @Volatile
    private var activeMaxFps: Int = 60
    @Volatile
    private var activeSupportedFps: List<Int> = listOf(30, 60)
    @Volatile
    private var activeFpsRangeStr: String = "[60, 60]"

    private var isActivityInForeground = false
    @Volatile
    private var isSwitching = false

    private var lastStreamConfigTime = 0L

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private fun startBackgroundThread() {
        if (cameraThread == null || cameraThread?.isAlive != true) {
            val thread = HandlerThread("CameraBackgroundThread").apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
        }
    }

    @Synchronized
    private fun getCameraHandler(): Handler {
        startBackgroundThread()
        return cameraHandler!!
    }

    private fun loadCameraPrefs() {
        try {
            val prefs = getSharedPreferences("vcam_camera_prefs", Context.MODE_PRIVATE)
            targetWidth = prefs.getInt("target_width", 1920)
            targetHeight = prefs.getInt("target_height", 1080)
            userConfiguredFps = prefs.getInt("user_configured_fps", 60)
            targetFps = prefs.getInt("target_fps", userConfiguredFps)
            targetBitrate = prefs.getInt("target_bitrate", 5_500_000)
            currentCameraFacingName = prefs.getString("camera_facing", "back") ?: "back"
            isFloatingOverlayEnabled = prefs.getBoolean("pref_floating_enabled", true)
            activeMaxFps = 60
            activeSupportedFps = listOf(30, 60)
            activeFpsRangeStr = "[60, 60]"
            Log.i("ROUTING_DEBUG", "loadCameraPrefs: ${targetWidth}x${targetHeight} @ ${targetFps}fps (userConfigured=$userConfiguredFps), facing=$currentCameraFacingName, floating=$isFloatingOverlayEnabled")
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "loadCameraPrefs error", e)
        }
    }

    private fun saveCameraPrefs() {
        try {
            val prefs = getSharedPreferences("vcam_camera_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("target_width", targetWidth)
                .putInt("target_height", targetHeight)
                .putInt("user_configured_fps", userConfiguredFps)
                .putInt("target_fps", targetFps)
                .putInt("target_bitrate", targetBitrate)
                .putString("camera_facing", currentCameraFacingName)
                .putBoolean("pref_floating_enabled", isFloatingOverlayEnabled)
                .apply()
            Log.i("ROUTING_DEBUG", "saveCameraPrefs: ${targetWidth}x${targetHeight} @ ${targetFps}fps, facing=$currentCameraFacingName, floating=$isFloatingOverlayEnabled")
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "saveCameraPrefs error", e)
        }
    }

    private fun initControllersIfNeeded() {
        if (overlayController == null) {
            overlayController = OverlayController(this, object : OverlayController.OverlayListener {
                override fun onSurfaceAvailable(surface: Surface) {
                    Log.i("ROUTING_DEBUG", "Overlay surface available: $surface")
                    overlayDisplaySurface = surface
                    if (activityDisplaySurface == null) {
                        displaySurface = surface
                        updateSessionTargets()
                    }
                }

                override fun onSurfaceDestroyed() {
                    Log.i("ROUTING_DEBUG", "Overlay surface destroyed")
                    if (displaySurface == overlayDisplaySurface) {
                        displaySurface = null
                        updateSessionTargets()
                    }
                    overlayDisplaySurface = null
                }

                override fun onFlipCamera() {
                    switchCamera()
                }

                override fun onToggleMute(): Boolean {
                    val isMuted = toggleMicMute()
                    updateNotification(getNotificationStatusText())
                    return isMuted
                }

                override fun onExpandToApp() {
                    try {
                        val intent = Intent(this@FloatingCameraService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка перехода в полноэкранный режим", e)
                    }
                }

                override fun onToggleEco() {
                    toggleBlackout()
                }

                override fun onCloseService() {
                    stopServiceInternal()
                }
            })
        }

        acquireLocks()
        startControlServer()

        if (udpBeaconBroadcaster == null) {
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL
            udpBeaconBroadcaster = UdpBeaconBroadcaster(deviceId).apply {
                start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        loadCameraPrefs()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        initControllersIfNeeded()
        startForegroundWithStatus(getNotificationStatusText())
        registerReceiver(heatGuardReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startOrientationDetection()

        Log.i(TAG, "FloatingCameraService создан, UDP маяк и Foreground запущены")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE, ACTION_STOP -> {
                Log.i(TAG, "Получен запрос на остановку сервиса через Intent")
                stopServiceInternal()
                return START_NOT_STICKY
            }
            ACTION_FLIP_CAMERA -> {
                Log.i(TAG, "Получен запрос смены камеры из Notification/PiP")
                switchCamera()
                return START_STICKY
            }
            ACTION_TOGGLE_MIC -> {
                Log.i(TAG, "Получен запрос переключения микрофона из Notification/PiP")
                val isMuted = toggleMicMute()
                overlayController?.setMicMuted(isMuted)
                updateNotification(getNotificationStatusText())
                return START_STICKY
            }
        }

        val hasCameraPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPerm) {
            Log.w(TAG, "Нет разрешения CAMERA при запуске сервиса -> остановка во избежание ForegroundServiceSecurityException")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForegroundWithStatus(getNotificationStatusText())
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка startForegroundWithStatus в onStartCommand", e)
        }

        initControllersIfNeeded()
        startCameraOnce()
        return START_STICKY
    }

    fun stopSelfGracefully() {
        try {
            stopServiceInternal()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка stopSelfGracefully", e)
        }
    }

    private fun stopServiceInternal() {
        try {
            disableBlackout()
            overlayController?.hideOverlay()
            overlayController = null
            udpBeaconBroadcaster?.stop()
            udpBeaconBroadcaster = null
            stopStreamingInternal(isShuttingDown = true)
            controlServer?.stop()
            controlServer = null
            releaseLocks()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка stopServiceInternal", e)
        }
    }

    fun startCameraOnce() {
        if (isCameraInitialized) return
        isCameraInitialized = true
        Log.i("ROUTING_DEBUG", "startCameraOnce: единоразовый запуск камеры и кодека")
        startCameraAndStreamerInternal()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Приложение смахнуто из недавних задач (onTaskRemoved), завершаем сервис и освобождаем камеру")
        stopServiceInternal()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    // --- Управление фоновыми блокировками и уведомлениями ---

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VCam::StreamLock").apply {
                    acquire(12 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VCam::WifiLock").apply {
                    acquire()
                }
            }
            if (multicastLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wm.createMulticastLock("VCam::MulticastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
            Log.i(TAG, "WakeLock, WifiLock и MulticastLock успешно зафиксированы")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка захвата WakeLock / WifiLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null

            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null

            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
            Log.i(TAG, "WakeLock и WifiLock освобождены")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка освобождения WakeLock / WifiLock", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VirtualCam Native Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновая трансляция камеры и управляющий сервер"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun getNotificationStatusText(): String {
        return if (isStreaming) {
            getString(R.string.notif_streaming, targetWidth, targetHeight)
        } else {
            getString(R.string.notif_waiting)
        }
    }

    private fun buildNotificationBuilder(statusText: String): NotificationCompat.Builder {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val flipIntent = Intent(this, FloatingCameraService::class.java).apply {
            action = ACTION_FLIP_CAMERA
        }
        val flipPendingIntent = PendingIntent.getService(
            this,
            2,
            flipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val micIntent = Intent(this, FloatingCameraService::class.java).apply {
            action = ACTION_TOGGLE_MIC
        }
        val micPendingIntent = PendingIntent.getService(
            this,
            3,
            micIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingCameraService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMuted = audioStreamer?.isMuted ?: false
        val micTitle = getString(R.string.action_mic)
        val micIcon = if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtualCam Native")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_switch_camera, getString(R.string.action_camera), flipPendingIntent)
            .addAction(micIcon, micTitle, micPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_close), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }

    private fun startForegroundWithStatus(statusText: String) {
        createNotificationChannel()
        val notification = buildNotificationBuilder(statusText).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("MissingPermission")
    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotificationBuilder(statusText).build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    // --- Управление Blackout (затемнение экрана) ---

    fun toggleBlackout(): Boolean {
        setBlackoutEnabled(!isBlackoutEnabled)
        return isBlackoutEnabled
    }

    fun setBlackoutEnabled(enabled: Boolean) {
        if (isBlackoutEnabled == enabled) return
        isBlackoutEnabled = enabled
        Log.i(TAG, "setBlackoutEnabled: enabled=$enabled, listeners=${statusListeners.size}")
        notifyBlackoutToggled(enabled)

        if (statusListeners.isNotEmpty()) {
            // Foreground activity handles blackout in foreground window with direct screenBrightness = 0.0f
            removeServiceOverlayBlackout()
            return
        }

        // Fallback for background / floating overlay mode
        if (enabled) {
            showServiceOverlayBlackout()
        } else {
            removeServiceOverlayBlackout()
        }
    }

    private fun showServiceOverlayBlackout() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Нет разрешения SYSTEM_ALERT_WINDOW для отображения блэкаута")
            return
        }
        if (blackoutOverlayView != null) return

        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
            }

            val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    setBlackoutEnabled(false)
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    setBlackoutEnabled(false)
                    return true
                }
            })

            view.setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) {
                    v.performClick()
                }
                true
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            ).apply {
                screenBrightness = 0.0f
            }

            windowManager.addView(view, params)
            blackoutOverlayView = view
            Log.i(TAG, "Blackout overlay включен (background/floating)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка включения Blackout overlay", e)
        }
    }

    private fun removeServiceOverlayBlackout() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            blackoutOverlayView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            }
            blackoutOverlayView = null
            Log.i(TAG, "Blackout overlay выключен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка выключения Blackout overlay", e)
        }
    }

    private fun disableBlackout() {
        setBlackoutEnabled(false)
    }

    // --- Управление сервером, кодеком и камерой ---

    private fun startControlServer() {
        if (controlServer != null) return
        try {
            controlServer = ControlServer(port = 8080, context = this, callback = this).apply {
                start()
            }
            Log.i(TAG, "ControlServer запущен на порту 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска ControlServer", e)
        }
    }

    fun startCameraAndStreamerInternal() {
        Log.i("ROUTING_DEBUG", "startCameraAndStreamerInternal: targetWidth=$targetWidth, targetHeight=$targetHeight, targetFps=$targetFps")
        if (audioStreamer == null) {
            try {
                audioStreamer = AudioStreamer(8555).apply {
                    audioSink = { pcm, size, ptsUs ->
                        streamer?.enqueueAudio(pcm, size, ptsUs)
                    }
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации AudioStreamer", e)
            }
        }
        var activeStreamer = streamer
        if (activeStreamer == null) {
            try {
                activeStreamer = RawH265Streamer(
                    port = 8554,
                    width = targetWidth,
                    height = targetHeight,
                    bitRate = targetBitrate,
                    frameRate = targetFps,
                    listener = this
                ).apply {
                    start()
                }
                streamer = activeStreamer
                isStreaming = true
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации RawH265Streamer", e)
                overlayController?.setStatus(OverlayController.Status.ERROR)
            }
        } else {
            if (activeStreamer.inputSurface == null || !activeStreamer.inputSurface!!.isValid) {
                Log.i("ROUTING_DEBUG", "startCameraAndStreamerInternal: inputSurface равен null/невалиден, вызов reinitCodec")
                activeStreamer.reinitCodec(targetWidth, targetHeight, targetFps, targetBitrate)
            }
        }
        audioStreamer?.audioSink = { pcm, size, ptsUs ->
            streamer?.enqueueAudio(pcm, size, ptsUs)
        }
        bindCamera()
    }

    fun stopStreamingInternal(isShuttingDown: Boolean = false) {
        isStreaming = false
        isCameraInitialized = false
        audioStreamer?.audioSink = null
        try {
            audioStreamer?.stop()
        } catch (_: Exception) {}
        audioStreamer = null

        closeCameraSync()

        try {
            streamer?.stop()
        } catch (_: Exception) {}
        streamer = null

        if (!isShuttingDown) {
            try {
                updateNotification("Отключено по команде ПК")
                overlayController?.setStatus(OverlayController.Status.WAITING)
            } catch (_: Exception) {}
        }
    }

    fun setPreviewDisplaySurface(surface: Surface?) {
        val prevDisplay = displaySurface
        activityDisplaySurface = surface
        isActivityInForeground = (surface != null && surface.isValid)
        if (surface != null && surface.isValid) {
            overlayController?.hideOverlay()
            displaySurface = surface
        } else {
            displaySurface = overlayDisplaySurface
            if (isFloatingOverlayEnabled && (isStreaming || isCameraInitialized)) {
                overlayController?.showOverlay()
            }
        }
        Log.i("ROUTING_DEBUG", "setPreviewDisplaySurface: activitySurface=$surface, overlaySurface=$overlayDisplaySurface, displaySurface=$displaySurface, floating=$isFloatingOverlayEnabled")

        if (glRenderer != null) {
            glRenderer?.setPreviewSurface(displaySurface)
            return
        }

        if (displaySurface == prevDisplay && cameraCaptureSession != null) {
            return
        }

        // Если камера уже открыта — безопасно обновляем сессию с новым экраном
        val camera = cameraDevice ?: return
        getCameraHandler().post {
            startCamera2Session(camera)
        }
    }

    fun setLocalPreviewSurface(surface: Surface?) {
        setPreviewDisplaySurface(surface)
    }

    fun updateSessionTargets() {
        val camera = cameraDevice ?: return
        getCameraHandler().post {
            startCamera2Session(camera)
        }
    }

    fun closeCameraSync() {
        Log.i("ROUTING_DEBUG", "closeCameraSync: закрытие сессии и устройства Camera2")
        try {
            cameraCaptureSession?.close()
        } catch (_: Exception) {}
        cameraCaptureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        try {
            glRenderer?.release()
        } catch (_: Exception) {}
        glRenderer = null

        isCameraBusy = false
    }

    private fun bindCamera() {
        getCameraHandler().post {
            synchronized(cameraLock) {
                if (isCameraBusy) {
                    Log.w("ROUTING_DEBUG", "Камера уже открывается/переключается, пропуск")
                    isSwitching = false
                    return@post
                }
                isCameraBusy = true
            }

            try {
                closeCameraSync()

                val activeStreamer = streamer ?: RawH265Streamer(
                    port = 8554,
                    width = targetWidth,
                    height = targetHeight,
                    bitRate = targetBitrate,
                    frameRate = targetFps,
                    listener = this
                ).apply {
                    start()
                    streamer = this
                    isStreaming = true
                }
                activeStreamer.flushVideoQueue()
                activeStreamer.reinitCodec(targetWidth, targetHeight, targetFps, targetBitrate)

                val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val targetLensFacing = if (currentCameraFacingName == "back") CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
                var selectedCameraId: String? = currentExplicitCameraId
                if (selectedCameraId != null) {
                    val valid = try { manager.getCameraCharacteristics(selectedCameraId) != null } catch (_: Exception) { false }
                    if (!valid) {
                        Log.w("ROUTING_DEBUG", "selectedCameraId $selectedCameraId недоступна в CameraManager, сброс на default")
                        selectedCameraId = null
                        currentExplicitCameraId = null
                    }
                }
                if (selectedCameraId == null) {
                    for (id in manager.cameraIdList) {
                        val chars = manager.getCameraCharacteristics(id)
                        if (chars.get(CameraCharacteristics.LENS_FACING) == targetLensFacing) {
                            if (selectedCameraId == null || (targetLensFacing == CameraCharacteristics.LENS_FACING_BACK && id == "0")) {
                                selectedCameraId = id
                            }
                        }
                    }
                }
                if (selectedCameraId != null) {
                    val chars = try { manager.getCameraCharacteristics(selectedCameraId) } catch (_: Exception) { null }
                    val availableFpsRanges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val normalRanges = availableFpsRanges?.filter { it.upper <= 60 } ?: emptyList()
                    val hwUpper = normalRanges.map { it.upper }.maxOrNull() ?: 30

                    val isAux30Capped = (selectedCameraId == ultraWideCameraId || selectedCameraId == macroCameraId ||
                                         selectedCameraId == "50" || selectedCameraId == "54") && (hwUpper <= 30)
                    val maxSensorFps = if (isAux30Capped || currentCameraFacingName == "front") hwUpper else maxOf(hwUpper, 60)
                    
                    // Восстановление 60 FPS при переключении на основную камеру (1x), если датчик поддерживает 60 FPS
                    if (!isAux30Capped && currentCameraFacingName == "back" && (selectedCameraId == "0" || selectedCameraId == null)) {
                        if (userConfiguredFps >= 60 && maxSensorFps >= 60 && targetFps < 60) {
                            Log.i("ROUTING_DEBUG", "Автоматическое восстановление 60 FPS на 1x: $targetFps -> 60 FPS")
                            targetFps = userConfiguredFps
                        }
                    }

                    val effectiveFps = minOf(targetFps, maxSensorFps)
                    activeMaxFps = maxSensorFps
                    val rawSupported = (normalRanges.map { it.upper }) + listOf(30, 60)
                    activeSupportedFps = rawSupported.distinct().sorted()
                    Log.i("ROUTING_DEBUG", "Выбрана камера $selectedCameraId ($currentCameraFacingName): hwUpper=$hwUpper, maxFps=$activeMaxFps, effectiveFps=$effectiveFps, supportedFps=$activeSupportedFps")

                    if (activeStreamer.frameRate != targetFps) {
                        Log.i("ROUTING_DEBUG", "Настройка MediaCodec под целевой FPS: ${activeStreamer.frameRate} -> $targetFps FPS")
                        activeStreamer.reinitCodec(targetWidth, targetHeight, targetFps, targetBitrate)
                    }
                }

                val codecSurf = activeStreamer.inputSurface
                if (codecSurf == null || !codecSurf.isValid) {
                    Log.e("ROUTING_DEBUG", "codecSurf невалиден в bindCamera")
                    isCameraBusy = false
                    return@post
                }

                if (selectedCameraId != null && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    manager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.i("ROUTING_DEBUG", "Camera2 openCamera onOpened: id=${camera.id}")
                            cameraDevice = camera
                            isCameraBusy = false
                            startCamera2Session(camera)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            Log.w("ROUTING_DEBUG", "Camera2 openCamera onDisconnected")
                            closeCameraSync()
                            isCameraBusy = false
                            isSwitching = false
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e("ROUTING_DEBUG", "Camera2 openCamera onError: $error (id=${camera.id})")
                            closeCameraSync()
                            isCameraBusy = false
                            isSwitching = false
                            if (camera.id != "0" && currentCameraFacingName == "back") {
                                Log.w("ROUTING_DEBUG", "Попытка мягкого восстановления на основную камеру ID 0")
                                currentExplicitCameraId = null
                                activePhysicalCameraId = null
                                getCameraHandler().postDelayed({ bindCamera() }, 300)
                            }
                        }
                    }, getCameraHandler())
                } else {
                    Log.e("ROUTING_DEBUG", "selectedCameraId null или нет разрешения CAMERA")
                    isCameraBusy = false
                    isSwitching = false
                }
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка bindCamera Camera2: ", e)
                isCameraBusy = false
                isSwitching = false
            }
        }
    }

    private var highSpeedFallbackTried = false
    @Volatile
    private var activeSelectedRange: Range<Int> = Range(30, 30)
    @Volatile
    private var useHighSpeedActive: Boolean = false
    @Volatile
    private var lastSessionDiag: String = "init"
    @Volatile
    private var actualCaptureFps: Float = 0f
    @Volatile
    private var lastSensorFrameDurationNs: Long = 0L
    @Volatile
    private var lastSensorExposureNs: Long = 0L
    @Volatile
    private var lastSensorIso: Int = 0
    private var captureFpsWindowStartNs: Long = 0L
    private var captureFpsCounter: Int = 0

    private fun pickBestFpsRange(ranges: List<Range<Int>>, desiredFps: Int): Range<Int> {
        if (ranges.isEmpty()) return Range(minOf(desiredFps, 30), minOf(desiredFps, 30))
        if (desiredFps >= 60) {
            return ranges.firstOrNull { it.lower == 60 && it.upper == 60 }
                ?: ranges.filter { it.upper >= 60 }.maxWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
                ?: ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: ranges.filter { it.upper == 30 }.maxWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
                ?: ranges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
                ?: Range(30, 30)
        }
        return ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }
            ?: ranges.filter { it.upper == desiredFps }.maxWithOrNull(compareBy<Range<Int>> { it.lower })
            ?: ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper <= 30 }.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
            ?: ranges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
            ?: Range(30, 30)
    }

    private fun startCamera2Session(camera: CameraDevice) {
        Log.i("ROUTING_DEBUG", "startCamera2Session: camera.id = ${camera.id}")
        val codecSurface = streamer?.inputSurface ?: run {
            Log.e("ROUTING_DEBUG", "ОШИБКА: codecSurface == null при сборке captureRequest")
            return
        }
        if (!codecSurface.isValid) {
            Log.e("ROUTING_DEBUG", "ОШИБКА: codecSurface невалиден при сборке captureRequest")
            return
        }

        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(camera.id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (currentCameraFacingName == "front") 270 else 90)

            val targetChars = if (activePhysicalCameraId != null) {
                try { manager.getCameraCharacteristics(activePhysicalCameraId!!) } catch (_: Exception) { chars }
            } else chars
            val availableFpsRanges = targetChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val normalRanges = availableFpsRanges?.filter { it.upper <= 60 } ?: emptyList()

            val streamMap = targetChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val hsRanges = try {
                val targetSize = android.util.Size(targetWidth, targetHeight)
                val hsSizes = streamMap?.highSpeedVideoSizes ?: emptyArray()
                if (hsSizes.contains(targetSize)) {
                    streamMap?.getHighSpeedVideoFpsRangesFor(targetSize)?.toList() ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (_: Exception) { emptyList() }

            val validHsRanges = hsRanges.filter { it.upper in 60..120 }

            val hwNormalUpper = normalRanges.map { it.upper }.maxOrNull() ?: 30
            val hwHsUpper = validHsRanges.map { it.upper }.maxOrNull() ?: 0
            val hwUpper = maxOf(hwNormalUpper, minOf(hwHsUpper, 60))

            val isAuxCam = (camera.id != "0" && camera.id != "1")
            val isAux30Capped = (camera.id == ultraWideCameraId || camera.id == macroCameraId ||
                                 camera.id == "50" || camera.id == "54") && (hwUpper <= 30)
            val maxSensorFps = if (isAux30Capped || currentCameraFacingName == "front") hwUpper else maxOf(hwUpper, 60)
            activeMaxFps = maxSensorFps
            val effectiveSensorFps = minOf(targetFps, maxSensorFps)
            activeSupportedFps = (normalRanges.map { it.upper } + validHsRanges.map { it.upper } + listOf(30, 60)).distinct().sorted()

            val hasNormalFixed60 = normalRanges.any { it.lower == 60 && it.upper == 60 }
            val hasHs60 = validHsRanges.isNotEmpty()
            val is4KStream = (targetWidth >= 3840 || targetHeight >= 2160)

            val useHighSpeedSession = !highSpeedFallbackTried &&
                effectiveSensorFps >= 60 &&
                !is4KStream &&
                activePhysicalCameraId == null &&
                !isAuxCam &&
                !hasNormalFixed60 &&
                hasHs60
            useHighSpeedActive = useHighSpeedSession

            // Для обычного 60 FPS используем единый SurfaceTexture через GL, чтобы Camera2 не резал FPS из-за 2-х Surface.
            // Для ConstrainedHighSpeed используем прямой codecSurface (HAL MediaTek/Samsung часто отклоняет SurfaceTexture в HS).
            val needGlForHighFps = (!useHighSpeedSession && effectiveSensorFps >= 60 && !is4KStream && activePhysicalCameraId == null)

            val surfaces = mutableListOf<Surface>()
            if (!useHighSpeedSession && (isAutoFramingEnabled || useGlPipeline || isAuxCam || needGlForHighFps)) {
                try {
                    glRenderer?.release()
                } catch (_: Exception) {}
                val renderer = CameraGlRenderer(targetWidth, targetHeight, codecSurface)
                renderer.setOrientation(streamOrientation())
                renderer.isFrontCamera = (currentCameraFacingName == "front")
                renderer.sensorOrientation = sensorOrientation
                renderer.autoFraming.isEnabled = isAutoFramingEnabled
                renderer.setPreviewSurface(displaySurface)
                glRenderer = renderer

                val camTarget = renderer.cameraSurface
                if (camTarget != null && camTarget.isValid) {
                    surfaces.add(camTarget)
                    Log.i("ROUTING_DEBUG", "Пайплайн OpenGL ES (isAuxCam=$isAuxCam, highFps=$needGlForHighFps): добавлен SurfaceTexture таргет ($camTarget)")
                } else {
                    surfaces.add(codecSurface)
                }
            } else {
                try {
                    glRenderer?.release()
                } catch (_: Exception) {}
                glRenderer = null

                if (codecSurface.isValid) {
                    surfaces.add(codecSurface)
                    Log.i("ROUTING_DEBUG", "Пайплайн Direct Surface (highSpeed=$useHighSpeedSession): прямой вывод в кодек ($codecSurface)")
                }
                val dispSurf = displaySurface
                // Для HighSpeed, физического потока или AUX камеры держим строго 1 Surface во избежание 30 FPS / HAL reject
                if (!useHighSpeedSession && activePhysicalCameraId == null && !isAuxCam && dispSurf != null && dispSurf.isValid) {
                    surfaces.add(dispSurf)
                    Log.i("ROUTING_DEBUG", "Добавлена поверхность локального видоискателя ($dispSurf)")
                }
            }

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (s in surfaces) {
                builder.addTarget(s)
            }

            if (activePhysicalCameraId != null) {
                minZoomRatio = 1.0f
                maxZoomRatio = 1.0f
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
                zoomRatioRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                minZoomRatio = zoomRatioRange!!.lower
                maxZoomRatio = zoomRatioRange!!.upper
            } else {
                zoomRatioRange = null
                minZoomRatio = 1.0f
                maxZoomRatio = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            }
            currentZoomRatio = currentZoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
            Log.i("ROUTING_DEBUG", "Аппаратный диапазон зума: [$minZoomRatio, $maxZoomRatio], текущий=$currentZoomRatio")
            sensorCropRegion = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                minIso = it.lower
                maxIso = it.upper
            }
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                minExposureNs = it.lower
                maxExposureNs = it.upper
            }
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let {
                maxFocusDistance = it
            }

            activeAfModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            activeAeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            activeAwbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)

            if (currentCameraFacingName == "front") {
                currentZoomRatio = 1.0f
            }

            val selectedRange = if (useHighSpeedSession) {
                validHsRanges.firstOrNull { it.lower == 60 && it.upper == 60 }
                    ?: validHsRanges.filter { it.upper == 60 }.maxWithOrNull(compareBy<Range<Int>> { it.lower })
                    ?: validHsRanges.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower })
                    ?: Range(60, 60)
            } else {
                pickBestFpsRange(normalRanges, effectiveSensorFps)
            }
            activeSelectedRange = selectedRange
            activeFpsRangeStr = selectedRange.toString()
            Log.i("ROUTING_DEBUG", "[Camera2] FPS: camera=$currentCameraFacingName (id=${camera.id}, physId=$activePhysicalCameraId), targetFps=$targetFps, effectiveSensorFps=$effectiveSensorFps, maxSensorFps=$activeMaxFps, selectedRange=$selectedRange, highSpeed=$useHighSpeedSession, normalRanges=$normalRanges, hsRanges=$hsRanges")

            currentCaptureRequestBuilder = builder
            applyZoomToBuilder(builder)
            applyManualControlsToBuilder(builder)

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRange)

            var appliedVendorKeys = ""
            if (!useHighSpeedSession) {
                builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                if (effectiveSensorFps >= 60) {
                    val antibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                    if (antibandingModes?.contains(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF) == true) {
                        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF)
                    }
                    // Проверяем наличие MediaTek High-FPS vendor keys в характеристиках камеры
                    val availReqKeys = targetChars.availableCaptureRequestKeys ?: emptyList()
                    for (k in availReqKeys) {
                        if (k.name == "com.mediatek.streamingfeature.hfpsMode" || k.name == "com.mediatek.streaming.feature.hfpsMode") {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                builder.set(k as CaptureRequest.Key<Int>, 1)
                                appliedVendorKeys += "${k.name}=1;"
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (currentLensName == "macro") {
                    if (activeAfModes?.contains(CameraMetadata.CONTROL_AF_MODE_MACRO) == true) {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_MACRO)
                    } else if (activeAfModes?.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) == true) {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    }
                }
                val faceDetectModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
                val maxFaceMode = faceDetectModes?.maxOrNull() ?: CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
                val canDetectFaces = (maxFaceMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
                if (isAutoFramingEnabled && canDetectFaces) {
                    builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, maxFaceMode)
                } else {
                    builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
                }
                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                hasOis = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                hasEis = eisModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

                val stabOis = if (isStabilization && hasOis) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                val stabEis = if (isStabilization && hasEis && !is4KStream && effectiveSensorFps < 60) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, stabOis)
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabEis)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
                builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
                }

                if (isTorchOn && currentCameraFacingName == "back") {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }

            captureFpsWindowStartNs = 0L
            captureFpsCounter = 0
            lastSessionDiag = "cam=${camera.id},hs=$useHighSpeedSession,hsFb=$highSpeedFallbackTried,range=$selectedRange,surf=${surfaces.size},gl=${glRenderer != null},vendor=$appliedVendorKeys"

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    val callback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            result.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { lastSensorFrameDurationNs = it }
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastSensorExposureNs = it }
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastSensorIso = it }
                            val nowNs = System.nanoTime()
                            if (captureFpsWindowStartNs == 0L) {
                                captureFpsWindowStartNs = nowNs
                                captureFpsCounter = 0
                            } else {
                                captureFpsCounter++
                                val dtNs = nowNs - captureFpsWindowStartNs
                                if (dtNs >= 1_000_000_000L) {
                                    actualCaptureFps = (captureFpsCounter * 1_000_000_000.0 / dtNs).toFloat()
                                    captureFpsWindowStartNs = nowNs
                                    captureFpsCounter = 0
                                }
                            }
                            if (isAutoFramingEnabled) {
                                val faces = result.get(CaptureResult.STATISTICS_FACES)
                                val isFront = (currentCameraFacingName == "front")
                                glRenderer?.autoFraming?.onFacesDetected(faces, sensorCropRegion, streamOrientation(), isFront)
                            }
                        }
                    }
                    cameraCaptureCallback = callback
                    try {
                        if (useHighSpeedSession && session is CameraConstrainedHighSpeedCaptureSession) {
                            val burst = session.createHighSpeedRequestList(builder.build())
                            session.setRepeatingBurst(burst, callback, getCameraHandler())
                            highSpeedFallbackTried = false
                            lastSessionDiag += ",status=HS_RUNNING(burst=${burst.size})"
                            Log.i("ROUTING_DEBUG", "HighSpeed 60 FPS сессия запущена: burstSize=${burst.size}, range=$selectedRange")
                        } else {
                            session.setRepeatingRequest(builder.build(), callback, getCameraHandler())
                            lastSessionDiag += ",status=NORMAL_RUNNING"
                            Log.i("ROUTING_DEBUG", "Сессия запущена с таргетами: surfaces.size = ${surfaces.size} (экран + кодек), physId=$activePhysicalCameraId")
                        }
                        val orient = streamOrientation()
                        val isLand = (orient == 0 || orient == 180)
                        val wasSwitching = isSwitching
                        val statusJson = org.json.JSONObject()
                            .put("orient", orient)
                            .put("orientation", if (isLand) "horizontal" else "vertical")
                            .put("facing", currentCameraFacingName)
                            .put("width", targetWidth)
                            .put("height", targetHeight)
                            .put("fps", selectedRange.upper)
                            .put("camera_switch", wasSwitching)
                            .toString()
                        if (wasSwitching) {
                            streamer?.flushVideoQueue()
                        }
                        streamer?.enqueueStatus(statusJson)
                        streamer?.requestSyncFrame()
                        if (wasSwitching) {
                            getCameraHandler().postDelayed({ streamer?.requestSyncFrame() }, 80)
                            isSwitching = false
                        }
                        notifyStatusUpdated()
                    } catch (e: Exception) {
                        lastSessionDiag += ",repeatErr=${e.message}"
                        Log.e("ROUTING_DEBUG", "Ошибка setRepeatingRequest: ", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    lastSessionDiag += ",status=CONFIGURE_FAILED"
                    Log.e("ROUTING_DEBUG", "Ошибка конфигурации сессии (physId=$activePhysicalCameraId, highSpeed=$useHighSpeedSession)")
                    if (useHighSpeedSession && !highSpeedFallbackTried) {
                        Log.w("ROUTING_DEBUG", "Откат с HighSpeed сессии на обычную сессию")
                        highSpeedFallbackTried = true
                        val cam = cameraDevice
                        if (cam != null) {
                            getCameraHandler().postDelayed({ startCamera2Session(cam) }, 150)
                        }
                        return
                    }
                    if (activePhysicalCameraId != null) {
                        Log.w("ROUTING_DEBUG", "Откат с физической сессии на логическую камеру 0")
                        activePhysicalCameraId = null
                        val cam = cameraDevice
                        if (cam != null) {
                            getCameraHandler().postDelayed({ startCamera2Session(cam) }, 200)
                        }
                    }
                }
            }

            if (useHighSpeedSession) {
                @Suppress("DEPRECATION")
                camera.createConstrainedHighSpeedCaptureSession(surfaces, sessionCallback, getCameraHandler())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && (appliedVendorKeys.isNotEmpty() || activePhysicalCameraId != null)) {
                val outputConfigs = surfaces.map { s ->
                    OutputConfiguration(s).apply {
                        if (activePhysicalCameraId != null) {
                            setPhysicalCameraId(activePhysicalCameraId)
                        }
                    }
                }
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    java.util.concurrent.Executor { getCameraHandler().post(it) },
                    sessionCallback
                ).apply {
                    sessionParameters = builder.build()
                }
                camera.createCaptureSession(cfg)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(surfaces, sessionCallback, getCameraHandler())
            }
        } catch (e: Exception) {
            lastSessionDiag += ",createErr=${e.message}"
            isSwitching = false
            Log.e("ROUTING_DEBUG", "Ошибка создания Camera2 сессии: ", e)
        }
    }

    private fun restartCameraSessionWithPhysicalId(physId: String?) {
        val camera = cameraDevice ?: return
        getCameraHandler().post {
            try {
                startCamera2Session(camera)
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка restartCameraSessionWithPhysicalId: ", e)
            }
        }
    }

    private var orientationListener: OrientationEventListener? = null
    private var accelSensorListener: SensorEventListener? = null

    fun sendCurrentOrientationStatus() {
        val orient = streamOrientation()
        val isLandscape = (orient == 0 || orient == 180)
        val statusJson = org.json.JSONObject()
            .put("orient", orient)
            .put("orientation", if (isLandscape) "horizontal" else "vertical")
            .put("facing", currentCameraFacingName)
            .toString()
        streamer?.enqueueStatus(statusJson)
        Log.i("ROUTING_DEBUG", "[Sensor] Отправлен статус ориентации: orient=$orient, isLandscape=$isLandscape (dev=$deviceOrientationDegrees°)")
    }

    fun setOrientation(mode: String) {
        setOrientationInternal(mode)
    }

    private fun setOrientationInternal(mode: String) {
        val orient = streamOrientation()
        glRenderer?.setOrientation(orient)
        sendCurrentOrientationStatus()
        Log.i("ROUTING_DEBUG", "Ориентация обновлена вручную: streamOrient=$orient")
    }

    private var deviceOrientationDegrees = 0

    fun streamOrientation(): Int {
        val dev = deviceOrientationDegrees
        val isFront = (currentCameraFacingName == "front")
        return if (isFront) {
            (270 - dev + 360) % 360
        } else {
            (dev + 90) % 360
        }
    }

    private fun updateOrientationFromSnapped(snapped: Int) {
        val cur = deviceOrientationDegrees
        if (snapped != cur) {
            deviceOrientationDegrees = snapped
            val orient = streamOrientation()
            glRenderer?.setOrientation(orient)
            sendCurrentOrientationStatus()
            Log.i("ROUTING_DEBUG", "[Sensor] Авто-ориентация телефона: snapped=$snapped°, orient=$orient")
        }
    }

    fun startOrientationDetection() {
        if (orientationListener != null || accelSensorListener != null) return
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val gravitySensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val accelSensor = if (gravitySensor == null) sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
            val activeSensor = gravitySensor ?: accelSensor
            val isUsingGravity = (gravitySensor != null)

            if (sm != null && activeSensor != null) {
                accelSensorListener = object : SensorEventListener {
                    private var lastTick = 0L
                    private var filteredX = 0f
                    private var filteredY = 0f
                    private var filteredZ = 0f
                    private var hasFilteredInit = false
                    private var pendingOrientation = -1
                    private var pendingSinceMs = 0L

                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event == null) return

                        val rawX = -event.values[0]
                        val rawY = -event.values[1]
                        val rawZ = event.values[2]
                        val x: Float
                        val y: Float
                        val z: Float

                        if (isUsingGravity) {
                            x = rawX
                            y = rawY
                            z = rawZ
                        } else {
                            // Low-Pass фильтр отсекает высокочастотную тряску при отсутствии аппаратного TYPE_GRAVITY
                            if (!hasFilteredInit) {
                                filteredX = rawX
                                filteredY = rawY
                                filteredZ = rawZ
                                hasFilteredInit = true
                            } else {
                                val alpha = 0.15f
                                filteredX += alpha * (rawX - filteredX)
                                filteredY += alpha * (rawY - filteredY)
                                filteredZ += alpha * (rawZ - filteredZ)
                            }
                            x = filteredX
                            y = filteredY
                            z = filteredZ
                        }

                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastTick < 80L) return
                        lastTick = now

                        val magnitude = x * x + y * y
                        val absZ = Math.abs(z)

                        // Защита конусом по оси Z (~40° от вертикали): если телефон направлен под себя/в потолок
                        // или лежит плоско на столе, не реагируем на крены и не крутим кадр
                        if (magnitude < 25.0f || Math.sqrt(magnitude.toDouble()) < absZ * 0.85) {
                            pendingOrientation = -1
                            return
                        }

                        // Угол поворота устройства в градусах (0..359):
                        // 0 = вертикально (портрет), 90 = альбом (поворот по часовой, верх справа),
                        // 180 = вертикально вверх ногами, 270 = альбом (поворот против часовой, верх слева)
                        val angleRad = Math.atan2(-y.toDouble(), x.toDouble())
                        var orientation = 90 - Math.round(angleRad * (180.0 / Math.PI)).toInt()
                        while (orientation >= 360) orientation -= 360
                        while (orientation < 0) orientation += 360

                        // Симметричный гистерезис +-55° от текущей ориентации:
                        // предотвращает дребезг и случайные переключения
                        val cur = deviceOrientationDegrees
                        val diffFromCur = Math.abs((orientation - cur + 540) % 360 - 180)
                        if (diffFromCur < 55) {
                            pendingOrientation = -1
                            return
                        }

                        val targetSnapped = ((orientation + 45) / 90 * 90) % 360
                        if (pendingOrientation != targetSnapped) {
                            pendingOrientation = targetSnapped
                            pendingSinceMs = now
                            return
                        }
                        if (now - pendingSinceMs < 320L) {
                            return
                        }
                        pendingOrientation = -1
                        updateOrientationFromSnapped(targetSnapped)
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(accelSensorListener, activeSensor, SensorManager.SENSOR_DELAY_UI)
                Log.i("ROUTING_DEBUG", "Датчик ориентации запущен: ${if (isUsingGravity) "TYPE_GRAVITY (sensor fusion)" else "TYPE_ACCELEROMETER + Low-Pass"}")
            } else {
                // Резервный системный OrientationEventListener (только если нет акселерометра)
                orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                    override fun onOrientationChanged(degrees: Int) {
                        if (degrees == ORIENTATION_UNKNOWN) return
                        val snapped = ((degrees + 45) / 90 * 90) % 360
                        updateOrientationFromSnapped(snapped)
                    }
                }.apply {
                    if (canDetectOrientation()) {
                        enable()
                        Log.i("ROUTING_DEBUG", "OrientationEventListener успешно запущен (fallback)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ROUTING_DEBUG", "Не удалось запустить датчик ориентации", e)
        }
    }

    fun stopOrientationDetection() {
        try {
            orientationListener?.disable()
        } catch (_: Exception) {}
        orientationListener = null

        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelSensorListener?.let { sm?.unregisterListener(it) }
        } catch (_: Exception) {}
        accelSensorListener = null
    }

    fun setAutoFramingEnabled(enabled: Boolean) {
        if (isAutoFraming == enabled) return
        isAutoFraming = enabled

        if (!enabled) {
            glRenderer?.autoFraming?.reset()
        } else {
            glRenderer?.autoFraming?.isEnabled = true
        }

        getCameraHandler().post {
            val builder = currentCaptureRequestBuilder
            val session = cameraCaptureSession
            if (builder != null && session != null) {
                val faceMode = if (enabled) CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE else CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
                try {
                    cameraCaptureCallback?.let { cb ->
                        session.setRepeatingRequest(builder.build(), cb, getCameraHandler())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка переключения детекции лиц", e)
                }
            }
            if ((enabled && glRenderer == null) || (!enabled && !useGlPipeline && glRenderer != null)) {
                rebindResolutionOnly(targetWidth, targetHeight)
            }
        }
        Log.i("ROUTING_DEBUG", "Auto-Framing переключен: $enabled")
    }

    fun setPipelineMode(mode: String) {
        val gl = mode.lowercase() == "gl" || mode.lowercase() == "opengl"
        if (useGlPipeline == gl) return
        useGlPipeline = gl
        rebindResolutionOnly(targetWidth, targetHeight)
        Log.i("ROUTING_DEBUG", "Режим пайплайна переключен: mode=$mode, useGlPipeline=$useGlPipeline")
    }

    // --- ControlServer.ControlCallback ---

    override fun onConnectRequested(mode: String) {
        Log.i("ROUTING_DEBUG", "onConnectRequested: клиент подключился")
        isStreaming = true

        // НЕ вызываем closeCameraSync() и openCamera()! Камера уже работает в фоне!
        streamer?.resumeStreaming()
        streamer?.requestKeyFrame()

        updateNotification("Стриминг активен (ПК подключен)")
        overlayController?.setStatus(OverlayController.Status.STREAMING)
    }

    override fun onDisconnectRequested() {
        Log.i("ROUTING_DEBUG", "onDisconnectRequested")
        streamer?.pauseStreaming()
        updateNotification("Отключено по команде ПК")
        overlayController?.setStatus(OverlayController.Status.WAITING)
    }

    override fun onActionRequested(action: String, params: JSONObject) {
        Log.i("ROUTING_DEBUG", "onActionRequested: action=$action, params=$params")
        ContextCompat.getMainExecutor(this).execute {
            when (action) {
                "switch_camera" -> switchCamera()
                "toggle_torch" -> toggleTorch()
                "toggle_blackout" -> toggleBlackout()
                "toggle_mic_mute" -> toggleMicMute()
                "set_lens" -> {
                    val lens = params.optString("lens", "1x")
                    setLens(lens)
                }
                "set_zoom" -> {
                    val ratio = params.optDouble("ratio", 1.0).toFloat()
                    setZoomRatio(ratio)
                }
                "set_manual_exposure" -> {
                    val isManual = params.optBoolean("enabled", true)
                    val iso = params.optInt("iso", currentIso)
                    val expNs = params.optLong("exposure_time_ns", currentExposureTimeNs)
                    setManualExposure(isManual, iso, expNs)
                }
                "set_manual_focus" -> {
                    val isManual = params.optBoolean("enabled", true)
                    val dist = params.optDouble("focus_distance", currentFocusDistance.toDouble()).toFloat()
                    setManualFocus(isManual, dist)
                }
                "set_awb_mode" -> {
                    val mode = params.optString("mode", "auto")
                    setAwbMode(mode)
                }
                "toggle_auto_framing" -> setAutoFramingEnabled(!isAutoFramingEnabled)
                "set_auto_framing" -> {
                    val enabled = params.optBoolean("enabled", !isAutoFramingEnabled)
                    setAutoFramingEnabled(enabled)
                }
                "set_pipeline_mode" -> {
                    val mode = params.optString("mode", "gl")
                    setPipelineMode(mode)
                }
                "toggle_stabilization" -> setStabilizationEnabled(!isStabilization)
                "set_stabilization" -> {
                    val enabled = params.optBoolean("enabled", !isStabilization)
                    setStabilizationEnabled(enabled)
                }
                "set_camera_unit" -> {
                    val unitId = params.optString("id", "")
                    val isPhys = params.optBoolean("is_physical", false)
                    if (unitId.isNotBlank()) {
                        setCameraUnit(unitId, isPhys)
                    }
                }
            }
        }
    }

    private var cachedAvailableLenses: List<String>? = null

    fun getAvailableLenses(forceRefresh: Boolean = false): List<String> {
        if (!forceRefresh && cachedAvailableLenses != null) {
            return cachedAvailableLenses!!
        }
        val lenses = mutableListOf<String>()
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            var hasWide = (minZoomRatio <= 0.75f)
            var hasTele2 = (maxZoomRatio >= 1.9f)
            var hasTele3 = (maxZoomRatio >= 2.9f)
            var hasTele5 = (maxZoomRatio >= 4.9f)
            var hasTele10 = (maxZoomRatio >= 9.9f)

            if (manager != null) {
                // 1. Аппаратные характеристики Camera 0
                val back0 = try { manager.getCameraCharacteristics("0") } catch (_: Exception) { null }
                if (back0 != null) {
                    val focals0 = back0.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    if (focals0 != null && focals0.any { it < 3.2f }) hasWide = true
                    if (focals0 != null && focals0.any { it > 6.0f }) hasTele3 = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val zRange = back0.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        if (zRange != null) {
                            if (zRange.lower <= 0.75f) hasWide = true
                            if (zRange.upper >= 1.9f) hasTele2 = true
                            if (zRange.upper >= 2.9f) hasTele3 = true
                            if (zRange.upper >= 4.9f) hasTele5 = true
                            if (zRange.upper >= 9.9f) hasTele10 = true
                        }
                    }
                    val maxDig = back0.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
                    if (maxDig >= 1.9f) hasTele2 = true
                    if (maxDig >= 2.9f) hasTele3 = true
                    if (maxDig >= 4.9f) hasTele5 = true
                }

                // 2. Сканируем список cameraIdList и потенциальные скрытые aux-камеры Samsung/Android (0..32, vendor IDs 50..60)
                val candidateIds = linkedSetOf<String>()
                candidateIds.addAll(manager.cameraIdList)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (leadId in listOf("0", "1", "2", "56")) {
                        try {
                            val leadCh = manager.getCameraCharacteristics(leadId)
                            candidateIds.addAll(leadCh.physicalCameraIds)
                        } catch (_: Exception) {}
                    }
                }
                for (i in 0..32) { candidateIds.add(i.toString()) }
                candidateIds.addAll(listOf("20", "21", "22", "23", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60"))

                var foundUltraWideFocal = Float.MAX_VALUE

                for (id in candidateIds) {
                    val ch = try { manager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
                    val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    if (facing != CameraCharacteristics.LENS_FACING_FRONT) {
                        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val diag = if (phys != null && phys.width > 0f && phys.height > 0f) {
                            Math.sqrt((phys.width * phys.width + phys.height * phys.height).toDouble()).toFloat()
                        } else 0f
                        val eq = if (focal != null && diag > 0f) focal * 43.27f / diag else 0f

                        if (id != "0" && id != "56") {
                            // 1. Выделенный макро-модуль A-серии (ID 54 с f ~2.1-2.4мм, съемка в упор 3-5см)
                            if (id == "54" && (focal == null || focal < 3.5f)) {
                                hasMacroCameraHardware = true
                                macroCameraId = id
                                Log.i("ROUTING_DEBUG", "[Lens] Обнаружен макро-модуль A-серии: id=$id, focal=$focal, eq=${eq}mm")
                            }
                            // 2. Сверхширокоугольная камера (ID 50 на Galaxy A, ID 58 на Galaxy S22-S24, ID 2 на S20/S21 или f < 2.0мм)
                            else if (id == "50" || id == "58" || (id == "2" && (focal == null || focal < 2.5f)) || (focal != null && focal < 2.0f) || (eq in 10f..18f)) {
                                val curFocal = focal ?: 1.6f
                                if (curFocal < foundUltraWideFocal || id == "50" || id == "58") {
                                    hasWide = true
                                    hasWideCameraHardware = true
                                    ultraWideCameraId = id
                                    foundUltraWideFocal = curFocal
                                    Log.i("ROUTING_DEBUG", "[Lens] Обнаружен ширик: id=$id, focal=$focal, eq=${eq}mm")
                                }
                            }
                            // 3. Телевик 3x (Samsung A72 / S-серия ID 52, f ~7.1мм)
                            else if (id == "52" || (focal != null && focal in 6.0f..11.0f) || eq in 71f..110f) {
                                hasTele3 = true
                                teleCameraId = id
                                Log.i("ROUTING_DEBUG", "[Lens] Обнаружен телевик 3x: id=$id, focal=$focal, eq=${eq}mm")
                            }
                            // 4. Перископический супер-зум 5x/10x (Samsung S-серия Ultra ID 54 с f > 12мм)
                            else if ((id == "54" && focal != null && focal > 10.0f) || (focal != null && focal > 11.0f) || eq > 110f) {
                                hasTele5 = true
                                teleCameraId = id
                                Log.i("ROUTING_DEBUG", "[Lens] Обнаружен перископ-телевик: id=$id, focal=$focal, eq=${eq}mm")
                            }
                            else if (eq in 45f..70f) {
                                hasTele2 = true
                            }
                        }

                        // Samsung / Xiaomi Multi-Camera: физические модули (physicalCameraIds)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            for (pid in ch.physicalCameraIds) {
                                val pch = try { manager.getCameraCharacteristics(pid) } catch (_: Exception) { continue }
                                val pfocal = pch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                                val pphys = pch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                val pdiag = if (pphys != null && pphys.width > 0f && pphys.height > 0f) {
                                    Math.sqrt((pphys.width * pphys.width + pphys.height * pphys.height).toDouble()).toFloat()
                                } else 0f
                                val peq = if (pfocal != null && pdiag > 0f) pfocal * 43.27f / pdiag else 0f

                                if (pid == "54" && (pfocal == null || pfocal < 3.5f)) {
                                    hasMacroCameraHardware = true
                                    macroCameraId = pid
                                    Log.i("ROUTING_DEBUG", "[Samsung Lens] Обнаружен физический макро A-серии: pid=$pid, focal=$pfocal, eq=${peq}mm")
                                } else if ((pfocal != null && pfocal < 2.0f) || (peq in 10f..18f) || pid == "50" || pid == "58") {
                                    hasWide = true
                                    ultraWidePhysicalId = pid
                                    if (ultraWideCameraId == null || pid == "50" || pid == "58") ultraWideCameraId = pid
                                    Log.i("ROUTING_DEBUG", "[Samsung Lens] Обнаружен физический ширик: pid=$pid, focal=$pfocal, eq=${peq}mm")
                                } else if (pid == "52" || (pfocal != null && pfocal > 6.0f) || peq in 71f..110f) {
                                    hasTele3 = true
                                    telePhysicalId = pid
                                    Log.i("ROUTING_DEBUG", "[Samsung Lens] Обнаружен физический телевик: pid=$pid, focal=$pfocal, eq=${peq}mm")
                                }
                            }
                        }
                    }
                }

                // 5. Проверка макросъемки на флагманах S-серии (S21/S22/S23/S24 Ultra: Focus Enhancer через ширик с автофокусом)
                if (macroCameraId == null && ultraWideCameraId != null) {
                    try {
                        val wideCh = manager.getCameraCharacteristics(ultraWideCameraId!!)
                        val minFocusDist = wideCh.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                        val afModes = wideCh.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        val hasAf = afModes != null && (afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) || afModes.contains(CameraMetadata.CONTROL_AF_MODE_MACRO))
                        if (minFocusDist > 0f && hasAf) {
                            hasMacroCameraHardware = true
                            Log.i("ROUTING_DEBUG", "[Lens] Обнаружен режим Макро (Focus Enhancer через ширик $ultraWideCameraId, minFocusDist=$minFocusDist)")
                        }
                    } catch (_: Exception) {}
                }
            }

            val curZoomRange = zoomRatioRange
            if (hasWide || ultraWideCameraId != null || (curZoomRange != null && curZoomRange.lower <= 0.75f)) {
                lenses.add("0.5x")
            }
            lenses.add("1x")
            if (hasMacroCameraHardware || macroCameraId != null) lenses.add("macro")
            lenses.add("front")
            Log.i("ROUTING_DEBUG", "getAvailableLenses -> $lenses (hasWide=$hasWide, ultraWideCam=$ultraWideCameraId, macroCam=$macroCameraId, teleCam=$teleCameraId)")
            cachedAvailableLenses = lenses
            return lenses
        } catch (e: Exception) {
            Log.w("ROUTING_DEBUG", "getAvailableLenses error", e)
        }
        val fallback = listOf("1x", "front")
        cachedAvailableLenses = fallback
        return fallback
    }

    private fun applyZoomToBuilder(builder: CaptureRequest.Builder) {
        try {
            if (activePhysicalCameraId != null) {
                return
            }

            // На ширике (<= 0.75x) на Galaxy A34 (MediaTek Dimensity) сенсор аппаратно поддерживает максимум 30 FPS.
            // Если оставить фиксированный диапазон 60 FPS, ISP драйвер ширика зависает.
            // Поэтому на зуме <= 0.75x адаптируем FPS-диапазон под безопасные 30 FPS:
            val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val chars = if (cameraDevice != null && manager != null) {
                try { manager.getCameraCharacteristics(cameraDevice!!.id) } catch (_: Exception) { null }
            } else null
            val availableFpsRanges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

            if (currentZoomRatio <= 0.75f) {
                val availList = availableFpsRanges?.toList() ?: emptyList()
                val safe30Range = pickBestFpsRange(availList, 30)
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, safe30Range)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, activeSelectedRange)
            }

            if (currentZoomRatio < 1.0f) {
                try {
                    val samsungZoomKey = CaptureRequest.Key("samsung.android.scaler.zoomRatio", java.lang.Float::class.java)
                    builder.set(samsungZoomKey, currentZoomRatio as java.lang.Float)
                } catch (_: Exception) {}
                try {
                    val samsungZoomKey2 = CaptureRequest.Key("com.samsung.android.control.zoomratio", java.lang.Float::class.java)
                    builder.set(samsungZoomKey2, currentZoomRatio as java.lang.Float)
                } catch (_: Exception) {}
                try {
                    val samsungZoomKey3 = CaptureRequest.Key("samsung.android.scaler.zoomMapRatio", java.lang.Integer::class.java)
                    builder.set(samsungZoomKey3, (currentZoomRatio * 1000).toInt() as java.lang.Integer)
                } catch (_: Exception) {}
                try {
                    val samsungZoomKey4 = CaptureRequest.Key("com.samsung.android.scaler.zoomMapRatio", java.lang.Integer::class.java)
                    builder.set(samsungZoomKey4, (currentZoomRatio * 1000).toInt() as java.lang.Integer)
                } catch (_: Exception) {}
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
                } catch (_: Exception) {
                    if (zoomRatioRange != null) {
                        val clampedForApi = currentZoomRatio.coerceIn(zoomRatioRange!!.lower, zoomRatioRange!!.upper)
                        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedForApi)
                    }
                }
            } else {
                sensorCropRegion?.let { activeArray ->
                    val clamped = currentZoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
                    val cropW = (activeArray.width() / clamped).toInt()
                    val cropH = (activeArray.height() / clamped).toInt()
                    val cropX = activeArray.left + (activeArray.width() - cropW) / 2
                    val cropY = activeArray.top + (activeArray.height() - cropH) / 2
                    builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }
            }
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "Ошибка применения зума в builder", e)
        }
    }

    fun setZoomRatio(ratio: Float) {
        val effectiveMin = if (hasWideCameraHardware || ultraWideCameraId != null || (zoomRatioRange != null && zoomRatioRange!!.lower <= 0.75f)) 0.5f else minZoomRatio
        val clamped = ratio.coerceIn(effectiveMin, maxZoomRatio)
        currentZoomRatio = clamped
        currentLensName = when {
            clamped <= 0.75f -> "0.5x"
            clamped < 1.8f -> "1x"
            clamped < 2.8f -> "2x"
            clamped < 4.5f -> "3x"
            clamped < 8.0f -> "5x"
            else -> "${clamped.toInt()}x"
        }
        Log.i("ROUTING_DEBUG", "Установка zoomRatio = $clamped (диапазон [$effectiveMin, $maxZoomRatio]), lens=$currentLensName")
        applyZoomToSession()
    }

    fun setLens(lens: String) {
        val lower = lens.lowercase().trim()
        Log.i("ROUTING_DEBUG", "Запрос смены линзы: $lens (facing=$currentCameraFacingName, explicit=$currentExplicitCameraId)")

        if (lower == "front" || lower == "селфи" || lower == "фронт") {
            setCameraUnit("1", false)
            return
        }

        if (lower == "0.5x") {
            currentLensName = "0.5x"
            currentZoomRatio = 0.5f

            val canLogicalZoomWide = (zoomRatioRange != null && zoomRatioRange!!.lower <= 0.75f)
            if (canLogicalZoomWide && currentCameraFacingName == "back" && currentExplicitCameraId == null) {
                applyZoomToSession()
                Log.i("ROUTING_DEBUG", "0.5x применен через бесшовный аппаратный зум 0.5x в сессии Камеры 0")
                return
            }

            // Переключение на физический аппаратный ширик (ID 50 на Galaxy A, ID 58/2 на Galaxy S)
            val wideId = ultraWideCameraId ?: "50"
            Log.i("ROUTING_DEBUG", "Переключение на физическую широкоугольную камеру (ширик): id=$wideId")
            currentCameraFacingName = "back"
            currentExplicitCameraId = wideId
            activePhysicalCameraId = null
            currentZoomRatio = 1.0f
            notifyStatusUpdated()
            bindCamera()
            return
        }

        if (lower == "macro") {
            currentLensName = "macro"
            currentZoomRatio = 1.0f
            if (macroCameraId != null) {
                // Выделенный физический макро-модуль (Samsung Galaxy A-серии: ID 54)
                Log.i("ROUTING_DEBUG", "Переключение на физическую макро-камеру: id=$macroCameraId")
                currentCameraFacingName = "back"
                currentExplicitCameraId = macroCameraId
                activePhysicalCameraId = null
                notifyStatusUpdated()
                bindCamera()
                return
            } else if (ultraWideCameraId != null) {
                // Флагманы Galaxy S-серии (S21-S24 Ultra): Focus Enhancer через ширик
                Log.i("ROUTING_DEBUG", "Переключение на макро (Focus Enhancer через ширик $ultraWideCameraId)")
                currentCameraFacingName = "back"
                currentExplicitCameraId = ultraWideCameraId
                activePhysicalCameraId = null
                notifyStatusUpdated()
                bindCamera()
                return
            }
        }

        if (lower == "3x" && teleCameraId != null && (zoomRatioRange == null || zoomRatioRange!!.upper < 3.0f)) {
            currentLensName = "3x"
            currentZoomRatio = 1.0f
            Log.i("ROUTING_DEBUG", "Переключение на физический телевик: id=$teleCameraId")
            currentCameraFacingName = "back"
            currentExplicitCameraId = teleCameraId
            activePhysicalCameraId = null
            notifyStatusUpdated()
            bindCamera()
            return
        }

        // Если были на физическом ширике (50/58), макро (54) или фронталке, возвращаемся на основную камеру
        if (currentExplicitCameraId != null || currentCameraFacingName != "back") {
            currentExplicitCameraId = null
            currentCameraFacingName = "back"
            activePhysicalCameraId = null
            currentLensName = if (lower == "1x") "1x" else lower
            currentZoomRatio = 1.0f
            notifyStatusUpdated()
            bindCamera()
            return
        }

        if (activePhysicalCameraId != null) {
            activePhysicalCameraId = null
            useGlPipeline = false
            restartCameraSessionWithPhysicalId(null)
        }

        currentLensName = lower
        val targetRatio = when (lower) {
            "1x" -> 1.0f
            "2x" -> minOf(2.0f, maxZoomRatio)
            "3x" -> minOf(3.0f, maxZoomRatio)
            "5x" -> minOf(5.0f, maxZoomRatio)
            "10x" -> minOf(10.0f, maxZoomRatio)
            else -> {
                val parsed = lower.replace("x", "").toFloatOrNull() ?: 1.0f
                parsed.coerceIn(minZoomRatio, maxZoomRatio)
            }
        }
        setZoomRatio(targetRatio)
    }

    private fun applyZoomToSession() {
        val session = cameraCaptureSession ?: return
        val builder = currentCaptureRequestBuilder ?: return
        if (isCameraBusy) return
        getCameraHandler().post {
            try {
                if (cameraCaptureSession != session) return@post
                applyZoomToBuilder(builder)
                session.setRepeatingRequest(builder.build(), cameraCaptureCallback, getCameraHandler())
                Log.i("ROUTING_DEBUG", "Зум успешно применен в активной сессии: ratio=$currentZoomRatio, lens=$currentLensName")
                streamer?.requestSyncFrame()
            } catch (e: Exception) {
                if (e !is IllegalStateException) {
                    Log.e("ROUTING_DEBUG", "Ошибка applyZoomToSession: ", e)
                }
            }
        }
    }

    private fun applyManualControlsToBuilder(builder: CaptureRequest.Builder) {
        try {
            if (isManualExposure) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(minIso, maxIso))
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTimeNs.coerceIn(minExposureNs, maxExposureNs))
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }

            val afModes = activeAfModes ?: intArrayOf()
            if (isManualFocus) {
                if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance.coerceIn(0.0f, maxFocusDistance))
                }
            } else {
                val targetAf = when {
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF) -> CameraMetadata.CONTROL_AF_MODE_OFF
                    else -> afModes.firstOrNull() ?: CameraMetadata.CONTROL_AF_MODE_OFF
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, targetAf)
            }

            when (currentAwbMode.lowercase()) {
                "locked" -> {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                "incandescent" -> {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
                }
                "fluorescent" -> {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)
                }
                "daylight" -> {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
                }
                "cloudy" -> {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
                }
                else -> {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                }
            }
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "applyManualControlsToBuilder error", e)
        }
    }

    fun setManualExposure(isManual: Boolean, iso: Int, exposureTimeNs: Long) {
        isManualExposure = isManual
        if (iso > 0) currentIso = iso.coerceIn(minIso, maxIso)
        if (exposureTimeNs > 0) currentExposureTimeNs = exposureTimeNs.coerceIn(minExposureNs, maxExposureNs)
        applyManualControlsToSession()
    }

    fun setManualFocus(isManual: Boolean, focusDistance: Float) {
        isManualFocus = isManual
        currentFocusDistance = focusDistance.coerceIn(0.0f, maxFocusDistance)
        applyManualControlsToSession()
    }

    fun setAwbMode(mode: String) {
        currentAwbMode = mode
        applyManualControlsToSession()
    }

    private fun applyManualControlsToSession() {
        val session = cameraCaptureSession ?: return
        val builder = currentCaptureRequestBuilder ?: return
        getCameraHandler().post {
            try {
                applyManualControlsToBuilder(builder)
                session.setRepeatingRequest(builder.build(), null, getCameraHandler())
                Log.i("ROUTING_DEBUG", "Manual controls applied: AE=$isManualExposure, ISO=$currentIso, expNs=$currentExposureTimeNs, AF=$isManualFocus, focusDist=$currentFocusDistance, awb=$currentAwbMode")
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "applyManualControlsToSession error: ", e)
            }
        }
    }

    fun setStabilizationEnabled(enabled: Boolean) {
        isStabilization = enabled
        Log.i("ROUTING_DEBUG", "Установка стабилизации: $enabled (hasOis=$hasOis, hasEis=$hasEis)")
        val session = cameraCaptureSession ?: return
        val builder = currentCaptureRequestBuilder ?: return
        getCameraHandler().post {
            try {
                val is4KStream = (targetWidth >= 3840 || targetHeight >= 2160)
                val effectiveSensorFps = minOf(targetFps, activeMaxFps)
                val oisVal = if (enabled && hasOis) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                val eisVal = if (enabled && hasEis && !is4KStream && effectiveSensorFps < 60) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisVal)
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisVal)
                session.setRepeatingRequest(builder.build(), cameraCaptureCallback, getCameraHandler())
                val statusJson = org.json.JSONObject()
                    .put("stabilization", isStabilization)
                    .put("has_ois", hasOis)
                    .put("has_eis", hasEis)
                    .toString()
                streamer?.enqueueStatus(statusJson)
                Log.i("ROUTING_DEBUG", "Стабилизация успешно применена в активной сессии: $enabled (OIS=$oisVal, EIS=$eisVal)")
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка применения стабилизации: ", e)
            }
        }
    }

    private var cachedCameraUnits: List<CameraUnitInfo>? = null

    fun getAllCameraUnits(forceRefresh: Boolean = false): List<CameraUnitInfo> {
        if (!forceRefresh && cachedCameraUnits != null) {
            return cachedCameraUnits!!
        }
        val units = mutableListOf<CameraUnitInfo>()
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()

            // 1. Характеристики основной задней камеры (ID 0)
            val backCh = try { manager.getCameraCharacteristics("0") } catch (_: Exception) { null }
            val backOisModes = backCh?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val backHasOis = backOisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val backFocals = backCh?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val mainFocal = backFocals?.firstOrNull() ?: 26.0f

            // Аппаратный зум Camera 0
            val backZoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                backCh?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            val effectiveMinZoom = backZoomRange?.lower ?: minZoomRatio
            val backMaxDig = backCh?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8.0f
            val effectiveMaxZoom = backZoomRange?.upper ?: (if (maxZoomRatio > 1.0f) maxZoomRatio else backMaxDig)
            val availableBackLenses = getAvailableLenses()

            // А. Сверхширокоугольная камера (0.5x Ultra-Wide) - логическая линза
            val ultraFocal = backFocals?.firstOrNull { it < 3.2f } ?: (mainFocal * 0.5f)
            units.add(CameraUnitInfo(
                id = "0.5x",
                name = "0.5x Ultra-Wide",
                type = "ultra_wide",
                facing = "back",
                focalLengthMm = ultraFocal,
                hasOis = false,
                isPhysical = false
            ))

            // Б. Основная камера (1.0x Main)
            units.add(CameraUnitInfo(
                id = "0",
                name = "1.0x Main",
                type = "wide",
                facing = "back",
                focalLengthMm = mainFocal,
                hasOis = backHasOis,
                isPhysical = false
            ))

            // В. Макросъемка (Macro)
            val hasMacro = availableBackLenses.contains("macro") || hasMacroCameraHardware || macroCameraId != null
            if (hasMacro) {
                val macroFocal = backFocals?.firstOrNull { it in 2.0f..3.2f } ?: 2.4f
                units.add(CameraUnitInfo(
                    id = "macro",
                    name = "Macro (Макро)",
                    type = "macro",
                    facing = "back",
                    focalLengthMm = macroFocal,
                    hasOis = false,
                    isPhysical = true
                ))
            }

            // Е. Фронтальная камера (Селфи) - без дубликатов внутренних сенсоров
            val frontCh = try { manager.getCameraCharacteristics("1") } catch (_: Exception) { null }
            if (frontCh != null && !units.any { it.facing == "front" }) {
                val frontFocals = frontCh.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val frontFocal = frontFocals?.firstOrNull() ?: 3.4f
                units.add(CameraUnitInfo(
                    id = "1",
                    name = "Селфи",
                    type = "front",
                    facing = "front",
                    focalLengthMm = frontFocal,
                    hasOis = false,
                    isPhysical = true
                ))
            }
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "getAllCameraUnits error", e)
        }
        cachedCameraUnits = units
        return units
    }

    fun setCameraUnit(unitId: String, isPhysical: Boolean) {
        Log.i("ROUTING_DEBUG", "Запрос выбора блока камеры: unitId=$unitId, isPhysical=$isPhysical")
        if (unitId == "0.5x" || unitId == "lens_0.5x" || unitId == "50" || unitId == "58" || (ultraWidePhysicalId != null && unitId == ultraWidePhysicalId) || (ultraWideCameraId != null && unitId == ultraWideCameraId)) {
            setLens("0.5x")
            return
        }
        if (unitId == "macro" || unitId == "lens_macro" || unitId == "54" || (macroCameraId != null && unitId == macroCameraId)) {
            setLens("macro")
            return
        }
        if (unitId.startsWith("lens_")) {
            val lensName = unitId.removePrefix("lens_")
            setLens(lensName)
            return
        }
        if (unitId == "1x" || unitId == "0" || unitId == "56") {
            setLens("1x")
            return
        }
        if (unitId == "52" || (teleCameraId != null && unitId == teleCameraId)) {
            setLens("3x")
            return
        }
        if (unitId == "2x" || unitId == "3x" || unitId == "5x" || unitId == "10x") {
            setLens(unitId)
            return
        }
        if (unitId == "1" || unitId == "front") {
            currentCameraFacingName = "front"
            currentExplicitCameraId = null
            activePhysicalCameraId = null
            currentZoomRatio = 1.0f
            currentLensName = "front"
            glRenderer?.autoFraming?.reset()
            notifyStatusUpdated()
            bindCamera()
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val ch = if (manager != null) {
            try { manager.getCameraCharacteristics(unitId) } catch (_: Exception) { null }
        } else null

        if (ch != null) {
            val facingInt = ch.get(CameraCharacteristics.LENS_FACING)
            val isFront = (facingInt == CameraCharacteristics.LENS_FACING_FRONT)
            val newFacing = if (isFront) "front" else "back"
            if (newFacing != currentCameraFacingName || isFront) {
                currentZoomRatio = 1.0f
                currentLensName = if (isFront) "front" else "1x"
                glRenderer?.autoFraming?.reset()
            }
            currentCameraFacingName = newFacing
            currentExplicitCameraId = unitId
            activePhysicalCameraId = null
            bindCamera()
        } else {
            Log.w("ROUTING_DEBUG", "Модуль камеры $unitId не найден в CameraManager, мягкий откат на 1x")
            setLens("1x")
        }
    }

    private fun getBatteryInfo(): Triple<Int, Float, Boolean> {
        try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, intentFilter)
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (scale > 0) (level * 100 / scale) else level
                val tempTenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempTenths / 10.0f
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL
                return Triple(pct, tempC, isCharging)
            }
        } catch (e: Exception) {
            Log.w("ROUTING_DEBUG", "getBatteryInfo error", e)
        }
        return Triple(-1, -1.0f, false)
    }

    private fun getThermalStatusString(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            return when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "normal"
            }
        }
        return "normal"
    }

    override fun onOrientationRequested(mode: String) {
        Log.i("ROUTING_DEBUG", "onOrientationRequested: mode=$mode")
        ContextCompat.getMainExecutor(this).execute {
            setOrientationInternal(mode)
        }
    }

    override fun onConfigUpdated(resolution: String, fps: Int, bitrate: Int) {
        Log.i("ROUTING_DEBUG", "onConfigUpdated: resolution=$resolution, fps=$fps, bitrate=$bitrate")
        applyStreamConfig(resolution, fps, bitrate)
    }

    override fun getStatus(): ControlServer.StatusInfo {
        val (batteryLvl, batteryTemp, isCharging) = getBatteryInfo()
        val thermal = getThermalStatusString()
        val reportedMinZoom = if (minZoomRatio <= 0.75f || hasWideCameraHardware || ultraWidePhysicalId != null || ultraWideCameraId != null) 0.5f else minZoomRatio
        val orientVal = streamOrientation()
        val isLand = (orientVal == 0 || orientVal == 180)
        return ControlServer.StatusInfo(
            isStreaming = isStreaming,
            cameraFacing = currentCameraFacingName,
            isTorchOn = isTorchOn,
            orient = orientVal,
            orientation = if (isLand) "horizontal" else "vertical",
            isMicMuted = audioStreamer?.isMuted ?: false,
            currentLens = currentLensName,
            currentZoom = currentZoomRatio,
            minZoom = reportedMinZoom,
            maxZoom = maxZoomRatio,
            batteryLevel = batteryLvl,
            batteryTemp = batteryTemp,
            isCharging = isCharging,
            thermalStatus = thermal,
            isManualExposure = isManualExposure,
            iso = currentIso,
            exposureTimeNs = currentExposureTimeNs,
            minIso = minIso,
            maxIso = maxIso,
            minExposureNs = minExposureNs,
            maxExposureNs = maxExposureNs,
            isManualFocus = isManualFocus,
            focusDistance = currentFocusDistance,
            maxFocusDistance = maxFocusDistance,
            awbMode = currentAwbMode,
            maxFps = activeMaxFps,
            supportedFps = activeSupportedFps,
            currentFps = minOf(targetFps, activeMaxFps),
            currentFpsRange = activeFpsRangeStr,
            width = targetWidth,
            height = targetHeight,
            resolution = "${targetWidth}x${targetHeight}",
            isAutoFraming = isAutoFramingEnabled,
            pipelineMode = if (glRenderer != null) "gl" else "direct",
            availableLenses = getAvailableLenses(),
            isStabilization = isStabilization,
            hasOis = hasOis,
            hasEis = hasEis,
            currentCameraUnit = when {
                currentCameraFacingName == "front" -> (currentExplicitCameraId ?: "1")
                currentExplicitCameraId != null && (currentExplicitCameraId == ultraWideCameraId || currentExplicitCameraId == "50" || currentExplicitCameraId == "58") -> "0.5x"
                currentExplicitCameraId != null && (currentExplicitCameraId == macroCameraId || currentExplicitCameraId == "54") -> "macro"
                currentExplicitCameraId != null -> currentExplicitCameraId!!
                currentLensName == "0.5x" -> "0.5x"
                currentLensName == "macro" -> "macro"
                else -> "0"
            },
            cameraUnits = getAllCameraUnits().map {
                ControlServer.CameraUnitJson(
                    id = it.id,
                    name = it.name,
                    type = it.type,
                    facing = it.facing,
                    focalLengthMm = it.focalLengthMm,
                    hasOis = it.hasOis,
                    isPhysical = it.isPhysical
                )
            }
        )
    }

    override fun getDiagnosticsJson(): String {
        val root = org.json.JSONObject()
        try {
            root.put("version_code", BuildConfig.VERSION_CODE)
            root.put("version_name", BuildConfig.VERSION_NAME)
            root.put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            root.put("sdk_int", Build.VERSION.SDK_INT)
            root.put("target_width", targetWidth)
            root.put("target_height", targetHeight)
            root.put("target_fps", targetFps)
            root.put("active_range", activeFpsRangeStr)
            root.put("actual_capture_fps", actualCaptureFps.toDouble())
            root.put("last_sensor_frame_duration_ns", lastSensorFrameDurationNs)
            root.put("last_sensor_exposure_ns", lastSensorExposureNs)
            root.put("last_sensor_iso", lastSensorIso)
            root.put("use_high_speed_active", useHighSpeedActive)
            root.put("high_speed_fallback_tried", highSpeedFallbackTried)
            root.put("last_session_diag", lastSessionDiag)

            val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val camsArr = org.json.JSONArray()
            if (manager != null) {
                for (id in manager.cameraIdList) {
                    val cJson = org.json.JSONObject()
                    cJson.put("id", id)
                    try {
                        val ch = manager.getCameraCharacteristics(id)
                        cJson.put("facing", ch.get(CameraCharacteristics.LENS_FACING))
                        cJson.put("hw_level", ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                        val aeRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        cJson.put("normal_fps_ranges", aeRanges?.joinToString(", ") { it.toString() } ?: "")
                        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        if (map != null) {
                            val hsSizes = map.highSpeedVideoSizes ?: emptyArray()
                            cJson.put("hs_sizes", hsSizes.joinToString(", ") { it.toString() })
                            val hsMap = org.json.JSONObject()
                            for (sz in hsSizes) {
                                val r = map.getHighSpeedVideoFpsRangesFor(sz)
                                hsMap.put(sz.toString(), r.joinToString(", ") { it.toString() })
                            }
                            cJson.put("hs_ranges", hsMap)
                            val sz1080 = android.util.Size(1920, 1080)
                            cJson.put("min_dur_codec_1080p_ns", try { map.getOutputMinFrameDuration(android.media.MediaCodec::class.java, sz1080) } catch (_: Exception) { -1L })
                            cJson.put("min_dur_st_1080p_ns", try { map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, sz1080) } catch (_: Exception) { -1L })
                        }
                        val reqKeys = ch.availableCaptureRequestKeys?.filter {
                            val n = it.name.lowercase()
                            n.contains("fps") || n.contains("speed") || n.contains("rate") || n.contains("hfps") || n.contains("mediatek") || n.contains("samsung")
                        }?.map { it.name } ?: emptyList()
                        cJson.put("vendor_req_keys", org.json.JSONArray(reqKeys))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val sessKeys = ch.availableSessionKeys?.map { it.name } ?: emptyList()
                            cJson.put("session_keys", org.json.JSONArray(sessKeys))
                        }
                    } catch (e: Exception) {
                        cJson.put("error", e.message)
                    }
                    camsArr.put(cJson)
                }
            }
            root.put("cameras", camsArr)
        } catch (e: Exception) {
            root.put("error", e.message)
        }
        return root.toString()
    }

    override fun onRequestUserPairing(clientId: String, clientName: String, onDecision: (PairingDecision) -> Unit) {
        Log.i("ROUTING_DEBUG", "onRequestUserPairing: clientId=$clientId, clientName=$clientName")
        Handler(Looper.getMainLooper()).post {
            try {
                PairingManager.registerPendingRequest(this, clientId, clientName, onDecision)

                // 1. Полноэкранный системный диалог через PairingRequestActivity (поверх всех окон и экрана блокировки)
                val intent = Intent(this, PairingRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("client_id", clientId)
                    putExtra("client_name", clientName)
                }

                // 2. Heads-Up Notification как надежный резервный канал
                showPairingNotification(clientId, clientName, intent)

                // 3. Запуск Activity
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка показа окна сопряжения, используем уведомление", e)
                // FAIL-CLOSED: Не авто-подтверждаем! Уведомление уже отправлено
            }
        }
    }

    private fun showPairingNotification(clientId: String, clientName: String, fullScreenIntent: Intent) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "virtual_cam_pairing_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    getString(R.string.pairing_title),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(R.string.pairing_title)
                    enableLights(true)
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                1001,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val allowAlwaysIntent = Intent(this, PairingActionReceiver::class.java).apply {
                action = PairingActionReceiver.ACTION_ALLOW_ALWAYS
            }
            val allowAlwaysPending = PendingIntent.getBroadcast(
                this,
                1002,
                allowAlwaysIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val allowOnceIntent = Intent(this, PairingActionReceiver::class.java).apply {
                action = PairingActionReceiver.ACTION_ALLOW_ONCE
            }
            val allowOncePending = PendingIntent.getBroadcast(
                this,
                1003,
                allowOnceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val declineIntent = Intent(this, PairingActionReceiver::class.java).apply {
                action = PairingActionReceiver.ACTION_DECLINE
            }
            val declinePending = PendingIntent.getBroadcast(
                this,
                1004,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.pairing_title))
                .setContentText(getString(R.string.pairing_message, clientName))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setOngoing(false)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .addAction(0, getString(R.string.pairing_allow_always), allowAlwaysPending)
                .addAction(0, getString(R.string.pairing_allow_once), allowOncePending)
                .addAction(0, getString(R.string.pairing_decline), declinePending)
                .build()

            manager.notify(PairingManager.PAIRING_NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "Не удалось отправить уведомление сопряжения", e)
        }
    }

    fun toggleMicMute(): Boolean {
        val streamer = audioStreamer
        return if (streamer != null) {
            streamer.isMuted = !streamer.isMuted
            Log.i(TAG, "Микрофон переключен: isMuted=${streamer.isMuted}")
            streamer.isMuted
        } else {
            true
        }
    }

    fun isMicMuted(): Boolean {
        return audioStreamer?.isMuted ?: true
    }

    fun updateBitrateOnTheFly(newBitrate: Int) {
        targetBitrate = newBitrate
        streamer?.updateBitrate(newBitrate)
        Log.i("ROUTING_DEBUG", "Битрейт обновлен на лету: $newBitrate bps")
    }

    fun updateFpsOnTheFly(newFps: Int) {
        targetFps = newFps
        Log.i("ROUTING_DEBUG", "updateFpsOnTheFly: $newFps")
        getCameraHandler().post {
            val camera = cameraDevice
            if (camera != null) {
                startCamera2Session(camera)
            } else {
                bindCamera()
            }
        }
    }

    fun rebindResolutionOnly(newWidth: Int, newHeight: Int) {
        Log.i("ROUTING_DEBUG", "rebindResolutionOnly: ${newWidth}x${newHeight}")
        targetWidth = newWidth
        targetHeight = newHeight
        getCameraHandler().post {
            try {
                streamer?.pauseStreaming()
                try {
                    cameraCaptureSession?.close()
                } catch (_: Exception) {}
                cameraCaptureSession = null

                // Обязательно освобождаем GL-рендерер до reinitCodec(), иначе drawFrame() обратится к уже освобождённой codecSurface
                try {
                    glRenderer?.release()
                } catch (_: Exception) {}
                glRenderer = null

                val activeStreamer = streamer ?: RawH265Streamer(
                    port = 8554,
                    width = newWidth,
                    height = newHeight,
                    bitRate = targetBitrate,
                    frameRate = targetFps,
                    listener = this
                ).apply {
                    start()
                    streamer = this
                    isStreaming = true
                }

                val newSurface = activeStreamer.reinitCodec(newWidth, newHeight, targetFps, targetBitrate)
                val camera = cameraDevice
                if (camera != null && newSurface != null && newSurface.isValid) {
                    startCamera2Session(camera)
                } else {
                    bindCamera()
                }

                streamer?.resumeStreaming()
                streamer?.requestKeyFrame()
                Log.i("ROUTING_DEBUG", "Разрешение успешно изменено на ${newWidth}x${newHeight}")
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка смены разрешения: ", e)
            }
        }
    }

    private var lastStreamConfigSignature: String = ""

    fun applyStreamConfig(resolution: String, fps: Int, bitrate: Int, persist: Boolean = true) {
        val sig = "$resolution:$fps:$bitrate"
        val now = System.currentTimeMillis()
        if (sig == lastStreamConfigSignature && now - lastStreamConfigTime < 150) {
            Log.w("ROUTING_DEBUG", "applyStreamConfig: пропуск идентичного повтора ($sig, ${now - lastStreamConfigTime}мс)")
            return
        }
        lastStreamConfigSignature = sig
        lastStreamConfigTime = now

        val (newWidth, newHeight) = when (resolution.lowercase()) {
            "720p" -> Pair(1280, 720)
            "1080p" -> Pair(1920, 1080)
            "4k", "2160p" -> Pair(3840, 2160)
            else -> Pair(1920, 1080)
        }

        val isResChanged = (newWidth != targetWidth || newHeight != targetHeight)
        val isFpsChanged = (fps != targetFps)
        val isBitrateChanged = (bitrate != targetBitrate)

        if (!isResChanged && !isFpsChanged && !isBitrateChanged) {
            Log.i("ROUTING_DEBUG", "applyStreamConfig: параметры совпадают с текущими, пропуск")
            return
        }

        Log.i("ROUTING_DEBUG", "applyStreamConfig: resolution=$resolution (${newWidth}x${newHeight}), fps=$fps, bitrate=$bitrate | isResChanged=$isResChanged, isFpsChanged=$isFpsChanged, isBitrateChanged=$isBitrateChanged")

        targetBitrate = bitrate
        if (persist) userConfiguredFps = fps
        targetFps = fps
        targetWidth = newWidth
        targetHeight = newHeight
        if (persist) saveCameraPrefs()

        if (!isResChanged && !isFpsChanged && isBitrateChanged) {
            updateBitrateOnTheFly(bitrate)
            return
        }

        if (isResChanged || isFpsChanged) {
            highSpeedFallbackTried = false
            rebindResolutionOnly(newWidth, newHeight)
            return
        }
    }

    fun switchCamera() {
        if (isSwitching) return
        isSwitching = true
        highSpeedFallbackTried = false

        currentCameraFacingName = if (currentCameraFacingName == "back") "front" else "back"

        // Сбрасываем выбранные физические камеры и зум предыдущей камеры
        currentExplicitCameraId = null
        activePhysicalCameraId = null
        currentZoomRatio = 1.0f
        currentLensName = if (currentCameraFacingName == "front") "front" else "1x"
        glRenderer?.autoFraming?.reset()

        if (currentCameraFacingName == "front" && isTorchOn) {
            isTorchOn = false
        }

        saveCameraPrefs()
        notifyStatusUpdated()
        bindCamera()
    }

    fun toggleTorch() {
        if (currentCameraFacingName != "back") return
        isTorchOn = !isTorchOn
        Log.i("ROUTING_DEBUG", "toggleTorch: isTorchOn = $isTorchOn")

        getCameraHandler().post {
            val session = cameraCaptureSession
            val builder = currentCaptureRequestBuilder
            if (session != null && builder != null) {
                try {
                    builder.set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                    session.setRepeatingRequest(builder.build(), cameraCaptureCallback, getCameraHandler())
                    notifyStatusUpdated()
                    return@post
                } catch (e: Exception) {
                    Log.w("ROUTING_DEBUG", "Ошибка быстрого переключения вспышки, перезапуск сессии", e)
                }
            }
            val camera = cameraDevice
            if (camera != null) {
                startCamera2Session(camera)
            }
            notifyStatusUpdated()
        }
    }

    interface ServiceStatusListener {
        fun onStreamingStatusChanged(isStreaming: Boolean)
        fun onStatusUpdated() {}
        fun onBlackoutToggled(isBlackout: Boolean) {}
    }

    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<ServiceStatusListener>()

    fun addStatusListener(listener: ServiceStatusListener) {
        statusListeners.add(listener)
        listener.onStreamingStatusChanged(isStreaming)
        listener.onStatusUpdated()
        listener.onBlackoutToggled(isBlackoutEnabled)
        if (isBlackoutEnabled) {
            removeServiceOverlayBlackout()
        }
    }

    fun removeStatusListener(listener: ServiceStatusListener) {
        statusListeners.remove(listener)
        if (isBlackoutEnabled && statusListeners.isEmpty()) {
            showServiceOverlayBlackout()
        }
    }

    private fun notifyBlackoutToggled(isBlackout: Boolean) {
        mainHandler.post {
            for (l in statusListeners) {
                try { l.onBlackoutToggled(isBlackout) } catch (_: Exception) {}
            }
        }
    }

    private fun notifyStatusChanged(streaming: Boolean) {
        isStreaming = streaming
        mainHandler.post {
            for (l in statusListeners) {
                try { l.onStreamingStatusChanged(streaming) } catch (_: Exception) {}
            }
        }
    }

    fun notifyStatusUpdated() {
        mainHandler.post {
            for (l in statusListeners) {
                try { l.onStatusUpdated() } catch (_: Exception) {}
            }
        }
    }

    fun isFloatingOverlayEnabled(): Boolean = isFloatingOverlayEnabled

    fun setFloatingOverlayEnabled(enabled: Boolean) {
        isFloatingOverlayEnabled = enabled
        if (!enabled) {
            overlayController?.hideOverlay()
        }
        saveCameraPrefs()
    }

    fun showFloatingOverlay() {
        if (!isFloatingOverlayEnabled) return
        overlayController?.showOverlay()
    }

    fun hideFloatingOverlay() {
        overlayController?.hideOverlay()
    }

    fun isStreamingActive(): Boolean = isStreaming

    fun isFrontCamera(): Boolean = (currentCameraFacingName == "front")

    fun isBlackout(): Boolean = isBlackoutEnabled

    // --- RawH265Streamer.StatusListener ---

    override fun onStatusChanged(status: String, isConnected: Boolean) {
        notifyStatusChanged(isConnected)
        if (isConnected) {
            updateNotification("Трансляция активна ($targetWidth x $targetHeight)")
            overlayController?.setStatus(OverlayController.Status.STREAMING)
            sendCurrentOrientationStatus()
        } else {
            updateNotification("Ожидание подключения ПК...")
            overlayController?.setStatus(OverlayController.Status.WAITING)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        disableBlackout()

        overlayController?.hideOverlay()
        overlayController = null

        udpBeaconBroadcaster?.stop()
        udpBeaconBroadcaster = null

        try {
            unregisterReceiver(heatGuardReceiver)
        } catch (_: Exception) {}

        stopOrientationDetection()
        releaseLocks()
        stopStreamingInternal(isShuttingDown = true)
        controlServer?.stop()
        controlServer = null

        try {
            cameraThread?.quitSafely()
        } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null

        super.onDestroy()
        Log.i(TAG, "FloatingCameraService остановлен, камера и ресурсы полностью освобождены")
    }
}
