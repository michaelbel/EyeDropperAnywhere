package org.michaelbel.eyedropperanywhere

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri

class CapturePermissionActivity: ComponentActivity() {

    private var projectionRequestOpen = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        projectionRequestOpen = false
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            EyeDropperService.start(this, result.resultCode, data)
        }
        finish()
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            launchProjectionRequest()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri(),))
            return
        }
        if (savedInstanceState == null) launchProjectionRequest()
    }

    private fun launchProjectionRequest() {
        if (projectionRequestOpen) return
        projectionRequestOpen = true
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createDisplayCaptureIntent())
    }
}
