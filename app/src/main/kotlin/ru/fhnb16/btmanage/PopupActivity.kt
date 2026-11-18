package ru.fhnb16.btmanage

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fhnb16.btmanage.data.Cell
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Role
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.service.DeviceService
import ru.fhnb16.btmanage.ui.AppButton
import ru.fhnb16.btmanage.ui.DeviceText
import ru.fhnb16.btmanage.ui.Glyph
import ru.fhnb16.btmanage.ui.GlyphIcon
import ru.fhnb16.btmanage.ui.LocalDesign
import ru.fhnb16.btmanage.ui.NoiseChoice
import ru.fhnb16.btmanage.ui.PairingPanel
import ru.fhnb16.btmanage.ui.SegmentedMeter
import ru.fhnb16.btmanage.ui.isHistorical
import ru.fhnb16.btmanage.ui.pressable
import ru.fhnb16.btmanage.ui.tabular

/**
 * What a tap on the widget opens, laid out as the tray panel of the Windows application: the
 * headphones in use at the top with each of their batteries and their noise control, the
 * other active devices under them, and at the bottom the way into the application, to its
 * settings and to adding a device.
 *
 * Written without a run on a device.
 */
class PopupActivity : PanelActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceService.start(this)
    }

    @Composable
    override fun Content(settings: SettingsData, close: () -> Unit) {
        Popup(settings, onClose = close, openApp = { device, page -> open(device, page) })
    }

    /** Opens the application on the list of devices, on the page of one, or on a tab. */
    private fun open(deviceId: String?, page: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DEVICE, deviceId)
            .putExtra(MainActivity.EXTRA_PAGE, page)
        runCatching { startActivity(intent) }
        finishNow()
    }
}

@Composable
private fun Popup(settings: SettingsData, onClose: () -> Unit, openApp: (device: String?, page: String?) -> Unit) {
    val context = LocalContext.current
    val design = LocalDesign.current
    val devices by Devices.list.collectAsState()
    var pairing by rememberSaveable { mutableStateOf(false) }
    val ready = Devices.available && DeviceService.hasPermissions(context)
    val side = if (design.isHistorical) 12.dp else 20.dp

    BackHandler {
        if (pairing) pairing = false else onClose()
    }

    val active = devices.filter { it.isActive && settings.shows(it) }
    // The device on top: headphones with their batteries first, then anything with a level.
    val featured = active.firstOrNull { it.connected && it.hasEarbudCells }
        ?: active.firstOrNull { it.hasEarbudCells }
        ?: active.firstOrNull { it.connected && it.cells.isNotEmpty() }
    val others = active.filter { it.id != featured?.id }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            PanelHandle(Modifier.align(Alignment.CenterHorizontally))
            if (pairing) {
                Text(
                    stringResource(R.string.action_add_device),
                    modifier = Modifier.padding(start = side, end = side, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                PairingPanel(Modifier.weight(1f))
                Row(Modifier.padding(horizontal = side, vertical = 12.dp)) {
                    AppButton(stringResource(R.string.back), primary = false) { pairing = false }
                }
                return@Column
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (featured != null) {
                    item(key = "featured") {
                        Featured(featured, settings, side) { openApp(featured.id, null) }
                    }
                }
                if (featured == null && others.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (ready) R.string.popup_none else R.string.permission_title),
                            modifier = Modifier.padding(horizontal = side, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (featured != null && others.isNotEmpty()) {
                    item(key = "line") { Line(side) }
                }
                items(others, key = { it.id }) { device ->
                    OtherRow(device, settings, side) { openApp(device.id, null) }
                }
            }

            Line(side)
            // Under the devices, as on Windows: everything, then the settings and a new device.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = side - 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.popup_all_devices),
                    modifier = Modifier
                        .pressable { openApp(null, null) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                if (ready) {
                    FooterIcon(Glyph.PLUS, stringResource(R.string.action_add_device)) { pairing = true }
                }
                FooterIcon(Glyph.GEAR, stringResource(R.string.tab_settings)) { openApp(null, MainActivity.PAGE_SETTINGS) }
            }
        }
    }
}

/** The headphones in use: name, then a column per battery, then noise control. */
@Composable
private fun Featured(device: Device, settings: SettingsData, side: Dp, open: () -> Unit) {
    val context = LocalContext.current
    val newest = device.cells.minOfOrNull { it.ageMs }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = side, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(onClick = open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                DeviceText.name(context, device),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            // How old the newest level is, once it is no longer current.
            if (newest != null && newest >= 2 * 60_000) {
                Text(
                    DeviceText.age(context, newest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val roles = if (device.hasEarbudCells) listOf(Role.LEFT, Role.RIGHT, Role.CASE) else listOf(Role.MAIN)
            for (role in roles) {
                PanelCell(role, device.cell(role), settings.lowThreshold, Modifier.weight(1f))
            }
            // A single battery keeps the width of one column of three.
            repeat(3 - roles.size) { Spacer(Modifier.weight(1f)) }
        }
        val address = device.address
        if (device.connected && address != null && device.noiseModes.isNotEmpty()) {
            NoiseChoice(device, address)
        }
    }
}

/** One battery: its outline, the level large, the meter, and what it is. */
@Composable
private fun PanelCell(role: Role, cell: Cell?, lowThreshold: Int, modifier: Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val current = cell?.isFresh == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlyphIcon(Glyph.of(role), size = 28.dp, color = scheme.onSurfaceVariant)
        Text(
            cell?.let { DeviceText.percent(context, it.percent) } ?: stringResource(R.string.percent_unknown),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp).tabular(),
            color = when {
                cell == null -> scheme.outline
                current -> scheme.onSurface
                else -> scheme.onSurfaceVariant
            },
            maxLines = 1,
        )
        SegmentedMeter(cell?.percent, low = cell?.isLow(lowThreshold) == true, dimmed = !current)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val label = listOfNotNull(
                DeviceText.roleLabel(context, role),
                stringResource(R.string.in_ear).takeIf { cell?.inEar == true },
            ).joinToString(" · ")
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
            if (cell?.charging == true) GlyphIcon(Glyph.BOLT, size = 14.dp, color = scheme.onSurfaceVariant)
        }
    }
}

/** Another active device: its glyph, its name, and its level or its state on the right. */
@Composable
private fun OtherRow(device: Device, settings: SettingsData, side: Dp, open: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val cell = device.lowestCell
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = open)
            .padding(horizontal = side, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(Glyph.of(device), size = 22.dp, color = scheme.onSurfaceVariant)
        Text(
            DeviceText.name(context, device),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        if (cell != null) {
            if (cell.charging) GlyphIcon(Glyph.BOLT, size = 14.dp, color = scheme.onSurfaceVariant)
            Text(
                DeviceText.percent(context, cell.percent),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = when {
                    !cell.isFresh -> scheme.outline
                    cell.isLow(settings.lowThreshold) -> scheme.error
                    else -> scheme.onSurface
                },
            )
        } else {
            Text(
                stringResource(if (device.connected) R.string.state_connected else R.string.state_nearby),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Line(side: Dp) {
    Box(
        Modifier
            .padding(horizontal = side)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun FooterIcon(glyph: Glyph, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .pressable(onClick = onClick)
            // What a reader of the screen says for a button drawn without text.
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = 22.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
