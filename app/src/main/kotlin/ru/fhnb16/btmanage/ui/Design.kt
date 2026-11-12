package ru.fhnb16.btmanage.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fhnb16.btmanage.data.SettingsData

/**
 * The looks of the application.
 *
 * - Material You is Android's own: colours derived from the wallpaper, rounded shapes, tonal
 *   surfaces.
 * - Fluent follows the Windows application: neutral grey surfaces, cards with a hairline
 *   border, small corner radii, the Windows type ramp.
 * - Holo recalls Android 4.3: black or pale grey, the Holo blue, thin figures, headers in
 *   capitals.
 * - Eclair recalls Android 2.1: black or white, the orange highlight, a grey title bar.
 *
 * This file holds what a design is made of: colours, shapes, type. The controls each design
 * draws for itself (switches, tabs, buttons, lists) are in `Controls.kt`; only Material You
 * uses the Material 3 components as they come.
 */
enum class DesignStyle { MATERIAL, FLUENT, HOLO, ECLAIR }

val LocalDesign = staticCompositionLocalOf { DesignStyle.MATERIAL }

fun designOf(settings: SettingsData): DesignStyle = when (settings.design) {
    "fluent" -> DesignStyle.FLUENT
    "holo" -> DesignStyle.HOLO
    "eclair" -> DesignStyle.ECLAIR
    else -> DesignStyle.MATERIAL
}

/** The application in the design, theme and accent the user chose. */
@Composable
fun AppTheme(settings: SettingsData, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val design = designOf(settings)
    val bluetooth = settings.accent == "bluetooth"
    val colors = when (design) {
        DesignStyle.MATERIAL -> materialColors(settings.accent, dark)
        DesignStyle.FLUENT -> fluentColors(accentColor(settings.accent, dark), dark)
        DesignStyle.HOLO -> holoColors(bluetooth, dark)
        DesignStyle.ECLAIR -> eclairColors(bluetooth, dark)
    }
    // The icons of the status bar follow the theme of the application, not of the system.
    // Android 2.1 kept its status bar black in either theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val bars = WindowCompat.getInsetsController(window, view)
                bars.isAppearanceLightStatusBars = !dark && design != DesignStyle.ECLAIR
                bars.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalDesign provides design) {
        MaterialTheme(
            colorScheme = colors,
            shapes = when (design) {
                DesignStyle.MATERIAL -> Shapes()
                DesignStyle.FLUENT -> FluentShapes
                DesignStyle.HOLO -> HoloShapes
                DesignStyle.ECLAIR -> EclairShapes
            },
            typography = when (design) {
                DesignStyle.MATERIAL -> Typography()
                DesignStyle.FLUENT -> FluentTypography
                DesignStyle.HOLO -> HoloTypography
                DesignStyle.ECLAIR -> EclairTypography
            },
            content = content,
        )
    }
}

// --- Material You ---------------------------------------------------------------------

/**
 * Material 3 colours: derived from the wallpaper where Android offers that (12 and later),
 * else the scheme built on Bluetooth blue. Choosing the Bluetooth accent picks that scheme
 * on every version.
 */
@Composable
private fun materialColors(accent: String, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        accent != "bluetooth" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BlueDark
        else -> BlueLight
    }
}

// A complete Material 3 scheme on a blue seed: every role, so that no surface falls back to
// the library's default purple.
private val BlueLight = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val BlueDark = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

// --- Fluent ---------------------------------------------------------------------------

/**
 * The accent for the Fluent look: the one Android derives from the wallpaper, as Windows
 * takes its own from the system, or Bluetooth blue. Before Android 12 there is no system
 * accent, and the default blue of Windows stands in.
 */
@Composable
private fun accentColor(accent: String, dark: Boolean): Color {
    val context = LocalContext.current
    return when {
        accent == "bluetooth" -> if (dark) Color(0xFF8CC4FF) else Color(0xFF0067D6)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        dark -> Color(0xFF60CDFF)
        else -> Color(0xFF005FB8)
    }
}

/**
 * The neutral palette of Windows 11, as solid colours: the grey base that stands in for
 * Mica, cards a shade off it with a hairline border, three strengths of text.
 */
