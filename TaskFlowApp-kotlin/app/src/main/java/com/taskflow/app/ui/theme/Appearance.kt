package com.taskflow.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Light, dark, or whatever the phone is doing. */
enum class AppearanceMode {
    SYSTEM, LIGHT, DARK;

    val label: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
        }
}

/**
 * Which appearance the app renders in, and where that choice is kept.
 *
 * On the device and nowhere else. It is a property of *this phone in this
 * person's hand*, not of the household or even of the account: someone who
 * reads in the dark on their own phone has said nothing about how the same
 * account should look on a tablet, and syncing it would decide that for them.
 * That also keeps it out of a schema that other people can read.
 *
 * A plain SharedPreferences file rather than DataStore. One enum, written when
 * a person taps a radio button and read once at startup, does not need
 * asynchronous storage or a new dependency to hold it; the swipe hint next door
 * is kept the same way. Worth revisiting only if settings multiply.
 *
 * The flow is process-wide because the theme is applied at the very top of the
 * tree, above the nav graph, while the control that changes it sits several
 * screens down.
 */
object Appearance {
    private const val FILE = "taskflow_appearance"
    private const val KEY = "mode"

    private val _mode = MutableStateFlow(AppearanceMode.SYSTEM)
    val mode: StateFlow<AppearanceMode> = _mode.asStateFlow()

    /** Reads the stored choice. Call once, before the first frame. */
    fun load(context: Context) {
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        _mode.value = AppearanceMode.entries.firstOrNull { it.name == stored } ?: AppearanceMode.SYSTEM
    }

    fun set(context: Context, mode: AppearanceMode) {
        _mode.value = mode
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, mode.name)
            .apply()
    }
}
