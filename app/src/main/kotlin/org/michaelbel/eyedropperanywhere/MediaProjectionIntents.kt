package org.michaelbel.eyedropperanywhere

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/** Requests the complete default display instead of Android's single-app capture chooser. */
internal fun MediaProjectionManager.createDisplayCaptureIntent(): Intent =
    if (Build.VERSION.SDK_INT >= 34) {
        createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        createScreenCaptureIntent()
    }
