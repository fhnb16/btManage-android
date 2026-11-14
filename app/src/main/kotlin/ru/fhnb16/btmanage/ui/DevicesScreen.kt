package ru.fhnb16.btmanage.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
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
import ru.fhnb16.btmanage.data.DeviceFacts
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.EventLog
import ru.fhnb16.btmanage.data.FastPairRing
import ru.fhnb16.btmanage.data.LinkOutcome
import ru.fhnb16.btmanage.data.NoiseMode
import ru.fhnb16.btmanage.service.Aap
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
    onAdd: () -> Unit,
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
    val context = LocalContext.current
    // A file the user picks goes to Android's own Bluetooth sharing, which asks for the device.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendOverBluetooth(context, uri)
    }

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
        // Pairing with a device that is not in the list yet, and sending a file to one.
        item(key = "add-device") {
            Row(
                Modifier.padding(horizontal = if (design.isHistorical) 8.dp else 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(stringResource(R.string.action_add_device), primary = false, onClick = onAdd)
                AppButton(stringResource(R.string.action_send_file), primary = false) { pickFile.launch(arrayOf("*/*")) }
            }
        }
    }
}

/**
 * Hands a file to Android's Bluetooth sharing (the Object Push profile), which asks which
 * device to send it to and shows the transfer in a notification. Where a phone has no such
 * sharing under the usual name, the share menu offers whatever can take the file.
 */
private fun sendOverBluetooth(context: Context, uri: Uri) {
    val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    EventLog.note("share", "send over Bluetooth: $type")
    val direct = Intent(send).setPackage("com.android.bluetooth")
    val started = runCatching { context.startActivity(direct) }.isSuccess
    if (!started) runCatching { context.startActivity(Intent.createChooser(send, null)) }
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
                // A download or installation that failed: try again, or take the release page.
                update is UpdateState.Failed && release.apkUrl != null -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppButton(stringResource(R.string.update_retry), primary = false, onClick = onInstall)
                    AppButton(stringResource(R.string.update_page), primary = false, onClick = openPage)
                }
                // A release without an APK: the page is the way out.
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
    val learned by DeviceFacts.facts.collectAsState()
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
            DeviceTools(device, address)
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

        if (device.connected && address != null && device.noiseModes.isNotEmpty()) {
            NoiseSection(device, address)
        }

        if (history.isNotEmpty()) {
            Section(stringResource(R.string.section_history)) {
                Entry { HistoryChart(history, device.isActive, Modifier.fillMaxWidth()) }
            }
        }

        Section(stringResource(R.string.section_info)) {
            // Unknown facts are left out rather than shown empty.
            // What Android has on record for the device, where the application may read it.
            val record = address?.let { learned[it] }
            val facts = listOfNotNull(
                R.string.info_type to DeviceText.kind(context, device.kind),
                record?.manufacturer?.let { R.string.info_manufacturer to it },
                (device.model ?: record?.model)?.let { R.string.info_model to it },
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
                record?.firmware?.let { R.string.info_firmware to it },
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
            RemoveActions(device, address)
        }
    }
}

/**
 * What else can be done with a device: send it a file or text over Bluetooth, look for it
 * by its signal, make it ring where it can.
 */
@Composable
private fun DeviceTools(device: Device, address: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var finding by remember(device.id) { mutableStateOf(false) }
    var writing by remember(device.id) { mutableStateOf(false) }
    var ringNote by remember(device.id) { mutableStateOf<String?>(null) }
    val canRing = remember(address) { address != null && FastPairRing.offers(context, address) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendOverBluetooth(context, uri)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (device.paired) {
            AppButton(stringResource(R.string.action_send_file), primary = false) { pickFile.launch(arrayOf("*/*")) }
            AppButton(stringResource(R.string.action_send_text), primary = false) { writing = true }
        }
        if (device.rssi != null) {
            AppButton(stringResource(R.string.action_find), primary = false) { finding = true }
        }
        if (canRing && address != null) {
            AppButton(stringResource(R.string.action_ring), primary = false) {
                ringNote = null
                scope.launch {
                    val failure = FastPairRing.ring(context, address, on = true)
                    ringNote = if (failure == null) context.getString(R.string.ring_sent) else context.getString(R.string.ring_failed, failure)
                }
            }
        }
    }
    ringNote?.let { Note(it) }

    if (finding) FindDialog(device.id) { finding = false }
    if (writing) BluetoothTextDialog(onDismiss = { writing = false }) { text -> sendTextOverBluetooth(context, text) }
}

/**
 * Looking for a device by its signal: how strong it is, how far that suggests, and whether
 * it grows as the phone moves. Only devices that broadcast give a signal to measure.
 */
