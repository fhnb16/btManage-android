package ru.fhnb16.btmanage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ru.fhnb16.btmanage.MainActivity
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Cell
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Role
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.WidgetConfig
import ru.fhnb16.btmanage.ui.DeviceText

/**
 * Home screen widget with the battery levels of a few devices. What it shows and how is set
 * in the application's settings ([WidgetConfig]): how many devices and which, how they are
 * laid out, how much detail, and the colours.
 */
class BatteryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Settings.load(context)
        Devices.open(context)
        provideContent {
            val devices by Devices.list.collectAsState()
            val settings by Settings.state.collectAsState()
            val config = settings.widget
            GlanceTheme {
                val look = palette(config, fluent = settings.design == "fluent")
                Content(context, pick(devices, config), config, settings.lowThreshold, look)
            }
        }
    }
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}

/** The devices to show: the chosen ones, or else the active ones that report a level. */
private fun pick(devices: List<Device>, config: WidgetConfig): List<Device> {
    val chosen = devices.filter { it.id in config.devices }
    val shown = chosen.ifEmpty {
        devices.filter { it.isActive && it.cells.isNotEmpty() }.ifEmpty { devices.filter { it.cells.isNotEmpty() } }
    }
    return shown.take(config.count.coerceIn(1, 4))
}

private class Palette(
    val background: ColorProvider,
    val text: ColorProvider,
    val secondary: ColorProvider,
    val accent: ColorProvider,
    val low: ColorProvider,
    val track: ColorProvider,
    /** Corner radius of the widget. */
    val corner: Dp,
)

/**
 * Colours and corners for the design of the application and the widget's own theme, accent
 * and background opacity.
 */
@Composable
private fun palette(config: WidgetConfig, fluent: Boolean): Palette {
    // In the system theme each colour has a day and a night value; a fixed theme uses one for both.
    fun pair(light: Color, dark: Color): ColorProvider = when (config.theme) {
        "light" -> ColorProvider(light)
        "dark" -> ColorProvider(dark)
        else -> androidx.glance.color.ColorProvider(day = light, night = dark)
    }
    val opacity = when (config.background) {
        "translucent" -> 0.6f
        "transparent" -> 0f
        else -> 1f
    }
    val accent = if (config.accent == "bluetooth") pair(Color(0xFF0067D6), Color(0xFF8CC4FF)) else GlanceTheme.colors.primary
    val track = pair(Color(0x1F000000), Color(0x29FFFFFF))

    return when {
        // Fluent: the card of the Windows application, with its small corners.
        fluent -> Palette(
            background = pair(Color(0xFFFBFBFB).copy(alpha = opacity), Color(0xFF2B2B2B).copy(alpha = opacity)),
            text = pair(Color(0xFF1B1B1B), Color(0xFFFFFFFF)),
            secondary = pair(Color(0xFF5D5D5D), Color(0xFFCDCDCD)),
            accent = accent,
            low = pair(Color(0xFFC42B1C), Color(0xFFFF99A4)),
            track = track,
            corner = 8.dp,
        )
        // Material You proper: with the system theme and a solid background every colour
        // comes from the wallpaper, as in Android's own widgets.
        config.theme == "system" && config.background == "solid" -> Palette(
            background = GlanceTheme.colors.widgetBackground,
            text = GlanceTheme.colors.onSurface,
            secondary = GlanceTheme.colors.onSurfaceVariant,
            accent = accent,
            low = pair(Color(0xFFB3261E), Color(0xFFF2B8B5)),
            track = track,
            corner = 24.dp,
        )
        // Material with a fixed theme or a see-through background: the same neutral tones,
        // which can take an opacity.
        else -> Palette(
            background = pair(Color(0xFFFFFFFF).copy(alpha = opacity), Color(0xFF1E1E22).copy(alpha = opacity)),
            text = pair(Color(0xFF1B1B1F), Color(0xFFECECF0)),
            secondary = pair(Color(0xFF5E5E66), Color(0xFFA9A9B2)),
            accent = accent,
            low = pair(Color(0xFFB3261E), Color(0xFFF2B8B5)),
            track = track,
            corner = 24.dp,
        )
    }
}

@Composable
private fun Content(context: Context, devices: List<Device>, config: WidgetConfig, lowThreshold: Int, palette: Palette) {
    val detailed = config.style == "detailed"
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(palette.background)
            .cornerRadius(palette.corner)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (devices.isEmpty()) {
            Text(context.getString(R.string.empty_title), style = TextStyle(color = palette.secondary, fontSize = 13.sp))
            return@Column
        }

        @Composable
        fun block(device: Device, modifier: GlanceModifier) = DeviceBlock(context, device, detailed, lowThreshold, palette, modifier)

        when (config.layout) {
            "column" -> devices.forEachIndexed { index, device ->
                if (index > 0) Spacer(GlanceModifier.height(8.dp))
                block(device, GlanceModifier.fillMaxWidth())
            }
            "grid" -> devices.chunked(2).forEachIndexed { index, pair ->
                if (index > 0) Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    pair.forEachIndexed { column, device ->
                        if (column > 0) Spacer(GlanceModifier.width(12.dp))
                        block(device, GlanceModifier.defaultWeight())
                    }
                }
            }
            else -> Row(GlanceModifier.fillMaxWidth()) {
                devices.forEachIndexed { index, device ->
                    if (index > 0) Spacer(GlanceModifier.width(12.dp))
                    block(device, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/** One device: its name, then either a level per battery or the one that runs out first. */
@Composable
private fun DeviceBlock(
    context: Context,
    device: Device,
    detailed: Boolean,
    lowThreshold: Int,
    palette: Palette,
    modifier: GlanceModifier,
) {
    Column(modifier) {
        Text(
            DeviceText.name(context, device),
            style = TextStyle(color = palette.secondary, fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
        if (detailed && device.hasEarbudCells) {
            Row(GlanceModifier.fillMaxWidth()) {
                listOf(Role.LEFT, Role.RIGHT, Role.CASE).forEachIndexed { index, role ->
                    if (index > 0) Spacer(GlanceModifier.width(8.dp))
                    Level(context, device.cell(role), DeviceText.roleLabel(context, role), 15, lowThreshold, palette, GlanceModifier.defaultWeight())
                }
            }
        } else {
            Level(context, device.lowestCell, null, 20, lowThreshold, palette, GlanceModifier.fillMaxWidth())
        }
    }
}

/** A level with its meter, and a label under it when there are several side by side. */
@Composable
private fun Level(
    context: Context,
    cell: Cell?,
    label: String?,
    size: Int,
    lowThreshold: Int,
    palette: Palette,
    modifier: GlanceModifier,
) {
    val low = cell?.isLow(lowThreshold) == true
    Column(modifier) {
        Text(
            text = cell?.let { DeviceText.percent(context, it.percent) } ?: context.getString(R.string.percent_unknown),
            // A remembered level is shown in the quieter colour.
            style = TextStyle(color = if (cell?.isFresh == true) palette.text else palette.secondary, fontSize = size.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
        LinearProgressIndicator(
            progress = (cell?.percent ?: 0) / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = if (low) palette.low else palette.accent,
            backgroundColor = palette.track,
        )
        if (label != null) {
            Spacer(GlanceModifier.height(2.dp))
            Text(label, style = TextStyle(color = palette.secondary, fontSize = 10.sp), maxLines = 1)
        }
    }
}
