package com.example.ccamera

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
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

            binding.viewFinder.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            cameraService?.attachSurfaceProvider(binding.viewFinder.surfaceProvider)
            Log.i(TAG, "Успешно привязаны к FloatingCameraService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isServiceBound = false
            Log.i(TAG, "Отвязаны от FloatingCameraService")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startAndBindCameraService()
        } else {
            Toast.makeText(this, "Требуется разрешение на использование камеры", Toast.LENGTH_LONG).show()
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

        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startAndBindCameraService()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            cameraService?.detachSurfaceProvider()
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun checkAndRequestPermissions() {
        // Проверка разрешения на камеру
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startAndBindCameraService()
        }

        // Проверка разрешения на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Проверка разрешения на отображение поверх других окон
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
            cameraService?.detachSurfaceProvider()
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