private fun fluentColors(accent: Color, dark: Boolean): ColorScheme {
    return if (dark) {
        val base = Color(0xFF202020)
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF000000),
            primaryContainer = lerp(base, accent, 0.30f),
            onPrimaryContainer = Color(0xFFFFFFFF),
            secondary = accent,
            onSecondary = Color(0xFF000000),
            // The chosen option of a segmented row: a wash of the accent.
            secondaryContainer = lerp(base, accent, 0.24f),
            onSecondaryContainer = Color(0xFFFFFFFF),
            tertiary = accent,
            onTertiary = Color(0xFF000000),
            background = base,
            onBackground = Color(0xFFFFFFFF),
            surface = base,
            onSurface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFF2D2D2D),
            onSurfaceVariant = Color(0xFFCDCDCD),
            outline = Color(0xFF9A9A9A),
            // Card borders and dividers.
            outlineVariant = Color(0xFF3A3A3A),
            error = Color(0xFFFF99A4),
            onError = Color(0xFF000000),
            surfaceContainerLowest = Color(0xFF1C1C1C),
            // Cards.
            surfaceContainerLow = Color(0xFF2B2B2B),
            surfaceContainer = Color(0xFF272727),
            surfaceContainerHigh = Color(0xFF2D2D2D),
            surfaceContainerHighest = Color(0xFF343434),
        )
    } else {
        val base = Color(0xFFF3F3F3)
        lightColorScheme(
            primary = accent,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = lerp(base, accent, 0.20f),
            onPrimaryContainer = Color(0xFF1B1B1B),
            secondary = accent,
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = lerp(base, accent, 0.16f),
            onSecondaryContainer = Color(0xFF1B1B1B),
            tertiary = accent,
            onTertiary = Color(0xFFFFFFFF),
            background = base,
            onBackground = Color(0xFF1B1B1B),
            surface = base,
            onSurface = Color(0xFF1B1B1B),
            surfaceVariant = Color(0xFFEAEAEA),
            onSurfaceVariant = Color(0xFF5D5D5D),
            outline = Color(0xFF868686),
            outlineVariant = Color(0xFFE5E5E5),
            error = Color(0xFFC42B1C),
            onError = Color(0xFFFFFFFF),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFBFBFB),
            surfaceContainer = Color(0xFFF9F9F9),
            surfaceContainerHigh = Color(0xFFEFEFEF),
            surfaceContainerHighest = Color(0xFFE9E9E9),
        )
    }
}

/** Windows rounds controls by 4 and cards and overlays by 8. */
private val FluentShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/**
 * The Windows type ramp on the system typeface: caption 12/16, body 14/20, body strong
 * 14/20 semibold, subtitle 20/28 semibold, and 28/36 for the large figures.
 */
private val FluentTypography: Typography = Typography().let { base ->
    fun TextStyle.ramp(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)
    base.copy(
        headlineMedium = base.headlineMedium.ramp(28, 36),
        titleLarge = base.titleLarge.ramp(20, 28, FontWeight.SemiBold),
        titleMedium = base.titleMedium.ramp(14, 20, FontWeight.SemiBold),
        titleSmall = base.titleSmall.ramp(14, 20, FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.ramp(14, 20),
        bodyMedium = base.bodyMedium.ramp(14, 20),
        bodySmall = base.bodySmall.ramp(12, 16),
        labelLarge = base.labelLarge.ramp(14, 20),
        labelMedium = base.labelMedium.ramp(12, 16),
        labelSmall = base.labelSmall.ramp(12, 16),
    )
}

// --- Holo, after Android 4.3 --------------------------------------------------------------

/**
 * The Holo palette: black with white text in the dark theme, pale grey with dark text in
 * the light one, and the Holo blue. Bluetooth blue replaces it when chosen as the accent.
 */
private fun holoColors(bluetooth: Boolean, dark: Boolean): ColorScheme {
    return if (dark) {
        val base = Color(0xFF000000)
        val accent = if (bluetooth) Color(0xFF8CC4FF) else Color(0xFF33B5E5)
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF000000),
            primaryContainer = lerp(base, accent, 0.35f),
            onPrimaryContainer = Color(0xFFFFFFFF),
            secondary = accent,
            onSecondary = Color(0xFF000000),
            // Holo marks what is pressed or chosen with a wash of its blue.
            secondaryContainer = lerp(base, accent, 0.40f),
            onSecondaryContainer = Color(0xFFFFFFFF),
            tertiary = accent,
            onTertiary = Color(0xFF000000),
            background = base,
            onBackground = Color(0xFFF3F3F3),
            surface = base,
            onSurface = Color(0xFFF3F3F3),
            surfaceVariant = Color(0xFF222222),
            onSurfaceVariant = Color(0xFFBEBEBE),
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFF333333),
            error = Color(0xFFFF4444),
            onError = Color(0xFF000000),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF161616),
            surfaceContainer = Color(0xFF1C1C1C),
            // The action bar.
            surfaceContainerHigh = Color(0xFF222222),
            surfaceContainerHighest = Color(0xFF3A3A3A),
        )
    } else {
        val base = Color(0xFFF3F3F3)
        val accent = if (bluetooth) Color(0xFF0067D6) else Color(0xFF0099CC)
        lightColorScheme(
            primary = accent,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = lerp(base, accent, 0.25f),
            onPrimaryContainer = Color(0xFF333333),
            secondary = accent,
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = lerp(base, accent, 0.30f),
            onSecondaryContainer = Color(0xFF333333),
            tertiary = accent,
            onTertiary = Color(0xFFFFFFFF),
            background = base,
            onBackground = Color(0xFF333333),
            surface = base,
            onSurface = Color(0xFF333333),
            surfaceVariant = Color(0xFFE5E5E5),
            onSurfaceVariant = Color(0xFF737373),
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFFD6D6D6),
            error = Color(0xFFCC0000),
            onError = Color(0xFFFFFFFF),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFFFFFF),
            surfaceContainer = Color(0xFFEAEAEA),
            surfaceContainerHigh = Color(0xFFDDDDDD),
            surfaceContainerHighest = Color(0xFFC8C8C8),
        )
    }
}

