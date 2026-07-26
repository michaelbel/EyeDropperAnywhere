package org.michaelbel.eyedropperanywhere

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michaelbel.eyedropperanywhere.ui.touchscreen.EyeDropperOverlayView

class EyeDropperService : LifecycleService(), SavedStateRegistryOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var overlayView: EyeDropperOverlayView? = null
    private var screenshot: Bitmap? = null
    private val screenshotReady = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!screenshotReady.get() && !stopped.get()) {
                failCapture()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged running=${running.value}")
        if (running.value) finish()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        cleanup()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (running.value) return
        stopped.set(false)
        screenshotReady.set(false)
        _running.value = true
        EyeDropperTileService.requestRefresh(this)

        val notification = buildNotification(R.string.notification_preparing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_required, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            failCapture()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(resultCode, projectionData)
        if (newProjection == null) {
            failCapture()
            return
        }
        projection = newProjection.also { it.registerCallback(projectionCallback, mainHandler) }

        // Let the consent activity disappear so it is not present in the captured frame.
        mainHandler.postDelayed(::createVirtualDisplay, CAPTURE_DELAY_MS)
    }

    private fun createVirtualDisplay() {
        Log.d(TAG, "createVirtualDisplay")
        if (stopped.get()) return
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi

        val thread = HandlerThread("EyeDropperCapture").also { it.start() }
        captureThread = thread
        val captureHandler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ source -> acquireScreenshot(source) }, captureHandler)

        try {
            virtualDisplay = projection?.createVirtualDisplay(
                "EyeDropperAnywhere",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            ) ?: throw IllegalStateException("MediaProjection was stopped")
        } catch (_: Exception) {
            failCapture()
        }
    }

    private fun acquireScreenshot(reader: ImageReader) {
        Log.d(TAG, "acquireScreenshot")
        if (!screenshotReady.compareAndSet(false, true)) return
        val image = reader.acquireLatestImage()
        if (image == null) {
            screenshotReady.set(false)
            return
        }

        val captured = try {
            val plane = image.planes.first()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == image.width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
            releaseProjection()
        }

        if (captured == null) {
            failCapture()
            return
        }

        mainHandler.post {
            if (stopped.get()) {
                captured.recycle()
            } else {
                showOverlay(captured)
            }
        }
    }

    private fun showOverlay(bitmap: Bitmap) {
        Log.d(TAG, "showOverlay")
        screenshot = bitmap
        val view = EyeDropperOverlayView(
            context = this,
            screenshot = bitmap,
            onColorPicked = ::completeSelection,
            onCancel = ::finish,
        )
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        var overlayFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val params = WindowManager.LayoutParams(
            view.overlayWidth,
            view.overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = view.initialWindowX
            y = view.initialWindowY
            title = "EyeDropperAnywhereOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) configureLegacyFullScreen(view)

        try {
            windowManager.addView(view, params)
            Log.d(TAG, "overlay attached")
            overlayView = view
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(R.string.notification_selecting),
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to attach EyeDropper overlay", exception)
            bitmap.recycle()
            screenshot = null
            failCapture()
        }
    }

    private fun completeSelection(color: Int) {
        val hex = ColorRepository.format(color)
        ColorRepository.save(this, color)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Color", hex))
        Toast.makeText(this, hex, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun failCapture() {
        mainHandler.post {
            if (!stopped.get()) {
                Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun finish() {
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup")
        if (!stopped.compareAndSet(false, true)) return
        overlayView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        overlayView = null
        releaseProjection()
        screenshot?.takeUnless(Bitmap::isRecycled)?.recycle()
        screenshot = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        _running.value = false
        EyeDropperTileService.requestRefresh(this)
    }

    private fun releaseProjection() {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.let { currentProjection ->
            runCatching { currentProjection.unregisterCallback(projectionCallback) }
            runCatching { currentProjection.stop() }
        }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun buildNotification(textRes: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, EyeDropperService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_eyedropper)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_eyedropper),
                    getString(R.string.notification_stop),
                    stop,
                ).build()
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun configureLegacyFullScreen(view: View) {
        view.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    companion object {
        private const val CHANNEL_ID = "eye_dropper"
        private const val NOTIFICATION_ID = 17
        private const val CAPTURE_DELAY_MS = 650L
        private const val TAG = "EyeDropperService"
        private const val ACTION_START = "org.michaelbel.eyedropperanywhere.START"
        private const val ACTION_STOP = "org.michaelbel.eyedropperanywhere.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        fun start(context: Context, resultCode: Int, projectionData: Intent) {
            val intent = Intent(context, EyeDropperService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, EyeDropperService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
