package com.metrocast.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val BrandNavy = Color(0xFF0F2557)
val BrandBlue = Color(0xFF2E5BBA)
val BrandSun = Color(0xFFFFC24B)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = BrandNavy,
    secondary = Color(0xFF5B6478),
    secondaryContainer = Color(0xFFE2E6F2),
    onSecondaryContainer = Color(0xFF18203A),
    tertiary = Color(0xFFA06A00),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFF7F8FC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = BrandNavy,
    primaryContainer = Color(0xFF1F3F8F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFC2C7D9),
    secondaryContainer = Color(0xFF2C3346),
    onSecondaryContainer = Color(0xFFDDE2F3),
    tertiary = BrandSun,
    background = Color(0xFF10131B),
    surface = Color(0xFF10131B),
)

/** Theme choices, read from Settings once and updated live when they change. */
object AppLook {
    var mode by androidx.compose.runtime.mutableStateOf("system")
    var highContrast by androidx.compose.runtime.mutableStateOf(false)
    var largeText by androidx.compose.runtime.mutableStateOf(false)
    private var loaded = false

    fun load(prefs: com.metrocast.app.data.Prefs) {
        mode = prefs.themeMode; highContrast = prefs.highContrast; largeText = prefs.largeText; loaded = true
        Format.use24h = prefs.clock24
    }
    fun ensure(context: android.content.Context) { if (!loaded) load(com.metrocast.app.data.Prefs(context)) }
}

// High contrast: pure black/white paper, full-strength secondary text and outlines.
private val LightHigh = LightColors.copy(
    background = Color.White, surface = Color.White, onSurface = Color.Black, onBackground = Color.Black,
    onSurfaceVariant = Color(0xFF1A1A1A), outline = Color.Black, outlineVariant = Color(0xFF444444),
    surfaceContainerLow = Color(0xFFF2F2F2), surfaceContainer = Color(0xFFEDEDED), surfaceContainerHigh = Color(0xFFE6E6E6),
    surfaceContainerHighest = Color(0xFFDDDDDD), primary = Color(0xFF0B3FA8), secondaryContainer = Color(0xFFD6DCEC),
)
private val DarkHigh = DarkColors.copy(
    background = Color.Black, surface = Color.Black, onSurface = Color.White, onBackground = Color.White,
    onSurfaceVariant = Color(0xFFEDEDED), outline = Color.White, outlineVariant = Color(0xFFBBBBBB),
    surfaceContainerLow = Color(0xFF111111), surfaceContainer = Color(0xFF161616), surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF262626), primary = Color(0xFFC9D6FF),
)

@Composable
fun MetroCastTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    AppLook.ensure(context)
    val dark = when (AppLook.mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val scheme = when {
        AppLook.highContrast -> if (dark) DarkHigh else LightHigh
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = if (AppLook.largeText) androidx.compose.ui.unit.Density(density.density, density.fontScale * 1.2f) else density
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