/** Holo is all but square. */
private val HoloShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

/** Roboto as Holo set it: large figures in the light weight, headers and tabs bold. */
private val HoloTypography: Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Light, letterSpacing = 0.sp),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    )
}

// --- Eclair, after Android 2.1 ------------------------------------------------------------

/**
 * The palette of Android 2.1: black with white text, or white with black text, and the
 * orange of its highlights. Bluetooth blue replaces the orange when chosen as the accent.
 */
private fun eclairColors(bluetooth: Boolean, dark: Boolean): ColorScheme {
    return if (dark) {
        val base = Color(0xFF000000)
        val accent = if (bluetooth) Color(0xFF8CC4FF) else Color(0xFFFF9000)
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF000000),
            primaryContainer = accent,
            onPrimaryContainer = Color(0xFF000000),
            secondary = accent,
            onSecondary = Color(0xFF000000),
            // The chosen item is filled with the highlight, as a focused list row was.
            secondaryContainer = accent,
            onSecondaryContainer = Color(0xFF000000),
            tertiary = accent,
            onTertiary = Color(0xFF000000),
            background = base,
            onBackground = Color(0xFFFFFFFF),
            surface = base,
            onSurface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFF2A2A2A),
            onSurfaceVariant = Color(0xFFA8A8A8),
            outline = Color(0xFF808080),
            outlineVariant = Color(0xFF404040),
            error = Color(0xFFFF5050),
            onError = Color(0xFF000000),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF101010),
            surfaceContainer = Color(0xFF1A1A1A),
            surfaceContainerHigh = Color(0xFF2A2A2A),
            surfaceContainerHighest = Color(0xFF4A4A4A),
        )
    } else {
        val accent = if (bluetooth) Color(0xFF0067D6) else Color(0xFFE07800)
        lightColorScheme(
            primary = accent,
            onPrimary = if (bluetooth) Color(0xFFFFFFFF) else Color(0xFF000000),
            primaryContainer = accent,
            onPrimaryContainer = if (bluetooth) Color(0xFFFFFFFF) else Color(0xFF000000),
            secondary = accent,
            onSecondary = if (bluetooth) Color(0xFFFFFFFF) else Color(0xFF000000),
            secondaryContainer = accent,
            onSecondaryContainer = if (bluetooth) Color(0xFFFFFFFF) else Color(0xFF000000),
            tertiary = accent,
            onTertiary = Color(0xFF000000),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF000000),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF000000),
            surfaceVariant = Color(0xFFEBEBEB),
            onSurfaceVariant = Color(0xFF5A5A5A),
            outline = Color(0xFF808080),
            outlineVariant = Color(0xFFC8C8C8),
            error = Color(0xFFCC0000),
            onError = Color(0xFFFFFFFF),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF4F4F4),
            surfaceContainer = Color(0xFFEBEBEB),
            surfaceContainerHigh = Color(0xFFE0E0E0),
            surfaceContainerHighest = Color(0xFFC8C8C8),
        )
    }
}

/** Buttons had slightly rounded corners; everything else was square. */
private val EclairShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

/** Android 2.1 set its titles in bold and knew no letter spacing. */
private val EclairTypography: Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(letterSpacing = 0.sp),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.sp),
    )
}


// --- Pieces that differ between the looks -------------------------------------------------

/**
 * A group of content: a tonal card in Material, a card with a hairline border in Fluent.
 * The two historical designs knew no cards: there the content lies straight on the page.
 */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    when (LocalDesign.current) {
        DesignStyle.MATERIAL -> Card(modifier = modifier, content = content)
        DesignStyle.FLUENT -> Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(content = content)
        }
        else -> Column(modifier = modifier, content = content)
    }
}

/** Holo set its tabs and section headers in capitals; the other designs leave text as it is. */
@Composable
fun String.caps(): String = if (LocalDesign.current == DesignStyle.HOLO) uppercase() else this

/** Whether the design lays its content out as lists with dividers rather than as cards. */
val DesignStyle.isHistorical: Boolean get() = this == DesignStyle.HOLO || this == DesignStyle.ECLAIR
