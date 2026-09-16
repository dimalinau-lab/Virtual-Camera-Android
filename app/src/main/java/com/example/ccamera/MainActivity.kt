package com.example.ccamera

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ccamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var gestureDetector: GestureDetector

    private var cameraService: FloatingCameraService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? FloatingCameraService.LocalBinder
            cameraService = localBinder?.getService()
            isServiceBound = true

            setupPreviewSurface()
            cameraService?.startCameraOnce()
            updateMuteButtonUI(cameraService?.isMicMuted() ?: true)
            Log.i(TAG, "Успешно привязаны к FloatingCameraService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
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

        binding.btnMuteMic.setOnClickListener {
            val isMuted = cameraService?.toggleMicMute() ?: true
            updateMuteButtonUI(isMuted)
        }

        updateMuteButtonUI(true)
        checkAndRequestPermissions()
    }

    private fun updateMuteButtonUI(isMuted: Boolean) {
        if (isMuted) {
            binding.btnMuteMic.setImageResource(R.drawable.ic_mic_off)
            binding.btnMuteMic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935"))
            binding.btnMuteMic.imageTintList = ColorStateList.valueOf(Color.WHITE)
        } else {
            binding.btnMuteMic.setImageResource(R.drawable.ic_mic)
            binding.btnMuteMic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#80000000"))
            binding.btnMuteMic.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
    }

    override fun onStart() {
        super.onStart()
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
                val aspectRatio = Rational(9, 16)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
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
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return
        }
        if (isServiceBound) {
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
        if (isServiceBound) {
            cameraService?.setPreviewDisplaySurface(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
