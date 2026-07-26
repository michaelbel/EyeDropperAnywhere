package org.michaelbel.eyedropperanywhere

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.michaelbel.eyedropperanywhere.ui.AppTheme
import androidx.core.net.toUri

class MainActivity: ComponentActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    private var overlayAllowed by mutableStateOf(false)
    private var continueAfterOverlay = false
    private var projectionRequestOpen = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        projectionRequestOpen = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            EyeDropperService.start(this, result.resultCode, data)
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshState()
        if (continueAfterOverlay && overlayAllowed) {
            continueAfterOverlay = false
            launchProjectionRequest()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        continueStartFlow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshState()

        setContent {
            AppTheme {
                val serviceRunning by EyeDropperService.running.collectAsState()
                MainActivityContent(
                    overlayAllowed = overlayAllowed,
                    serviceRunning = serviceRunning,
                    onStartOrStop = {
                        if (serviceRunning) EyeDropperService.stop(this) else beginStartFlow()
                    },
                    onOpenOverlaySettings = ::openOverlaySettings,
                    onAddTile = ::requestTile,
                )
            }
        }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            window.decorView.post { beginStartFlow() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        if (continueAfterOverlay && overlayAllowed && !projectionRequestOpen) {
            continueAfterOverlay = false
            launchProjectionRequest()
        }
    }

    private fun refreshState() {
        overlayAllowed = Settings.canDrawOverlays(this)
    }

    private fun beginStartFlow() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueStartFlow()
        }
    }

    private fun continueStartFlow() {
        if (!Settings.canDrawOverlays(this)) {
            continueAfterOverlay = true
            Toast.makeText(this, R.string.overlay_required, Toast.LENGTH_LONG).show()
            openOverlaySettings()
        } else {
            launchProjectionRequest()
        }
    }

    private fun openOverlaySettings() {
        overlaySettingsLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            )
        )
    }

    private fun launchProjectionRequest() {
        if (projectionRequestOpen || EyeDropperService.running.value) return
        projectionRequestOpen = true
        projectionLauncher.launch(mediaProjectionManager.createDisplayCaptureIntent())
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.tile_add_failed, Toast.LENGTH_LONG).show()
            return
        }
        val statusBarManager = getSystemService(StatusBarManager::class.java)
        statusBarManager.requestAddTileService(
            ComponentName(this, EyeDropperTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile_eyedropper),
            mainExecutor,
        ) { result ->
            val message = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                R.string.tile_added
            } else {
                R.string.tile_add_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }
}
