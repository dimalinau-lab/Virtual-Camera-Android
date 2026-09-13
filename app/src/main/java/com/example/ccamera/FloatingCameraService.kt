package com.example.ccamera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CaptureRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

@ExperimentalCamera2Interop
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
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null

    private var activeScreenPreview: Preview? = null
    private var activeStreamPreview: Preview? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var overlayController: OverlayController? = null
    private var blackoutOverlayView: View? = null
    private var isBlackoutEnabled = false
    private var udpDiscoveryBroadcaster: UdpDiscoveryBroadcaster? = null

    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentCameraFacingName: String = "back"
    private var isTorchOn: Boolean = false
    private var isStreaming: Boolean = false
    private var currentOrientationMode: String = "vertical"

    private var targetWidth: Int = 1280
    private var targetHeight: Int = 720
    private var targetFps: Int = 30
    private var targetBitrate: Int = 7_000_000

    private var externalSurfaceProvider: Preview.SurfaceProvider? = null
    private var isActivityInForeground = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        overlayController = OverlayController(this)

        acquireLocks()
        startControlServer()
        startForegroundWithStatus("Ожидание подключения ПК...")

        udpDiscoveryBroadcaster = UdpDiscoveryBroadcaster().apply {
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

        startCameraAndStreamerInternal()
        return START_STICKY
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
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
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
            controlServer = ControlServer(port = 8080, callback = this).apply {
                start()
            }
            Log.i(TAG, "ControlServer запущен на порту 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска ControlServer", e)
        }
    }

    fun startCameraAndStreamerInternal() {
        if (streamer == null) {
            try {
                streamer = RawH265Streamer(
                    port = 8554,
                    width = targetWidth,
                    height = targetHeight,
                    bitRate = targetBitrate,
                    frameRate = targetFps,
                    listener = this
                ).apply {
                    start()
                }
                isStreaming = true
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации RawH265Streamer", e)
                overlayController?.setStatus(OverlayController.Status.ERROR)
            }
        }
        bindCameraX()
    }

    fun stopStreamingInternal() {
        isStreaming = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        boundCamera = null
        activeScreenPreview = null
        activeStreamPreview = null

        try {
            streamer?.stop()
        } catch (_: Exception) {}
        streamer = null

        updateNotification("Отключено по команде ПК")
        overlayController?.setStatus(OverlayController.Status.WAITING)
    }

    fun attachSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        externalSurfaceProvider = surfaceProvider
        isActivityInForeground = true
        overlayController?.hideOverlay()
        bindCameraX()
    }

    fun detachSurfaceProvider() {
        externalSurfaceProvider = null
        isActivityInForeground = false
        if (isStreaming) {
            overlayController?.showOverlay()
        }
        bindCameraX()
    }

    @ExperimentalCamera2Interop
    private fun bindCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val rotation = if (currentOrientationMode == "horizontal") Surface.ROTATION_90 else Surface.ROTATION_0

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(targetWidth, targetHeight),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val useCases = ArrayList<Preview>()

                // 1. Экранный Preview (для MainActivity)
                val extProvider = externalSurfaceProvider
                if (extProvider != null) {
                    val screenPreview = Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                    screenPreview.setSurfaceProvider(extProvider)
                    activeScreenPreview = screenPreview
                    useCases.add(screenPreview)
                } else {
                    activeScreenPreview = null
                }

                // 2. Zero-Copy Preview под аппаратный Surface MediaCodec
                val codecSurface = streamer?.inputSurface
                if (codecSurface != null && codecSurface.isValid) {
                    val streamPreviewBuilder = Preview.Builder()
                        .setTargetRotation(rotation)
                        .setResolutionSelector(resolutionSelector)

                    val extBuilder = Camera2Interop.Extender(streamPreviewBuilder)
                    extBuilder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(targetFps, targetFps)
                    )

                    val streamPreview = streamPreviewBuilder.build()

                    streamPreview.setSurfaceProvider { request ->
                        val surface = streamer?.inputSurface
                        if (surface != null && surface.isValid) {
                            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {}
                            streamer?.requestKeyFrame()
                        } else {
                            request.willNotProvideSurface()
                        }
                    }
                    activeStreamPreview = streamPreview
                    useCases.add(streamPreview)
                } else {
                    activeStreamPreview = null
                }

                if (useCases.isNotEmpty()) {
                    try {
                        boundCamera = provider.bindToLifecycle(this, currentCameraSelector, *useCases.toTypedArray())
                        Log.i(TAG, "CameraX успешно привязана к Service (${useCases.size} use-cases, mode=$currentOrientationMode)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Сбой совместной привязки use-cases, пробуем только streamPreview: ${e.message}")
                        if (useCases.size > 1) {
                            boundCamera = provider.bindToLifecycle(this, currentCameraSelector, useCases.last())
                        }
                    }
                }

                if (isTorchOn && currentCameraFacingName == "back") {
                    boundCamera?.cameraControl?.enableTorch(true)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка привязки CameraX в сервисе", e)
                overlayController?.setStatus(OverlayController.Status.ERROR)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setOrientationInternal(mode: String) {
        val normalized = if (mode.contains("horiz") || mode.contains("land")) "horizontal" else "vertical"

        currentOrientationMode = normalized
        val rotation = if (normalized == "horizontal") Surface.ROTATION_90 else Surface.ROTATION_0

        try {
            activeStreamPreview?.targetRotation = rotation
            activeScreenPreview?.targetRotation = rotation
            bindCameraX()
            streamer?.requestKeyFrame()
            Log.i(TAG, "Мгновенно обновлена ориентация кадра: $normalized (rotation=$rotation)")
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка установки ориентации CameraX", e)
            bindCameraX()
        }
    }

    // --- ControlServer.ControlCallback ---

    override fun onConnectRequested(mode: String) {
        startCameraAndStreamerInternal()
        streamer?.requestKeyFrame()
        updateNotification("Стриминг активен (ПК подключен)")
        overlayController?.setStatus(OverlayController.Status.STREAMING)
    }

    override fun onDisconnectRequested() {
        stopStreamingInternal()
    }

    override fun onActionRequested(action: String) {
        ContextCompat.getMainExecutor(this).execute {
            when (action) {
                "switch_camera" -> switchCamera()
                "toggle_torch" -> toggleTorch()
                "toggle_blackout" -> toggleBlackout()
            }
        }
    }

    override fun onOrientationRequested(mode: String) {
        ContextCompat.getMainExecutor(this).execute {
            setOrientationInternal(mode)
        }
    }

    override fun onConfigUpdated(resolution: String, fps: Int, bitrate: Int) {
        applyStreamConfig(resolution, fps, bitrate)
    }

    override fun getStatus(): ControlServer.StatusInfo {
        return ControlServer.StatusInfo(
            isStreaming = isStreaming,
            cameraFacing = currentCameraFacingName,
            isTorchOn = isTorchOn,
            orientation = currentOrientationMode
        )
    }

    fun applyStreamConfig(resolution: String, fps: Int, bitrate: Int) {
        val (newWidth, newHeight) = when (resolution.lowercase()) {
            "720p" -> Pair(1280, 720)
            "1080p" -> Pair(1920, 1080)
            "4k", "2160p" -> Pair(3840, 2160)
            else -> Pair(1280, 720)
        }

        val isResOrFpsChanged = (newWidth != targetWidth || newHeight != targetHeight || fps != targetFps)

        targetWidth = newWidth
        targetHeight = newHeight
        targetFps = fps

        if (isStreaming) {
            if (!isResOrFpsChanged) {
                targetBitrate = bitrate
                streamer?.updateBitrate(bitrate)
            } else {
                targetBitrate = bitrate
                stopStreamingInternal()
                startCameraAndStreamerInternal()
            }
        } else {
            targetBitrate = bitrate
        }
    }

    private fun switchCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            currentCameraFacingName = "front"
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            currentCameraFacingName = "back"
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        if (currentCameraFacingName == "front" && isTorchOn) {
            isTorchOn = false
        }

        bindCameraX()
    }

    private fun toggleTorch() {
        if (currentCameraFacingName == "back") {
            isTorchOn = !isTorchOn
            boundCamera?.cameraControl?.enableTorch(isTorchOn)
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

        super.onDestroy()
        Log.i(TAG, "FloatingCameraService остановлен, камера и ресурсы полностью освобождены")
    }
}
