package ru.fhnb16.btmanage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.DeviceActions
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.LinkOutcome
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.UpdateState
import ru.fhnb16.btmanage.data.release

/** Every device the application knows: connected ones first, then those nearby, then the rest. */
@Composable
fun DevicesScreen(
    devices: List<Device>,
    settings: SettingsData,
    update: UpdateState,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    open: (String) -> Unit,
) {
    val design = LocalDesign.current
    val groups = listOf(
        R.string.group_connected to devices.filter { it.connected },
        R.string.group_nearby to devices.filter { !it.connected && it.nearby },
        R.string.group_other to devices.filter { !it.connected && !it.nearby },
    ).filter { it.second.isNotEmpty() }
    // Lists in the historical designs run to the edge of the list; the others keep a margin.
    val margin = if (design.isHistorical) 0.dp else 16.dp
    val inset = PaddingValues(horizontal = if (design == DesignStyle.HOLO) 8.dp else 0.dp)

    LazyColumn(modifier, contentPadding = inset) {
        if (update.release != null) {
            item {
                UpdateBanner(update, onInstall, Modifier.padding(horizontal = margin, vertical = 8.dp))
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
                SectionHeader(stringResource(title), Modifier.padding(horizontal = margin))
            }
            items(group, key = { it.id }) { device ->
                DeviceRow(device, settings) { open(device.id) }
            }
        }
    }
}

/** A device in the list: a list item in Material, a card in Fluent, a row over a divider in the historical designs. */
@Composable
private fun DeviceRow(device: Device, settings: SettingsData, onClick: () -> Unit) {
    val context = LocalContext.current
    val design = LocalDesign.current
    val cell = device.lowestCell
    val name = DeviceText.name(context, device)
    val summary = "${DeviceText.kind(context, device.kind)} · ${DeviceText.status(context, device)}"
    val level: @Composable () -> Unit = {
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
    }

    if (design == DesignStyle.MATERIAL) {
        ListItem(
            modifier = Modifier.pressable(onClick = onClick),
            leadingContent = { StatusDot(device) },
            headlineContent = { Text(name, maxLines = 1) },
            supportingContent = { Text(summary) },
            trailingContent = level,
        )
        return
    }

    val row: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(onClick = onClick)
                .then(if (design.isHistorical) Modifier.hairline() else Modifier)
                .padding(horizontal = if (design.isHistorical) 8.dp else 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(device)
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            level()
        }
    }
    if (design == DesignStyle.FLUENT) {
        Panel(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()) { row() }
    } else {
        row()
    }
}

/**
 * A newer release and how far the application has got with it: found, downloading, handed
 * to Android, or failed. Shows nothing while there is no newer release.
 */
@Composable
fun UpdateBanner(update: UpdateState, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val release = update.release ?: return
    val openPage = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
        Unit
    }

    Panel(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (update) {
                    is UpdateState.Downloading -> stringResource(R.string.update_downloading, update.percent)
                    is UpdateState.Installing -> stringResource(R.string.update_installing)
                    is UpdateState.Failed -> stringResource(R.string.update_failed)
                    else -> stringResource(R.string.update_available, release.version)
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            when {
                // A release without an APK, or one that would not install: the page is the way out.
                update is UpdateState.Failed || (update is UpdateState.Available && release.apkUrl == null) ->
                    AppButton(stringResource(R.string.update_page), primary = false, onClick = openPage)
                update is UpdateState.Available ->
                    AppButton(stringResource(R.string.update_install), primary = false, onClick = onInstall)
            }
        }
    }
}

/** One device: what can be done with it, its batteries, their history, what is known about it. */
@Composable
fun DeviceDetail(device: Device, settings: SettingsData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val codecs by DeviceActions.codecs.collectAsState()
    // Re-read when the levels change, not on every recomposition.
    val history = remember(device.id, device.cells.map { it.percent to it.charging }) {
        Devices.history(device.id, HISTORY_WINDOW_MS + 60 * 60 * 1000L)
    }
    val address = device.address
    val audio = "A2DP" in device.profiles
    LaunchedEffect(device.id, device.connected) {
        if (device.connected && audio && address != null) DeviceActions.readCodec(context, address)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .page(),
        verticalArrangement = Arrangement.spacedBy(if (LocalDesign.current.isHistorical) 0.dp else 16.dp),
    ) {
        Entry {
            Text(
                "${DeviceText.kind(context, device.kind)} · ${DeviceText.status(context, device)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device.paired && address != null) LinkActions(device, address)
        }

        Section(stringResource(R.string.section_battery)) {
            Entry {
                if (device.cells.isEmpty()) {
                    Text(
                        stringResource(R.string.no_battery),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    BatteryCells(device, settings.lowThreshold, Modifier.fillMaxWidth())
                    device.estimateMin?.let { Note(DeviceText.estimate(context, it)) }
                }
            }
        }

        if (history.isNotEmpty()) {
            Section(stringResource(R.string.section_history)) {
                Entry { HistoryChart(history, device.isActive, Modifier.fillMaxWidth()) }
            }
        }

        Section(stringResource(R.string.section_info)) {
            // Unknown facts are left out rather than shown empty.
            val facts = listOfNotNull(
                R.string.info_type to DeviceText.kind(context, device.kind),
                device.model?.let { R.string.info_model to it },
                address?.let { R.string.info_address to it },
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
                address?.let { codecs[it] }?.takeIf { device.connected }?.let { R.string.info_codec to it },
                device.rssi?.let { R.string.info_signal to stringResource(R.string.signal_format, it) },
            )
            Entry {
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

        Section(stringResource(R.string.section_device_settings)) {
            SettingToggle(stringResource(R.string.device_notifications), device.id !in settings.muted) { on ->
                Settings.update(context) {
                    it.copy(muted = if (on) it.muted - device.id else it.muted + device.id)
                }
            }
            Box(Modifier.entry()) {
                AppButton(stringResource(R.string.forget_history), primary = false) { Devices.forget(device.id) }
            }
        }
    }
}

/**
 * Connect or disconnect, and the way into Android's Bluetooth settings for when Android
 * refuses the application.
 */
@Composable
private fun LinkActions(device: Device, address: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(device.id) { mutableStateOf(false) }
    var outcome by remember(device.id) { mutableStateOf<LinkOutcome?>(null) }
    val connect = !device.connected

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppButton(
            text = stringResource(if (connect) R.string.action_connect else R.string.action_disconnect),
            enabled = !busy,
        ) {
            busy = true
            outcome = null
            scope.launch {
                outcome = DeviceActions.link(context, address, connect)
                busy = false
            }
        }
        AppButton(stringResource(R.string.action_bluetooth_settings), primary = false) {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    val message = when (outcome) {
        LinkOutcome.ASKED -> R.string.link_asked
        LinkOutcome.NO_ACCESS -> R.string.link_no_access
        LinkOutcome.FAILED -> R.string.link_failed
        null -> null
    }
    if (message != null) Note(stringResource(message))
}
