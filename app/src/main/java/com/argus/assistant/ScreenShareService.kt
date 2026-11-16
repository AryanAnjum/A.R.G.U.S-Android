package com.argus.assistant

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * Screen mode: shares this phone's screen (not the camera) with ARGUS. Android asks the user first,
 * then this foreground service keeps a picture of the screen and sends it to the PC about once a second
 * when it changes. While sharing, ARGUS keeps listening and answering when you switch to other apps.
 */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var lastSent = 0L
    @Volatile private var pending: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())

    private val model: ArgusModel get() = (application as ArgusApplication).model

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null || projection != null) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+ needs the service in the foreground (with the media projection type) before capture starts.
        try {
            goForeground()
        } catch (e: Exception) {
            main.post { model.system("Android didn't allow screen sharing: ${e.message ?: "unknown error"}") }
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            stopSharing()
            return START_NOT_STICKY
        }
        projection = mp
        startCapture(mp)
        main.post { model.screenShareStarted() }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ARGUS can see your screen")
            .setContentText("Ask \"Hey Argus, what's on my screen?\" from any app.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // With the microphone type too, "Hey Argus" keeps working while you are in other apps.
            if (Build.VERSION.SDK_INT >= 30 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCapture(mp: MediaProjection) {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WindowManager::class.java)
        val (screenW, screenH) = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        val density = resources.displayMetrics.densityDpi
        // The picture is made at most 1600 px on its long side: enough to read text, light on Wi-Fi.
        val scale = minOf(1f, FrameConfig.SCREEN_PX.toFloat() / maxOf(screenW, screenH))
        val width = (screenW * scale).toInt().coerceAtLeast(2) and 1.inv()
        val height = (screenH * scale).toInt().coerceAtLeast(2) and 1.inv()

        val t = HandlerThread("argus-screen").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        worker = handler
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSharing() }
            }
        }, main)
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val image = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val exact = if (rowPixels == image.width) padded
                else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
                pending?.recycle()
                pending = exact
            } catch (e: Exception) {
                // skip this picture
            } finally {
                image.close()
            }
            sendIfDue()
        }, handler)
        display = mp.createVirtualDisplay(
            "ARGUS screen", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        handler.post(tick)
    }

    /** Runs on the capture thread: sends the newest picture if the last one went out long enough ago. */
    private fun sendIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent < FrameConfig.SCREEN_INTERVAL_MS) return
        val bitmap = pending ?: return
        pending = null
        lastSent = now
        try {
            model.sendScreenFrame(CameraController.toBase64Jpeg(bitmap, 80))
        } finally {
            bitmap.recycle()
        }
    }

    /** A change that came in just after a send goes out on the next tick. */
    private val tick = object : Runnable {
        override fun run() {
            sendIfDue()
            worker?.postDelayed(this, 400)
        }
    }

    private fun stopSharing() {
        val wasSharing = projection != null
        worker?.removeCallbacksAndMessages(null)
        try {
            display?.release()
        } catch (e: Exception) {
            // already released
        }
        display = null
        try {
            reader?.close()
        } catch (e: Exception) {
            // already closed
        }
        reader = null
        val mp = projection
        projection = null
        try {
            mp?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        thread?.quitSafely()
        thread = null
        worker = null
        pending?.recycle()
        pending = null
        if (wasSharing) model.screenShareStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) stopSharing()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "argus_screen"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.argus.assistant.STOP_SHARING"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
