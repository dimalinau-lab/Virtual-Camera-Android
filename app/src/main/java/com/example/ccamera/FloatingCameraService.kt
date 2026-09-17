package com.example.ccamera

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.view.GestureDetector
import android.view.MotionEvent
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

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var displaySurface: Surface? = null

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
    private var udpDiscoveryBroadcaster: UdpDiscoveryBroadcaster? = null

    private var currentCameraFacingName: String = "back"
    private var isTorchOn: Boolean = false
    private var isStreaming: Boolean = false
    private var currentOrientationMode: String = "vertical"

    private var targetWidth: Int = 1280
    private var targetHeight: Int = 720
    private var targetFps: Int = 30
    private var targetBitrate: Int = 7_000_000

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

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        overlayController = OverlayController(this)

        acquireLocks()
        startControlServer()
        startForegroundWithStatus("Ожидание подключения ПК...")

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL
        udpDiscoveryBroadcaster = UdpBeaconBroadcaster(deviceId).apply {
            start()
        }

        Log.i(TAG, "FloatingCameraService создан, UDP маяк и Foreground запущены")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE || intent?.action == ACTION_STOP) {
            Log.i(TAG, "Получен запрос на остановку сервиса через Intent")
            disableBlackout()
            overlayController?.hideOverlay()
            udpDiscoveryBroadcaster?.stop()
            udpDiscoveryBroadcaster = null
            stopStreamingInternal()
            controlServer?.stop()
            controlServer = null
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startCameraOnce()
        return START_STICKY
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

        disableBlackout()
        overlayController?.hideOverlay()
        overlayController = null

        udpDiscoveryBroadcaster?.stop()
        udpDiscoveryBroadcaster = null

        stopStreamingInternal()
        controlServer?.stop()
        controlServer = null

        releaseLocks()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun startForegroundWithStatus(statusText: String) {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtualCam Native")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить камеру",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

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
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtualCam Native")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить камеру",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    // --- Управление Blackout (затемнение экрана) ---

    fun toggleBlackout() {
        if (isBlackoutEnabled) {
            disableBlackout()
        } else {
            enableBlackout()
        }
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
        bindCamera()
    }

    fun stopStreamingInternal() {
        isStreaming = false
        isCameraInitialized = false
        try {
            audioStreamer?.stop()
        } catch (_: Exception) {}
        audioStreamer = null

        closeCameraSync()

        try {
            streamer?.stop()
        } catch (_: Exception) {}
        streamer = null

        updateNotification("Отключено по команде ПК")
        overlayController?.setStatus(OverlayController.Status.WAITING)
    }

    fun setPreviewDisplaySurface(surface: Surface?) {
        displaySurface = surface
        isActivityInForeground = (surface != null && surface.isValid)
        if (surface == null && isStreaming) {
            overlayController?.showOverlay()
        } else if (surface != null) {
            overlayController?.hideOverlay()
        }
        Log.i("ROUTING_DEBUG", "setPreviewDisplaySurface: surface=$surface, isValid=${surface?.isValid}")

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

                val codecSurf = activeStreamer.inputSurface
                if (codecSurf == null || !codecSurf.isValid) {
                    Log.e("ROUTING_DEBUG", "codecSurf невалиден в bindCamera")
                    isCameraBusy = false
                    return@post
                }

                val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val targetLensFacing = if (currentCameraFacingName == "back") CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
                var selectedCameraId: String? = null
                for (id in manager.cameraIdList) {
                    val chars = manager.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.LENS_FACING) == targetLensFacing) {
                        if (selectedCameraId == null || (targetLensFacing == CameraCharacteristics.LENS_FACING_BACK && id == "0")) {
                            selectedCameraId = id
                        }
                    }
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
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e("ROUTING_DEBUG", "Camera2 openCamera onError: $error")
                            closeCameraSync()
                            isCameraBusy = false
                        }
                    }, getCameraHandler())
                } else {
                    Log.e("ROUTING_DEBUG", "selectedCameraId null или нет разрешения CAMERA")
                    isCameraBusy = false
                }
            } catch (e: Exception) {
                Log.e("ROUTING_DEBUG", "Ошибка bindCamera Camera2: ", e)
                isCameraBusy = false
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
            if (codecSurface.isValid) {
                surfaces.add(codecSurface)
            }

            val dispSurf = displaySurface
            if (dispSurf != null && dispSurf.isValid) {
                surfaces.add(dispSurf)
                Log.i("ROUTING_DEBUG", "Добавлена поверхность локального видоискателя ($dispSurf)")
            } else {
                Log.w("ROUTING_DEBUG", "displaySurface null или невалиден: $dispSurf")
            }

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (s in surfaces) {
                builder.addTarget(s)
            }

            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(camera.id)
            val availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val maxSensorFps = availableFpsRanges?.map { it.upper }?.maxOrNull() ?: 30

            val selectedRange = if (targetFps >= 60 && maxSensorFps >= 60) {
                availableFpsRanges?.firstOrNull { it.upper >= 60 } ?: Range(30, 60)
            } else {
                availableFpsRanges?.firstOrNull { it.upper == 30 && it.lower >= 15 } ?: Range(30, 30)
            }

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRange)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
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

            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, getCameraHandler())
                        Log.i("ROUTING_DEBUG", "Сессия запущена с таргетами: surfaces.size = ${surfaces.size} (экран + кодек)")
                        streamer?.requestSyncFrame()
                    } catch (e: Exception) {
                        Log.e("ROUTING_DEBUG", "Ошибка setRepeatingRequest: ", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("ROUTING_DEBUG", "Ошибка конфигурации сессии")
                }
            }, getCameraHandler())
        } catch (e: Exception) {
            Log.e("ROUTING_DEBUG", "Ошибка создания Camera2 сессии: ", e)
        }
    }

    private fun setOrientationInternal(mode: String) {
        val normalized = if (mode.contains("horiz") || mode.contains("land")) "horizontal" else "vertical"
        currentOrientationMode = normalized
        Log.i("ROUTING_DEBUG", "Ориентация установлена: $normalized")
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

    override fun onActionRequested(action: String) {
        Log.i("ROUTING_DEBUG", "onActionRequested: action=$action")
        ContextCompat.getMainExecutor(this).execute {
            when (action) {
                "switch_camera" -> switchCamera()
                "toggle_torch" -> toggleTorch()
                "toggle_blackout" -> toggleBlackout()
                "toggle_mic_mute" -> toggleMicMute()
            }
        }
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
        return ControlServer.StatusInfo(
            isStreaming = isStreaming,
            cameraFacing = currentCameraFacingName,
            isTorchOn = isTorchOn,
            orientation = currentOrientationMode,
            isMicMuted = audioStreamer?.isMuted ?: true
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
            else -> Pair(1280, 720)
        }

        val isResChanged = (newWidth != targetWidth || newHeight != targetHeight)
        val isFpsChanged = (fps != targetFps)
        val isBitrateChanged = (bitrate != targetBitrate)

        Log.i("ROUTING_DEBUG", "applyStreamConfig: resolution=$resolution (${newWidth}x${newHeight}), fps=$fps, bitrate=$bitrate | isResChanged=$isResChanged, isFpsChanged=$isFpsChanged, isBitrateChanged=$isBitrateChanged")

        if (!isResChanged && !isFpsChanged && isBitrateChanged) {
            updateBitrateOnTheFly(bitrate)
            return
        }

        if (!isResChanged && isFpsChanged) {
            if (isBitrateChanged) {
                updateBitrateOnTheFly(bitrate)
            }
            updateFpsOnTheFly(fps)
            return
        }

        if (isResChanged) {
            targetBitrate = bitrate
            targetFps = fps
            rebindResolutionOnly(newWidth, newHeight)
            return
        }
    }

    private fun switchCamera() {
        if (isSwitching) return

        currentCameraFacingName = if (currentCameraFacingName == "back") "front" else "back"

        if (currentCameraFacingName == "front" && isTorchOn) {
            isTorchOn = false
        }

        bindCamera()
    }

    private fun toggleTorch() {
        if (currentCameraFacingName != "back") return
        isTorchOn = !isTorchOn
        Log.i("ROUTING_DEBUG", "toggleTorch: isTorchOn = $isTorchOn")

        val camera = cameraDevice
        if (camera != null) {
            startCamera2Session(camera)
        }
    }

    // --- RawH265Streamer.StatusListener ---

    override fun onStatusChanged(status: String, isConnected: Boolean) {
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

        udpDiscoveryBroadcaster?.stop()
        udpDiscoveryBroadcaster = null

        releaseLocks()
        stopStreamingInternal()
        controlServer?.stop()
        controlServer = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        super.onDestroy()
        Log.i(TAG, "FloatingCameraService остановлен, камера и ресурсы полностью освобождены")
    }
}
