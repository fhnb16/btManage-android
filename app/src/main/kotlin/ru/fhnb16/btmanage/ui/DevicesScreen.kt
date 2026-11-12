package ru.fhnb16.btmanage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.UpdateInfo

/** Every device the application knows: connected ones first, then those nearby, then the rest. */
@Composable
fun DevicesScreen(
    devices: List<Device>,
    settings: SettingsData,
    update: UpdateInfo?,
    modifier: Modifier = Modifier,
    open: (String) -> Unit,
) {
    val context = LocalContext.current
    val groups = listOf(
        R.string.group_connected to devices.filter { it.connected },
        R.string.group_nearby to devices.filter { !it.connected && it.nearby },
        R.string.group_other to devices.filter { !it.connected && !it.nearby },
    ).filter { it.second.isNotEmpty() }

    LazyColumn(modifier) {
        if (update != null) {
            item {
                UpdateBanner(update, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (devices.isEmpty()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        for ((title, group) in groups) {
            item(key = title) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            items(group, key = { it.id }) { device ->
                val cell = device.lowestCell
                ListItem(
                    modifier = Modifier.clickable { open(device.id) },
                    leadingContent = { StatusDot(device) },
                    headlineContent = { Text(DeviceText.name(context, device), maxLines = 1) },
                    supportingContent = {
                        Text("${DeviceText.kind(context, device.kind)} · ${DeviceText.status(context, device)}")
                    },
                    trailingContent = {
                        if (cell != null) {
                            Text(
                                text = DeviceText.percent(context, cell.percent),
                                style = MaterialTheme.typography.bodyLarge.tabular(),
                                color = when {
                                    !cell.isFresh -> MaterialTheme.colorScheme.outline
                                    cell.isLow(settings.lowThreshold) -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

/** A newer release: where to read about it and where to get it. */
@Composable
fun UpdateBanner(update: UpdateInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Panel(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.update_available, update.version), Modifier.weight(1f))
            TextButton(onClick = {
                val target = update.apkUrl ?: update.pageUrl
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
            }) {
                Text(stringResource(R.string.update_download))
            }
        }
    }
}

/** One device: its batteries, their history, what is known about it. */
@Composable
fun DeviceDetail(device: Device, settings: SettingsData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Re-read when the levels change, not on every recomposition.
    val history = remember(device.id, device.cells.map { it.percent to it.charging }) {
        Devices.history(device.id, HISTORY_WINDOW_MS + 60 * 60 * 1000L)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "${DeviceText.kind(context, device.kind)} · ${DeviceText.status(context, device)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.section_battery), style = MaterialTheme.typography.titleSmall)
                if (device.cells.isEmpty()) {
                    Text(
                        stringResource(R.string.no_battery),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    BatteryCells(device, settings.lowThreshold, Modifier.fillMaxWidth())
                    device.estimateMin?.let {
                        Text(
                            DeviceText.estimate(context, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.section_history), style = MaterialTheme.typography.titleSmall)
                    HistoryChart(history, device.isActive, Modifier.fillMaxWidth())
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.section_info), style = MaterialTheme.typography.titleSmall)
                // Unknown facts are left out rather than shown empty.
                val facts = listOfNotNull(
                    R.string.info_type to DeviceText.kind(context, device.kind),
                    device.model?.let { R.string.info_model to it },
                    device.address?.let { R.string.info_address to it },
                    device.transport?.let {
                        R.string.info_transport to stringResource(
                            when (it) {
                                "le" -> R.string.transport_le
                                "dual" -> R.string.transport_dual
                                else -> R.string.transport_classic
                            },
                        )
                    },
                    device.profiles.takeIf { it.isNotEmpty() }?.let { R.string.info_profiles to it.joinToString(", ") },
                    device.rssi?.let { R.string.info_signal to stringResource(R.string.signal_format, it) },
                )
                for ((label, value) in facts) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(label),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(value, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.device_notifications), Modifier.weight(1f))
            Switch(
                checked = device.id !in settings.muted,
                onCheckedChange = { on ->
                    Settings.update(context) {
                        it.copy(muted = if (on) it.muted - device.id else it.muted + device.id)
                    }
                },
            )
        }

        TextButton(onClick = { Devices.forget(device.id) }) {
            Text(stringResource(R.string.forget_history))
        }
    }
}
