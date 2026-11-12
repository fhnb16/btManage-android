package ru.fhnb16.btmanage.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fhnb16.btmanage.data.SettingsData

/**
 * The two looks of the application.
 *
 * Material You is Android's own: colours derived from the wallpaper, rounded shapes, tonal
 * surfaces. Fluent follows the Windows application: neutral grey surfaces, cards with a
 * hairline border, small corner radii, the Windows type ramp.
 */
enum class DesignStyle { MATERIAL, FLUENT }

val LocalDesign = staticCompositionLocalOf { DesignStyle.MATERIAL }

fun designOf(settings: SettingsData): DesignStyle =
    if (settings.design == "fluent") DesignStyle.FLUENT else DesignStyle.MATERIAL

/** The application in the design, theme and accent the user chose. */
@Composable
fun AppTheme(settings: SettingsData, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val design = designOf(settings)
    val colors = when (design) {
        DesignStyle.MATERIAL -> materialColors(settings.accent, dark)
        DesignStyle.FLUENT -> fluentColors(accentColor(settings.accent, dark), dark)
    }
    CompositionLocalProvider(LocalDesign provides design) {
        MaterialTheme(
            colorScheme = colors,
            shapes = if (design == DesignStyle.FLUENT) FluentShapes else Shapes(),
            typography = if (design == DesignStyle.FLUENT) FluentTypography else Typography(),
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

private val FluentControlShape: CornerBasedShape = RoundedCornerShape(4.dp)

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

// --- Pieces that differ between the two looks ---------------------------------------------

/**
 * A group of content: a tonal card in Material, a flat card with a hairline border in
 * Fluent.
 */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (LocalDesign.current == DesignStyle.FLUENT) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(content = content)
        }
    } else {
        Card(modifier = modifier, content = content)
    }
}

/** Shape of buttons: the pill of Material, or the 4 dp corners of Windows. */
@Composable
fun buttonShape(): Shape =
    if (LocalDesign.current == DesignStyle.FLUENT) FluentControlShape else ButtonDefaults.shape

/** Shape of one option in a segmented row. */
@Composable
fun segmentShape(index: Int, count: Int): Shape =
    if (LocalDesign.current == DesignStyle.FLUENT) {
        SegmentedButtonDefaults.itemShape(index = index, count = count, baseShape = FluentControlShape)
    } else {
        SegmentedButtonDefaults.itemShape(index = index, count = count)
    }
