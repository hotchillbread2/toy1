package com.example.spamdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class CaptureOverlayService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var searchButton: ImageButton? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultCode != -1 && resultData != null) startProjection(resultCode, resultData)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        if (searchButton != null) return
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, null)
        addSearchButton()
    }

    private fun addSearchButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        searchButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setBackgroundColor(0xFF6366F1.toInt())
            contentDescription = "현재 화면 약관 검색"
            setOnClickListener { captureScreen() }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            58, 58, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 20
        }
        windowManager?.addView(searchButton, params)
    }

    private fun captureScreen() {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = projection?.createVirtualDisplay(
            "terms-capture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
        searchButton?.isEnabled = false
        searchButton?.postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                searchButton?.isEnabled = true
                releaseCapture()
                return@postDelayed
            }
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * metrics.widthPixels
            val bitmap = Bitmap.createBitmap(
                metrics.widthPixels + rowPadding / plane.pixelStride,
                metrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(plane.buffer)
            image.close()
            val file = File(cacheDir, "terms-capture-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { output ->
                Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
            releaseCapture()
            startActivity(Intent(this, ContractAnalysisActivity::class.java).apply {
                putExtra(ContractAnalysisActivity.EXTRA_CAPTURE_PATH, file.absolutePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            searchButton?.isEnabled = true
        }, 250)
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    override fun onDestroy() {
        releaseCapture()
        searchButton?.let { windowManager?.removeView(it) }
        searchButton = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "약관 화면 검색", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("약관 검색 아이콘 실행 중")
            .setContentText("화면 위 검색 아이콘을 눌러 약관을 분석하세요.")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.spamdetector.START_CAPTURE_OVERLAY"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "terms_capture_overlay"
        private const val NOTIFICATION_ID = 7001
    }
}