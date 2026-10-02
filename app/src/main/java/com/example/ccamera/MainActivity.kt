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

    private var cameraService: FloatingCameraService? = null
    private var isServiceBound = false

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
                    paramsBuilder.setAutoEnterEnabled(true)
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
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешение на оверлей получено", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Оверлей-виджет будет недоступен без разрешения", Toast.LENGTH_LONG).show()
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
                bottomLp.bottomMargin = maxOf(navBars.bottom + (16 * density).toInt(), baseMargin)
                binding.bottomActionBar.layoutParams = bottomLp
            }

            windowInsets
        }

        // 2. Выход из блэкаута по двойному тапу
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                disableBlackout()
                return true
            }
        })

        binding.blackoutOverlay.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            true
        }

        binding.btnFlipCamera.setOnClickListener {
            cameraService?.switchCamera()
        }

        binding.btnFloatingMode.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                cameraService?.showFloatingOverlay()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Предоставьте разрешение для плавающего виджета", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
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

        updateMuteButtonUI(cameraService?.isMicMuted() ?: false)
        checkAndRequestPermissions()
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
        } else {
            binding.statusText.text = "ОЖИДАНИЕ ПК"
            binding.statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFC107"))
        }
    }

    private fun enableBlackout() {
        binding.blackoutOverlay.visibility = View.VISIBLE
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
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

        if (!Settings.canDrawOverlays(this)) {
            try {
                Toast.makeText(this, "Предоставьте разрешение для оверлей-виджета", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка открытия настроек оверлея", e)
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
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
    }

    override fun onDestroy() {
        super.onDestroy()
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
