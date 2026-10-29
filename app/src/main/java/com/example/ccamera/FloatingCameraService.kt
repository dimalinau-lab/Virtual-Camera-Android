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
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
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

    private var overlayController: OverlayController? = null
    private var blackoutOverlayView: View? = null
    private var isBlackoutEnabled = false
    private var isFloatingOverlayEnabled = true
    private var udpBeaconBroadcaster: UdpBeaconBroadcaster? = null

    private var currentCameraFacingName: String = "back"

    private var isTorchOn: Boolean = false
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
                applyStreamConfig(if (targetWidth >= 1920) "1080p" else "720p", newFps, newBitrate)
            }
        } else if (tempC <= 40.5f && isHeatThrottled) {
            isHeatThrottled = false
            Log.i(TAG, "[HEAT GUARD] Температура нормализована: ${tempC}°C <= 40.5°C. Восстановление исходных параметров: $preHeatFps FPS, $preHeatBitrate bps")
            applyStreamConfig(if (targetWidth >= 1920) "1080p" else "720p", preHeatFps, preHeatBitrate)
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
    var telePhysicalId: String? = null
    var teleCameraId: String? = null
    var hasWideCameraHardware: Boolean = false
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
            targetFps = prefs.getInt("target_fps", 60)
            targetBitrate = prefs.getInt("target_bitrate", 5_500_000)
            currentCameraFacingName = prefs.getString("camera_facing", "back") ?: "back"
            isFloatingOverlayEnabled = prefs.getBoolean("pref_floating_enabled", true)
            activeMaxFps = 60
            activeSupportedFps = listOf(30, 60)
            activeFpsRangeStr = "[60, 60]"
            Log.i("ROUTING_DEBUG", "loadCameraPrefs: ${targetWidth}x${targetHeight} @ ${targetFps}fps, facing=$currentCameraFacingName, floating=$isFloatingOverlayEnabled")
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
                    updateNotification(if (isStreaming) "Трансляция активна ($targetWidth x $targetHeight)" else "Ожидание подключения ПК...")
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
        startForegroundWithStatus("Ожидание подключения ПК...")
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
                updateNotification(if (isStreaming) "Трансляция активна ($targetWidth x $targetHeight)" else "Ожидание подключения ПК...")
                return START_STICKY
            }
        }

        try {
            startForegroundWithStatus(if (isStreaming) "Трансляция активна ($targetWidth x $targetHeight)" else "Ожидание подключения ПК...")
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
            Log.i(TAG, "WakeLock и WifiLock успешно зафиксированы")
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
        val micTitle = if (isMuted) "Вкл. микр." else "Выкл. микр."
        val micIcon = if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtualCam Native")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_switch_camera, "Камера", flipPendingIntent)
            .addAction(micIcon, micTitle, micPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPendingIntent)
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
        if (isBlackoutEnabled) {
            disableBlackout()
        } else {
            enableBlackout()
        }
        return isBlackoutEnabled
    }

    private fun enableBlackout() {
        if (isBlackoutEnabled) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Нет разрешения SYSTEM_ALERT_WINDOW для отображения блэкаута")
            return
        }

        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
            }

            val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    disableBlackout()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    disableBlackout()
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
                PixelFormat.TRANSLUCENT
            ).apply {
                screenBrightness = 0.01f
            }

            windowManager.addView(view, params)
            blackoutOverlayView = view
            isBlackoutEnabled = true
            Log.i(TAG, "Blackout (затемнение экрана) включен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка включения Blackout", e)
        }
    }

    private fun disableBlackout() {
        if (!isBlackoutEnabled) return
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            blackoutOverlayView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            }
            blackoutOverlayView = null
            isBlackoutEnabled = false
            Log.i(TAG, "Blackout (затемнение экрана) выключен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка выключения Blackout", e)
        }
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
                    val maxSensorFps = availableFpsRanges?.map { it.upper }?.maxOrNull() ?: 30
                    val effectiveFps = minOf(targetFps, maxSensorFps)
                    activeMaxFps = maxSensorFps
                    activeSupportedFps = availableFpsRanges?.map { it.upper }?.distinct()?.sorted() ?: listOf(30)
                    Log.i("ROUTING_DEBUG", "Выбрана камера $selectedCameraId ($currentCameraFacingName): maxFps=$activeMaxFps, effectiveFps=$effectiveFps, supportedFps=$activeSupportedFps")

                    if (activeStreamer.frameRate != effectiveFps) {
                        Log.i("ROUTING_DEBUG", "Адаптация MediaCodec под частоту сенсора: ${activeStreamer.frameRate} -> $effectiveFps FPS")
                        activeStreamer.reinitCodec(targetWidth, targetHeight, effectiveFps, targetBitrate)
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
                            isSwitching = false
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
            val surfaces = mutableListOf<Surface>()
            if (isAutoFramingEnabled || useGlPipeline) {
                try {
                    glRenderer?.release()
                } catch (_: Exception) {}
                val renderer = CameraGlRenderer(targetWidth, targetHeight, codecSurface)
                renderer.setOrientation(streamOrientation())
                renderer.isFrontCamera = (currentCameraFacingName == "front")
                renderer.autoFraming.isEnabled = isAutoFramingEnabled
                renderer.setPreviewSurface(displaySurface)
                glRenderer = renderer

                val camTarget = renderer.cameraSurface
                if (camTarget != null && camTarget.isValid) {
                    surfaces.add(camTarget)
                    Log.i("ROUTING_DEBUG", "Пайплайн OpenGL ES: добавлен SurfaceTexture таргет ($camTarget)")
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
                    Log.i("ROUTING_DEBUG", "Пайплайн Direct Surface: прямой вывод в кодек ($codecSurface)")
                }
                val dispSurf = displaySurface
                // Для физического потока камеры (activePhysicalCameraId) нельзя добавлять второй Surface, так как HAL физической камеры ограничен 1 Surface
                if (activePhysicalCameraId == null && dispSurf != null && dispSurf.isValid) {
                    surfaces.add(dispSurf)
                    Log.i("ROUTING_DEBUG", "Добавлена поверхность локального видоискателя ($dispSurf)")
                }
            }

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (s in surfaces) {
                builder.addTarget(s)
            }

            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(camera.id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (currentCameraFacingName == "front") 270 else 90)

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
            currentCaptureRequestBuilder = builder
            applyZoomToBuilder(builder)
            applyManualControlsToBuilder(builder)

            val targetChars = if (activePhysicalCameraId != null) {
                try { manager.getCameraCharacteristics(activePhysicalCameraId!!) } catch (_: Exception) { chars }
            } else chars
            val availableFpsRanges = targetChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val maxSensorFps = availableFpsRanges?.map { it.upper }?.maxOrNull() ?: 30
            val effectiveFps = if (activePhysicalCameraId != null) minOf(30, maxSensorFps) else minOf(targetFps, maxSensorFps)
            activeMaxFps = maxSensorFps
            activeSupportedFps = availableFpsRanges?.map { it.upper }?.distinct()?.sorted() ?: listOf(30)


            val selectedRange = if (effectiveFps >= 60) {
                // Priority 1: Exact fixed 60 FPS range [60, 60] (prevents AE from dropping to 30 in low light)
                availableFpsRanges?.firstOrNull { it.lower == 60 && it.upper == 60 }
                    ?: availableFpsRanges?.firstOrNull { it.lower >= 60 && it.upper >= 60 }
                    ?: availableFpsRanges?.firstOrNull { it.lower >= 30 && it.upper >= 60 }
                    ?: availableFpsRanges?.firstOrNull { it.upper >= 60 }
                    ?: availableFpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
                    ?: availableFpsRanges?.firstOrNull { it.upper == 30 }
                    ?: Range(30, 30)
            } else {
                availableFpsRanges?.firstOrNull { it.lower == effectiveFps && it.upper == effectiveFps }
                    ?: availableFpsRanges?.firstOrNull { it.upper == effectiveFps }
                    ?: availableFpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
                    ?: availableFpsRanges?.firstOrNull { it.upper == 30 }
                    ?: Range(30, 30)
            }
            activeFpsRangeStr = selectedRange.toString()
            Log.i("ROUTING_DEBUG", "[Camera2] FPS: camera=$currentCameraFacingName (id=${camera.id}, physId=$activePhysicalCameraId), targetFps=$targetFps, effectiveFps=$effectiveFps, maxSensorFps=$maxSensorFps, selectedRange=$selectedRange")

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRange)
            val faceDetectModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            val canDetectFaces = faceDetectModes?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) == true
            if (isAutoFramingEnabled && canDetectFaces) {
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
            } else {
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
            }
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            hasOis = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            hasEis = eisModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            val stabOis = if (isStabilization && hasOis) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            val stabEis = if (isStabilization && hasEis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, stabOis)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabEis)
            Log.i("ROUTING_DEBUG", "[Stabilization] Applied to session: OIS=$stabOis (avail=$hasOis), EIS=$stabEis (avail=$hasEis), isStabilization=$isStabilization")
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

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    val callback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            if (isAutoFramingEnabled) {
                                val faces = result.get(CaptureResult.STATISTICS_FACES)
                                val isFront = (currentCameraFacingName == "front")
                                glRenderer?.autoFraming?.onFacesDetected(faces, sensorCropRegion, streamOrientation(), isFront)
                            }
                        }
                    }
                    cameraCaptureCallback = callback
                    try {
                        session.setRepeatingRequest(builder.build(), callback, getCameraHandler())
                        Log.i("ROUTING_DEBUG", "Сессия запущена с таргетами: surfaces.size = ${surfaces.size} (экран + кодек), physId=$activePhysicalCameraId")
                        val orient = streamOrientation()
                        val isLand = (orient == 0 || orient == 180)
                        val statusJson = org.json.JSONObject()
                            .put("orient", orient)
                            .put("orientation", if (isLand) "horizontal" else "vertical")
                            .put("facing", currentCameraFacingName)
                            .put("width", targetWidth)
                            .put("height", targetHeight)
                            .put("fps", selectedRange.upper)
                            .toString()
                        streamer?.enqueueStatus(statusJson)
                        streamer?.requestSyncFrame()
                    } catch (e: Exception) {
                        Log.e("ROUTING_DEBUG", "Ошибка setRepeatingRequest: ", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("ROUTING_DEBUG", "Ошибка конфигурации сессии (physId=$activePhysicalCameraId)")
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

            if (activePhysicalCameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { s ->
                    OutputConfiguration(s).apply {
                        setPhysicalCameraId(activePhysicalCameraId)
                    }
                }
                camera.createCaptureSessionByOutputConfigurations(outputConfigs, sessionCallback, getCameraHandler())
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(surfaces, sessionCallback, getCameraHandler())
            }
        } catch (e: Exception) {
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

    fun setOrientation(mode: String) {
        setOrientationInternal(mode)
    }

    private fun setOrientationInternal(mode: String) {
        val normalized = when {
            mode == "auto" -> "auto"
            mode.contains("horiz") || mode.contains("land") -> "horizontal"
            else -> "vertical"
        }
        currentOrientationMode = normalized
        val orient = streamOrientation()
        glRenderer?.setOrientation(orient)
        val isLand = (orient == 0 || orient == 180)
        val json = org.json.JSONObject()
            .put("orient", orient)
            .put("orientation", if (isLand) "horizontal" else "vertical")
            .toString()
        streamer?.enqueueStatus(json)
        streamer?.requestKeyFrame()
        Log.i("ROUTING_DEBUG", "Ориентация установлена: $normalized, streamOrient=$orient")
    }

    private var deviceOrientationDegrees = 0

    fun streamOrientation(): Int {
        val dev = when (currentOrientationMode) {
            "horizontal" -> return 0
            "vertical" -> 0
            else -> deviceOrientationDegrees
        }
        val isFront = (currentCameraFacingName == "front")
        val sensor = if (isFront) 270 else 90
        return if (isFront) (sensor - dev + 360) % 360 else (sensor + dev) % 360
    }

    fun startOrientationDetection() {
        if (orientationListener != null) return
        try {
            orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(degrees: Int) {
                    if (degrees == ORIENTATION_UNKNOWN) return
                    val cur = deviceOrientationDegrees
                    var diff = Math.abs(degrees - cur) % 360
                    if (diff > 180) diff = 360 - diff
                    if (diff < 50) return // гистерезис 50°
                    val snapped = ((degrees + 45) / 90 * 90) % 360
                    if (snapped != cur) {
                        deviceOrientationDegrees = snapped
                        val orient = streamOrientation()
                        glRenderer?.setOrientation(orient)
                        val isLandscape = (orient == 0 || orient == 180)
                        val statusJson = org.json.JSONObject()
                            .put("orient", orient)
                            .put("orientation", if (isLandscape) "horizontal" else "vertical")
                            .put("facing", currentCameraFacingName)
                            .put("width", targetWidth)
                            .put("height", targetHeight)
                            .put("fps", targetFps)
                            .toString()
                        streamer?.enqueueStatus(statusJson)
                        streamer?.requestKeyFrame()
                        Log.i("ROUTING_DEBUG", "[Sensor] Авто-ориентация телефона: snapped=$snapped°, orient=$orient ($degrees°)")
                    }
                }
            }.apply {
                if (canDetectOrientation()) {
                    enable()
                    Log.i("ROUTING_DEBUG", "OrientationEventListener успешно запущен")
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
    }

    fun setAutoFramingEnabled(enabled: Boolean) {
        if (isAutoFraming == enabled) return
        isAutoFraming = enabled

        if (!enabled) {
            glRenderer?.autoFraming?.reset()
        } else {
            glRenderer?.autoFraming?.isEnabled = true
        }

        currentCaptureRequestBuilder?.let { b ->
            val faceMode = if (enabled) CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE else CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
            try {
                cameraCaptureCallback?.let { cb ->
                    cameraCaptureSession?.setRepeatingRequest(b.build(), cb, getCameraHandler())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка переключения детекции лиц", e)
            }
        }
        if (enabled && glRenderer == null) {
            rebindResolutionOnly(targetWidth, targetHeight)
        } else if (!enabled && !useGlPipeline && glRenderer != null) {
            rebindResolutionOnly(targetWidth, targetHeight)
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

                // 2. Сканируем список cameraIdList и потенциальные скрытые aux-камеры (0..32, vendor IDs)
                val candidateIds = linkedSetOf<String>()
                candidateIds.addAll(manager.cameraIdList)
                for (i in 0..32) { candidateIds.add(i.toString()) }
                candidateIds.addAll(listOf("20", "21", "22", "23", "50", "51", "52", "53", "54"))

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

                        if (id != "0") {
                            if ((focal != null && focal < 2.0f) || (eq in 10f..20f)) {
                                hasWide = true
                                hasWideCameraHardware = true
                                ultraWideCameraId = id
                                Log.i("ROUTING_DEBUG", "[Lens] Обнаружен ширик: id=$id, focal=$focal, eq=${eq}mm, inList=${manager.cameraIdList.contains(id)}")
                            } else if (focal != null && focal in 2.0f..3.2f) {
                                Log.i("ROUTING_DEBUG", "[Lens] Обнаружен макро-модуль: id=$id, focal=$focal, eq=${eq}mm")
                            } else if (eq in 45f..70f) {
                                hasTele2 = true
                            } else if ((focal != null && focal > 6.0f) || eq in 71f..110f) {
                                hasTele3 = true
                                teleCameraId = id
                                Log.i("ROUTING_DEBUG", "[Lens] Обнаружен телевик: id=$id, focal=$focal, eq=${eq}mm")
                            } else if (eq > 110f) {
                                hasTele5 = true
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

                                if ((pfocal != null && pfocal < 3.2f) || (peq in 10f..22f)) {
                                    hasWide = true
                                    ultraWidePhysicalId = pid
                                    Log.i("ROUTING_DEBUG", "[Samsung Lens] Обнаружен физический ширик: pid=$pid, focal=$pfocal, eq=${peq}mm")
                                } else if ((pfocal != null && pfocal > 6.0f) || peq in 71f..110f) {
                                    hasTele3 = true
                                    telePhysicalId = pid
                                    Log.i("ROUTING_DEBUG", "[Samsung Lens] Обнаружен физический телевик: pid=$pid, focal=$pfocal, eq=${peq}mm")
                                }
                            }
                        }
                    }
                }
            }

            lenses.add("0.5x")
            lenses.add("1x")
            if (hasTele2) lenses.add("2x")
            if (hasTele3) lenses.add("3x")
            if (hasTele5) lenses.add("5x")
            if (hasTele10) lenses.add("10x")
            Log.i("ROUTING_DEBUG", "getAvailableLenses -> $lenses (hasWide=$hasWide, ultraWideCam=$ultraWideCameraId, teleCam=$teleCameraId, ultraWidePhys=$ultraWidePhysicalId, telePhys=$telePhysicalId)")
            cachedAvailableLenses = lenses
            return lenses
        } catch (e: Exception) {
            Log.w("ROUTING_DEBUG", "getAvailableLenses error", e)
        }
        val fallback = listOf("0.5x", "1x", "2x")
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
                val safe30Range = availableFpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
                    ?: availableFpsRanges?.firstOrNull { it.upper == 30 }
                    ?: availableFpsRanges?.firstOrNull { it.upper <= 30 }
                    ?: Range(15, 30)
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, safe30Range)
            } else {
                val effectiveFps = minOf(targetFps, activeMaxFps)
                val normalRange = if (effectiveFps >= 60) {
                    availableFpsRanges?.firstOrNull { it.lower == 60 && it.upper == 60 }
                        ?: availableFpsRanges?.firstOrNull { it.lower >= 30 && it.upper >= 60 }
                        ?: availableFpsRanges?.firstOrNull { it.upper >= 60 }
                        ?: Range(30, 30)
                } else {
                    availableFpsRanges?.firstOrNull { it.lower == effectiveFps && it.upper == effectiveFps }
                        ?: availableFpsRanges?.firstOrNull { it.upper == effectiveFps }
                        ?: Range(30, 30)
                }
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, normalRange)
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
        currentLensName = lens
        Log.i("ROUTING_DEBUG", "Запрос смены линзы: $lens")
        if (lens.lowercase() == "0.5x") {
            currentLensName = "0.5x"
            currentZoomRatio = 0.5f

            // Если были на фронталке или другой камере, возвращаемся на основную Камеру 0
            if (currentCameraFacingName != "back" || currentExplicitCameraId != null) {
                currentCameraFacingName = "back"
                currentExplicitCameraId = null
                activePhysicalCameraId = null
                bindCamera()
                return
            }

            // Бесшовный переход на ширик внутри открытой сессии Камеры 0 (без деструктивного openCamera)
            applyZoomToSession()
            Log.i("ROUTING_DEBUG", "0.5x применен через бесшовный аппаратный зум 0.5x в сессии Камеры 0")
            return
        }

        // Если переключаемся с ширика обратно на 1x, 2x, 3x и была открыта отдельная камера,
        // возвращаемся на основную камеру "0"
        if (currentExplicitCameraId != null) {
            currentExplicitCameraId = null
            currentCameraFacingName = "back"
            bindCamera()
        }

        if (activePhysicalCameraId != null) {
            activePhysicalCameraId = null
            useGlPipeline = false
            restartCameraSessionWithPhysicalId(null)
        }

        val targetRatio = when (lens.lowercase()) {
            "1x" -> 1.0f
            "2x" -> minOf(2.0f, maxZoomRatio)
            "3x" -> minOf(3.0f, maxZoomRatio)
            "5x" -> minOf(5.0f, maxZoomRatio)
            "10x" -> minOf(10.0f, maxZoomRatio)
            else -> {
                val parsed = lens.replace("x", "", ignoreCase = true).trim().toFloatOrNull() ?: 1.0f
                parsed.coerceIn(minZoomRatio, maxZoomRatio)
            }
        }
        setZoomRatio(targetRatio)
    }

    private fun applyZoomToSession() {
        val session = cameraCaptureSession ?: return
        val builder = currentCaptureRequestBuilder ?: return
        getCameraHandler().post {
            try {
                applyZoomToBuilder(builder)
                session.setRepeatingRequest(builder.build(), cameraCaptureCallback, getCameraHandler())
                Log.i("ROUTING_DEBUG", "Зум успешно применен в активной сессии: ratio=$currentZoomRatio, lens=$currentLensName")
                streamer?.requestSyncFrame()
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка applyZoomToSession: ", e)
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
                val oisVal = if (enabled && hasOis) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                val eisVal = if (enabled && hasEis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
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

            // В. Телефото 2.0x (Телевик 2x)
            val hasTele2 = availableBackLenses.contains("2x") || effectiveMaxZoom >= 1.9f
            val hasTele3 = availableBackLenses.contains("3x") || telePhysicalId != null || teleCameraId != null || effectiveMaxZoom >= 2.9f || (backFocals != null && backFocals.any { it > 6.0f })

            if (hasTele2) {
                units.add(CameraUnitInfo(
                    id = "lens_2x",
                    name = "2.0x Tele",
                    type = "tele",
                    facing = "back",
                    focalLengthMm = mainFocal * 2.0f,
                    hasOis = backHasOis,
                    isPhysical = false
                ))
            }

            // Г. Телефото 3.0x (Телевик 3x)
            if (hasTele3) {
                val teleFocal = backFocals?.firstOrNull { it > 6.0f } ?: (mainFocal * 3.0f)
                units.add(CameraUnitInfo(
                    id = "lens_3x",
                    name = "3.0x Tele",
                    type = "tele",
                    facing = "back",
                    focalLengthMm = teleFocal,
                    hasOis = backHasOis,
                    isPhysical = false
                ))
            }

            // Д. Перископ 5.0x / 10x
            if (availableBackLenses.contains("5x") || effectiveMaxZoom >= 4.9f) {
                units.add(CameraUnitInfo(
                    id = "lens_5x",
                    name = "5.0x Periscope",
                    type = "tele",
                    facing = "back",
                    focalLengthMm = mainFocal * 5.0f,
                    hasOis = backHasOis,
                    isPhysical = false
                ))
            }

            // Е. Физически открываемые камеры строго из manager.cameraIdList (исключая "0", который уже добавлен)
            for (id in manager.cameraIdList) {
                if (id == "0" || units.any { it.id == id }) continue
                val ch = try { manager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
                val facingInt = ch.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> continue
                }
                val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focals?.firstOrNull() ?: 0.0f
                val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val unitHasOis = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                val name = if (facingStr == "front") {
                    if (id == "1") "Фронтальная (Селфи)" else "Фронтальная #$id"
                } else {
                    "Камера #$id"
                }
                units.add(CameraUnitInfo(
                    id = id,
                    name = name,
                    type = if (facingStr == "front") "front" else "wide",
                    facing = facingStr,
                    focalLengthMm = focal,
                    hasOis = unitHasOis,
                    isPhysical = false
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
        if (unitId == "0.5x" || unitId == "lens_0.5x" || unitId == "50" || (ultraWidePhysicalId != null && unitId == ultraWidePhysicalId)) {
            if (currentCameraFacingName != "back") {
                currentCameraFacingName = "back"
                currentExplicitCameraId = null
                activePhysicalCameraId = null
                bindCamera()
            }
            setLens("0.5x")
            return
        }
        if (unitId.startsWith("lens_")) {
            val lensName = unitId.removePrefix("lens_")
            if (currentCameraFacingName != "back") {
                currentCameraFacingName = "back"
                currentExplicitCameraId = null
                activePhysicalCameraId = null
                bindCamera()
            }
            setLens(lensName)
            return
        }
        if (unitId == "1x" || unitId == "0") {
            if (currentCameraFacingName != "back" || currentExplicitCameraId != null || activePhysicalCameraId != null) {
                currentCameraFacingName = "back"
                currentExplicitCameraId = null
                activePhysicalCameraId = null
                bindCamera()
            }
            setLens("1x")
            return
        }
        if (unitId == "2x" || unitId == "3x" || unitId == "5x" || unitId == "10x") {
            if (currentCameraFacingName != "back") {
                currentCameraFacingName = "back"
                currentExplicitCameraId = null
                activePhysicalCameraId = null
                bindCamera()
            }
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
            bindCamera()
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager != null && manager.cameraIdList.contains(unitId)) {
            val ch = try { manager.getCameraCharacteristics(unitId) } catch (_: Exception) { null }
            val facingInt = ch?.get(CameraCharacteristics.LENS_FACING)
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
            Log.w("ROUTING_DEBUG", "Модуль камеры $unitId не найден в cameraIdList, мягкий откат на 1x")
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
            pipelineMode = if (useGlPipeline || isAutoFramingEnabled) "gl" else "direct",
            availableLenses = getAvailableLenses(),
            isStabilization = isStabilization,
            hasOis = hasOis,
            hasEis = hasEis,
            currentCameraUnit = when {
                currentCameraFacingName == "front" -> (currentExplicitCameraId ?: "1")
                currentExplicitCameraId != null && currentExplicitCameraId == ultraWideCameraId -> "0.5x"
                currentExplicitCameraId != null -> currentExplicitCameraId!!
                currentLensName == "0.5x" -> "0.5x"
                currentLensName == "2x" -> "lens_2x"
                currentLensName == "3x" -> "lens_3x"
                currentLensName == "5x" -> "lens_5x"
                currentLensName == "10x" -> "lens_10x"
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

    override fun onRequestUserPairing(clientId: String, clientName: String, onDecision: (Boolean) -> Unit) {
        Log.i("ROUTING_DEBUG", "onRequestUserPairing: clientId=$clientId, clientName=$clientName")
        Handler(Looper.getMainLooper()).post {
            try {
                if (Settings.canDrawOverlays(this)) {
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Запрос подключения")
                        .setMessage("Компьютер \"$clientName\" запрашивает доступ к камере. Разрешить подключение?")
                        .setCancelable(false)
                        .setPositiveButton("Разрешить") { _, _ -> onDecision(true) }
                        .setNegativeButton("Отклонить") { _, _ -> onDecision(false) }
                        .create().apply {
                            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            show()
                        }
                } else {
                    Log.w("ROUTING_DEBUG", "Разрешение на оверлей отсутствует, авто-подтверждение сопряжения для $clientName")
                    onDecision(true)
                }
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка отображения диалога сопряжения", e)
                onDecision(true)
            }
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
        val camera = cameraDevice
        if (camera != null) {
            startCamera2Session(camera)
        } else {
            bindCamera()
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

    fun applyStreamConfig(resolution: String, fps: Int, bitrate: Int) {
        val now = System.currentTimeMillis()
        if (now - lastStreamConfigTime < 300) {
            Log.w("ROUTING_DEBUG", "applyStreamConfig: пропуск быстрого повтора (дребезг ${now - lastStreamConfigTime}мс)")
            return
        }
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

        Log.i("ROUTING_DEBUG", "applyStreamConfig: resolution=$resolution (${newWidth}x${newHeight}), fps=$fps, bitrate=$bitrate | isResChanged=$isResChanged, isFpsChanged=$isFpsChanged, isBitrateChanged=$isBitrateChanged")

        targetBitrate = bitrate
        targetFps = fps
        targetWidth = newWidth
        targetHeight = newHeight
        saveCameraPrefs()

        if (!isResChanged && !isFpsChanged && isBitrateChanged) {
            updateBitrateOnTheFly(bitrate)
            return
        }

        if (isResChanged || isFpsChanged) {
            rebindResolutionOnly(newWidth, newHeight)
            return
        }
    }

    fun switchCamera() {
        if (isSwitching) return
        isSwitching = true

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
        bindCamera()
    }

    fun toggleTorch() {
        if (currentCameraFacingName != "back") return
        isTorchOn = !isTorchOn
        Log.i("ROUTING_DEBUG", "toggleTorch: isTorchOn = $isTorchOn")

        val camera = cameraDevice
        if (camera != null) {
            startCamera2Session(camera)
        }
    }

    interface ServiceStatusListener {
        fun onStreamingStatusChanged(isStreaming: Boolean)
    }

    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<ServiceStatusListener>()

    fun addStatusListener(listener: ServiceStatusListener) {
        statusListeners.add(listener)
        listener.onStreamingStatusChanged(isStreaming)
    }

    fun removeStatusListener(listener: ServiceStatusListener) {
        statusListeners.remove(listener)
    }

    private fun notifyStatusChanged(streaming: Boolean) {
        isStreaming = streaming
        for (l in statusListeners) {
            try { l.onStreamingStatusChanged(streaming) } catch (_: Exception) {}
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
