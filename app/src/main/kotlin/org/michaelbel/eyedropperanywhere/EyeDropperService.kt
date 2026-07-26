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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
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
import androidx.core.graphics.createBitmap

class EyeDropperService: LifecycleService(), SavedStateRegistryOwner {

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
    @Volatile
    private var overlayView: EyeDropperOverlayView? = null
    private var screenshot: Bitmap? = null
    @Volatile
    private var captureState = CaptureState.IDLE
    private var lastSampleCaptureTime = 0L
    private var lastSamplePixels: IntArray? = null
    private val sampleUpdatePending = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stopped.get()) return
            mainHandler.post {
                if (captureState == CaptureState.WAITING_INITIAL) failCapture() else finish()
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
        captureState = CaptureState.WAITING_INITIAL
        lastSampleCaptureTime = 0L
        lastSamplePixels = null
        sampleUpdatePending.set(false)
        _running.value = true
        EyeDropperTileService.requestRefresh(this)

        val notification = buildNotification(R.string.notification_preparing)
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_required, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val projectionData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
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
        reader.setOnImageAvailableListener(::acquireScreenshot, captureHandler)

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
        val image = reader.acquireLatestImage() ?: return
        image.use { image ->
            when (captureState) {
                CaptureState.WAITING_INITIAL -> captureInitialFrame(image)
                CaptureState.VISIBLE -> captureVisibleSample(image)
                else -> Unit
            }
        }
    }

    private fun captureInitialFrame(image: Image) {
        val captured = image.toBitmap() ?: run {
            failCapture()
            return
        }
        captureState = CaptureState.INITIAL_CAPTURED
        mainHandler.post {
            if (stopped.get()) {
                captured.recycle()
            } else {
                showOverlay(captured)
            }
        }
    }

    private fun captureVisibleSample(image: Image) {
        val now = SystemClock.uptimeMillis()
        if (
            now - lastSampleCaptureTime < SAMPLE_INTERVAL_MS ||
            sampleUpdatePending.get()
        ) {
            return
        }
        val view = overlayView ?: return
        val point = view.currentSamplePoint()
        val sample = image.readSample(point.x, point.y) ?: return
        if (lastSamplePixels?.contentEquals(sample.pixels) == true) return
        lastSampleCaptureTime = now
        lastSamplePixels = sample.pixels
        if (!sampleUpdatePending.compareAndSet(false, true)) return
        mainHandler.post {
            try {
                if (!stopped.get() && overlayView === view) {
                    view.updateScreenshotSample(
                        left = sample.left,
                        top = sample.top,
                        width = sample.width,
                        height = sample.height,
                        pixels = sample.pixels,
                    )
                }
            } finally {
                sampleUpdatePending.set(false)
            }
        }
    }

    private fun Image.toBitmap(): Bitmap? = try {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = createBitmap(paddedWidth, height)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        }
    } catch (exception: Exception) {
        Log.e(TAG, "Unable to read captured frame", exception)
        null
    }

    private fun Image.readSample(pointerX: Int, pointerY: Int): CapturedSample? {
        val plane = planes.first()
        val buffer = plane.buffer
        val sampleWidth = SAMPLE_DIAMETER.coerceAtMost(width)
        val sampleHeight = SAMPLE_DIAMETER.coerceAtMost(height)
        val left = (pointerX - SAMPLE_RADIUS).coerceIn(0, width - sampleWidth)
        val top = (pointerY - SAMPLE_RADIUS).coerceIn(0, height - sampleHeight)
        val pixels = IntArray(sampleWidth * sampleHeight)
        for (row in 0 until sampleHeight) {
            val y = top + row
            for (column in 0 until sampleWidth) {
                val x = left + column
                val offset = y * plane.rowStride + x * plane.pixelStride
                if (offset + 3 >= buffer.limit()) return null
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                val alpha = buffer.get(offset + 3).toInt() and 0xFF
                pixels[row * sampleWidth + column] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return CapturedSample(left, top, sampleWidth, sampleHeight, pixels)
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
        val overlayFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }

        try {
            windowManager.addView(view, params)
            Log.d(TAG, "overlay attached")
            overlayView = view
            captureState = CaptureState.VISIBLE
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
        captureState = CaptureState.STOPPED
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
            .setSmallIcon(R.drawable.ic_dropper_eye)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_dropper_eye),
                    getString(R.string.notification_stop),
                    stop,
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "eye_dropper"
        private const val NOTIFICATION_ID = 17
        private const val CAPTURE_DELAY_MS = 650L
        private const val SAMPLE_RADIUS = 3
        private const val SAMPLE_DIAMETER = SAMPLE_RADIUS * 2 + 1
        private const val SAMPLE_INTERVAL_MS = 50L
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

    private enum class CaptureState {
        IDLE,
        WAITING_INITIAL,
        INITIAL_CAPTURED,
        VISIBLE,
        STOPPED,
    }

    private data class CapturedSample(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CapturedSample

            if (left != other.left) return false
            if (top != other.top) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (!pixels.contentEquals(other.pixels)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = left
            result = 31 * result + top
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + pixels.contentHashCode()
            return result
        }
    }
}
