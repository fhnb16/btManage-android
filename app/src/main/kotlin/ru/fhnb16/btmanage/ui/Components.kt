package ru.fhnb16.btmanage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Cell
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Role
import ru.fhnb16.btmanage.data.RoleHistory
import java.text.DateFormat
import java.util.Date

/** Fixed-width digits, so a changing level does not shift the text around it. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** The batteries of a device: three columns for earbuds, one for everything else. */
@Composable
fun BatteryCells(device: Device, lowThreshold: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (device.hasEarbudCells) {
            for (role in listOf(Role.LEFT, Role.RIGHT, Role.CASE)) {
                BatteryCell(role, device.cell(role), lowThreshold, Modifier.weight(1f))
            }
        } else {
            BatteryCell(Role.MAIN, device.cell(Role.MAIN), lowThreshold, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatteryCell(role: Role, cell: Cell?, lowThreshold: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = cell?.isFresh == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            // No level shows as a dash; a remembered one is dimmed and carries its age.
            text = cell?.let { DeviceText.percent(context, it.percent) } ?: stringResource(R.string.percent_unknown),
            style = MaterialTheme.typography.headlineMedium.tabular(),
            color = when {
                cell == null -> MaterialTheme.colorScheme.outline
                current -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        SegmentedMeter(cell?.percent, low = cell?.isLow(lowThreshold) == true, dimmed = !current)
        val note = when {
            cell == null -> null
            !cell.isFresh -> DeviceText.age(context, cell.ageMs)
            cell.charging -> stringResource(R.string.charging)
            cell.inEar == true -> stringResource(R.string.in_ear)
            else -> null
        }
        Text(
            text = listOfNotNull(DeviceText.roleLabel(context, role), note).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ten segments: earbuds report their charge in steps of 10 %. */
@Composable
fun SegmentedMeter(percent: Int?, low: Boolean, dimmed: Boolean = false) {
    val filled = ((percent ?: 0) + 5) / 10
    val on = (if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        .copy(alpha = if (dimmed) 0.4f else 1f)
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(10) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(if (index < filled) on else off),
            )
        }
    }
}

/** The mark of a device in a list: filled while connected, hollow while only nearby. */
@Composable
fun StatusDot(device: Device) {
    val color = when {
        device.connected -> MaterialTheme.colorScheme.primary
        device.nearby -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/**
 * Battery level over the last 24 hours, drawn as steps: a level holds until the device
 * reports another. The line breaks where the device was away. One battery is drawn in the
 * accent colour, the others in neutral tones.
 */
@Composable
fun HistoryChart(series: List<RoleHistory>, active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val start = now - HISTORY_WINDOW_MS
    val lead = series.minByOrNull { it.role }?.role
    val colors = MaterialTheme.colorScheme
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val label = MaterialTheme.typography.labelSmall

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
                for (value in listOf("100", "50", "0")) {
                    Text(value, style = label, color = colors.outline)
                }
            }
            Canvas(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                fun x(t: Long) = ((t - start).toFloat() / HISTORY_WINDOW_MS).coerceIn(0f, 1f) * size.width
                fun y(percent: Int) = (100 - percent) / 100f * size.height

                for (percent in listOf(0, 50, 100)) {
                    drawLine(colors.outlineVariant, Offset(0f, y(percent)), Offset(size.width, y(percent)), 1.dp.toPx())
                }
                // The leading line goes on top: draw it last.
                for (entry in series.sortedByDescending { it.role }) {
                    val color = when (entry.role) {
                        lead -> colors.primary
                        Role.CASE -> colors.outline
                        else -> colors.onSurfaceVariant
                    }
                    val path = Path()
                    val points = entry.points
                    var open = false
                    for ((index, point) in points.withIndex()) {
                        if (open) path.lineTo(x(point.t), y(point.percent)) else path.moveTo(x(point.t), y(point.percent))
                        val next = points.getOrNull(index + 1)
                        open = next != null && next.t - point.t <= HISTORY_GAP_MS
                        // The level holds until the next report, to now, or for a short tail.
                        val until = when {
                            open -> next!!.t
                            next == null && active && now - point.t <= HISTORY_GAP_MS -> now
                            else -> minOf(point.t + HISTORY_TAIL_MS, now)
                        }
                        path.lineTo(x(until), y(point.percent))
                    }
                    drawPath(path, color, style = Stroke(width = (if (entry.role == lead) 2f else 1.5f).dp.toPx(), join = StrokeJoin.Round))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time.format(Date(start)), style = label, color = colors.outline)
            Text(time.format(Date(start + HISTORY_WINDOW_MS / 2)), style = label, color = colors.outline)
            Text(stringResource(R.string.chart_now), style = label, color = colors.outline)
        }
        if (series.size > 1) {
            Text(
                text = series.joinToString("  ·  ") { DeviceText.roleLabel(context, it.role) },
                style = label,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, top = 4.dp),
            )
        }
    }
}

const val HISTORY_WINDOW_MS = 24 * 60 * 60 * 1000L

/** A longer silence than this breaks the line. */
private const val HISTORY_GAP_MS = 40 * 60 * 1000L

/** How far a level is drawn past its last report when nothing follows. */
private const val HISTORY_TAIL_MS = 15 * 60 * 1000L
