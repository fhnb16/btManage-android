package io.github.fhnb16.btmanage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import btmanage.core.Native
import io.github.fhnb16.btmanage.R
import io.github.fhnb16.btmanage.core.Cell
import io.github.fhnb16.btmanage.core.Demo
import io.github.fhnb16.btmanage.core.Device
import io.github.fhnb16.btmanage.core.Tracker

/**
 * Home screen widget with the levels of the nearest device. A placeholder for now: it shows
 * the demo data once, since scanning is not implemented yet.
 */
class BatteryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val device = if (Native.available) {
            Tracker().use { tracker ->
                Demo.feed(tracker, tick = 0)
                tracker.snapshot().firstOrNull()
            }
        } else {
            null
        }

        val name = device?.model ?: context.getString(if (device == null) R.string.empty_title else R.string.unknown_model)
        val cells = device?.let { cells(context, it) }.orEmpty()

        provideContent {
            GlanceTheme {
                Content(name, cells)
            }
        }
    }

    private fun cells(context: Context, device: Device): List<Pair<String, String>> {
        fun entry(label: Int, cell: Cell?) = context.getString(label) to
            (cell?.let { context.getString(R.string.percent_format, it.percent) } ?: context.getString(R.string.percent_unknown))

        return if (device.isHeadphones) {
            listOf(entry(R.string.cell_main, device.main))
        } else {
            listOf(
                entry(R.string.cell_left, device.left),
                entry(R.string.cell_right, device.right),
                entry(R.string.cell_case, device.case),
            )
        }
    }
}

@Composable
private fun Content(name: String, cells: List<Pair<String, String>>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp),
    ) {
        Text(
            text = name,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(8.dp))
        Row {
            cells.forEachIndexed { index, (label, percent) ->
                if (index > 0) {
                    Spacer(GlanceModifier.width(16.dp))
                }
                Column {
                    Text(
                        text = percent,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 20.sp),
                    )
                    Text(
                        text = label,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    )
                }
            }
        }
    }
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}
