package org.michaelbel.eyedropperanywhere

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class EyeDropperTileService: TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (EyeDropperService.running.value) {
            EyeDropperService.stop(this)
            updateTile()
            return
        }

        val target = Intent(this, CapturePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(target)
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (EyeDropperService.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (EyeDropperService.running.value) getString(R.string.notification_selecting) else null
            updateTile()
        }
    }

    companion object {
        fun requestRefresh(context: android.content.Context) {
            requestListeningState(context, ComponentName(context, EyeDropperTileService::class.java))
        }
    }
}
