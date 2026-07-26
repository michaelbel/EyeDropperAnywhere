package org.michaelbel.eyedropperanywhere

import android.content.Context
import java.util.Locale
import androidx.core.content.edit

internal object ColorRepository {
    private const val PREFS_NAME = "selected_color"
    private const val KEY_COLOR = "argb"

    fun save(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putInt(KEY_COLOR, color) }
    }

    fun format(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
}
