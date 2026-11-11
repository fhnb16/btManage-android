package io.github.fhnb16.btmanage

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import btmanage.core.Native
import io.github.fhnb16.btmanage.core.Cell
import io.github.fhnb16.btmanage.core.Demo
import io.github.fhnb16.btmanage.core.Device
import io.github.fhnb16.btmanage.core.Tracker
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BtManageTheme {
                DevicesScreen()
            }
        }
    }
}

/** Material 3 with the wallpaper colours where the system offers them (Android 12 and later). */
@Composable
private fun BtManageTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen() {
    var devices by remember { mutableStateOf(emptyList<Device>()) }

    // Until scanning is implemented the screen runs on demo data, one step a second.
    if (Native.available) {
        LaunchedEffect(Unit) {
            Tracker().use { tracker ->
                var tick = 0L
                while (true) {
                    Demo.feed(tracker, tick++)
                    devices = tracker.snapshot()
                    delay(1_000)
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val nearest = devices.firstOrNull()
            when {
                !Native.available -> Text(stringResource(R.string.core_missing))
                nearest == null -> Text(stringResource(R.string.empty_title))
                else -> {
                    DeviceCard(nearest)
                    devices.drop(1).forEach { OtherDeviceRow(it) }
                }
            }

            if (Native.available) {
                Text(
                    text = stringResource(R.string.demo_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.core_version, Native.version()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: Device) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = device.model ?: stringResource(R.string.unknown_model),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (device.isHeadphones) {
                    BatteryCell(stringResource(R.string.cell_main), device.main, Modifier.weight(1f))
                    // Keep the single cell the width of one column.
                    Box(Modifier.weight(2f))
                } else {
                    BatteryCell(stringResource(R.string.cell_left), device.left, Modifier.weight(1f))
                    BatteryCell(stringResource(R.string.cell_right), device.right, Modifier.weight(1f))
                    BatteryCell(stringResource(R.string.cell_case), device.case, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BatteryCell(label: String, cell: Cell?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = percentText(cell),
            style = MaterialTheme.typography.headlineMedium.tabular(),
            color = if (cell == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
        SegmentedMeter(percent = cell?.percent, low = cell?.isLow == true)
        Text(
            text = listOfNotNull(
                label,
                stringResource(R.string.in_ear).takeIf { cell?.inEar == true },
                stringResource(R.string.charging).takeIf { cell?.charging == true },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ten segments: earbuds report their charge in steps of 10 %. */
@Composable
private fun SegmentedMeter(percent: Int?, low: Boolean) {
    val filled = ((percent ?: 0) + 5) / 10
    val on = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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

@Composable
private fun OtherDeviceRow(device: Device) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(device.model ?: stringResource(R.string.unknown_model))
            Text(
                text = percentText(device.main ?: device.left ?: device.right),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun percentText(cell: Cell?): String =
    if (cell == null) stringResource(R.string.percent_unknown) else stringResource(R.string.percent_format, cell.percent)

/** Fixed-width digits, so a changing level does not shift the text around it. */
private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