@Composable
private fun FindDialog(deviceId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices by Devices.list.collectAsState()
    val rssi = devices.firstOrNull { it.id == deviceId }?.rssi
    var previous by remember { mutableStateOf<Int?>(null) }
    var trend by remember { mutableStateOf(0) }
    LaunchedEffect(rssi) {
        val last = previous
        if (rssi != null && last != null) {
            trend = when {
                rssi >= last + TREND_DB -> 1
                rssi <= last - TREND_DB -> -1
                else -> trend
            }
        }
        if (rssi != null) previous = rssi
    }

    AppDialog(
        title = stringResource(R.string.find_title),
        onDismiss = onDismiss,
        buttons = { AppButton(stringResource(R.string.action_close), onClick = onDismiss) },
    ) {
        if (rssi == null) {
            Note(stringResource(R.string.find_lost))
        } else {
            Text(DeviceText.distance(context, rssi), style = MaterialTheme.typography.headlineMedium.tabular())
            SegmentedMeter(signalPercent(rssi), low = false)
            val hint = when (trend) {
                1 -> R.string.find_warmer
                -1 -> R.string.find_colder
                else -> R.string.find_move
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
            Note(stringResource(R.string.find_note, rssi))
        }
    }
}

/** How strong a signal is, as a share of the range between faint (−100 dBm) and close (−40 dBm). */
private fun signalPercent(rssi: Int): Int = ((rssi + 100) * 100 / 60).coerceIn(0, 100)

/** A change of the signal this large, in dB, counts as closer or further. */
private const val TREND_DB = 4

@Composable
private fun BluetoothTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AppDialog(
        title = stringResource(R.string.action_send_text),
        onDismiss = onDismiss,
        buttons = {
            AppButton(stringResource(R.string.action_cancel), primary = false, onClick = onDismiss)
            AppButton(stringResource(R.string.share_send), enabled = text.isNotEmpty()) {
                onSend(text)
                onDismiss()
            }
        },
    ) {
        Note(stringResource(R.string.send_text_note))
        AppTextField(stringResource(R.string.share_text), text, { text = it }, singleLine = false)
    }
}

/** Sends text over Bluetooth as a small text file, the way phones exchange notes. */
private fun sendTextOverBluetooth(context: Context, text: String) {
    val folder = java.io.File(context.cacheDir, "share").apply { mkdirs() }
    val file = java.io.File(folder, "btManage-${System.currentTimeMillis()}.txt")
    runCatching { file.writeText(text) }.getOrNull() ?: return
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }.getOrNull() ?: return
    sendOverBluetooth(context, uri)
}

/**
 * Taking a device off: hiding it from the lists, which the application can always do, and
 * for a paired device, removing the pairing from Android. The second asks for a second
 * press, since a removed headset must be paired again from scratch.
 */
@Composable
private fun RemoveActions(device: Device, address: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember(device.id) { mutableStateOf(false) }
    var outcome by remember(device.id) { mutableStateOf<LinkOutcome?>(null) }

    Row(Modifier.entry(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton(stringResource(R.string.action_hide), primary = false) {
            Settings.update(context) { it.copy(hidden = it.hidden + device.id) }
        }
        if (device.paired && address != null) {
            AppButton(stringResource(if (confirm) R.string.action_unpair_confirm else R.string.action_unpair), primary = confirm) {
                if (!confirm) {
                    confirm = true
                } else {
                    confirm = false
                    scope.launch { outcome = DeviceActions.unpair(context, address) }
                }
            }
        }
    }
    val message = when (outcome) {
        LinkOutcome.ASKED -> R.string.unpair_asked
        LinkOutcome.NO_ACCESS -> R.string.unpair_no_access
        LinkOutcome.FAILED -> R.string.link_failed
        null -> null
    }
    if (message != null) Note(stringResource(message), Modifier.entry())
}

/**
 * Noise control and conversation awareness of AirPods. A choice is sent over the
 * accessory protocol; the mark moves when the headphones confirm it, not before. When the
 * phone cannot reach the headphones that way, the section says why and what would help.
 */
@Composable
private fun NoiseSection(device: Device, address: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val links by Aap.state.collectAsState()
    var pending by remember(device.id) { mutableStateOf<NoiseMode?>(null) }
    var failed by remember(device.id) { mutableStateOf(false) }
    val link = links[address]

    // The headphones confirmed a mode: the wait is over.
    LaunchedEffect(device.noiseMode) { pending = null }

    fun attempt(action: suspend () -> String?) {
        failed = false
        scope.launch {
            val failure = action()
            if (failure != null) {
                pending = null
                failed = true
            }
        }
    }

    Section(stringResource(R.string.section_noise)) {
        for (mode in device.noiseModes) {
            OptionRow(
                title = stringResource(noiseLabel(mode)),
                note = null,
                selected = (pending ?: device.noiseMode) == mode,
                radio = true,
            ) {
                if (mode != device.noiseMode) {
                    pending = mode
                    attempt { Aap.setNoise(context, address, mode) }
                }
            }
        }
        val awareness = device.awareness
        if (awareness != null) {
            SettingToggle(stringResource(R.string.setting_awareness), awareness) { on ->
                attempt { Aap.setAwareness(context, address, on) }
            }
        }
        val note = when {
            failed || link?.state == Aap.State.FAILED -> R.string.noise_unavailable
            link?.state == Aap.State.CONNECTING || pending != null -> R.string.noise_connecting
            device.noiseMode == null -> R.string.noise_unknown
            else -> null
        }
        if (note != null) Note(stringResource(note), Modifier.entry())
    }
}

internal fun noiseLabel(mode: NoiseMode): Int = when (mode) {
    NoiseMode.OFF -> R.string.noise_off
    NoiseMode.NOISE_CANCELLATION -> R.string.noise_cancellation
    NoiseMode.TRANSPARENCY -> R.string.noise_transparency
    NoiseMode.ADAPTIVE -> R.string.noise_adaptive
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
