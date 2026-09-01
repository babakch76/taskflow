package com.taskflow.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ─── Brand Colors ──────────────────────────────────────────────
private val Violet400 = Color(0xFFA78BFA)
private val Violet600 = Color(0xFF7C3AED)
private val Blue400   = Color(0xFF60A5FA)
private val Green400  = Color(0xFF34D399)
private val Pink400   = Color(0xFFF472B6)

private val DarkColorScheme = darkColorScheme(
    primary       = Violet400,
    onPrimary     = Color.White,
    secondary     = Blue400,
    onSecondary   = Color.White,
    tertiary      = Green400,
    background    = Color(0xFF0F1117),
    surface       = Color(0xFF181A24),
    surfaceVariant = Color(0xFF1E2130),
    onBackground  = Color(0xFFEEF0F6),
    onSurface     = Color(0xFFEEF0F6),
    error         = Color(0xFFF87171),
    onError       = Color.White,
    outline       = Color(0xFF2A2E3F),
)

private val LightColorScheme = lightColorScheme(
    primary       = Violet600,
    onPrimary     = Color.White,
    secondary     = Blue400,
    onSecondary   = Color.White,
    tertiary      = Green400,
    background    = Color(0xFFF8F9FC),
    surface       = Color.White,
    surfaceVariant = Color(0xFFF1F3F9),
    onBackground  = Color(0xFF1A1B2E),
    onSurface     = Color(0xFF1A1B2E),
    error         = Color(0xFFDC2626),
    onError       = Color.White,
    outline       = Color(0xFFD4D6E0),
)

@Composable
fun TaskFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: Material You recolours the whole app from the wallpaper on
    // Android 12+, so the violet scheme defined above is never what renders. The
    // brand palette is part of the design, and the amber overdue colour is
    // chosen to sit against it — a wallpaper-derived scheme can put a red
    // primary next to it, which reads as exactly the shame signal constraint 3
    // rules out.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Use Material You dynamic colors on Android 12+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
