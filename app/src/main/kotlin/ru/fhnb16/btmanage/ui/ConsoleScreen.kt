package ru.fhnb16.btmanage.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Console
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.EventLog

/**
 * The console of the developer mode, over the whole screen: a device, a way to reach it,
 * a command, and what went back and forth. The commands and answers are technical and in
 * English; the labels of the screen are translated.
 */
@Composable
fun ConsoleDialog(devices: List<Device>, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                AppTopBar(stringResource(R.string.console_title), onBack = {
                    Console.closeAll()
                    onClose()
                })
                ConsoleBody(devices)
            }
        }
    }
}

@Composable
private fun ConsoleBody(devices: List<Device>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets = devices.filter { it.address != null }
    var address by rememberSaveable { mutableStateOf(targets.firstOrNull()?.address.orEmpty()) }
    var transport by rememberSaveable { mutableStateOf(Console.Transport.GATT) }
    var target by rememberSaveable { mutableStateOf("") }
    var payload by rememberSaveable { mutableStateOf("") }
    var hex by rememberSaveable { mutableStateOf(false) }
    val connection by Console.connection.collectAsState()
    val characteristics by Console.characteristics.collectAsState()
    val lines by EventLog.recent.collectAsState()
    val console = lines.filter { "[console]" in it }

    fun send() {
        val bytes = Console.payload(payload, hex)
        if (bytes == null) {
            Console.note("not hexadecimal: $payload")
            return
        }
        when (transport) {
            Console.Transport.GATT -> {
                val uuid = Console.uuid(target) ?: return Console.note("not a UUID: $target")
                Console.write(uuid, bytes)
            }
            Console.Transport.RFCOMM -> scope.launch { Console.sendRfcomm(bytes) }
            Console.Transport.HEADSET -> Console.sendHeadset(context, address, payload)
            Console.Transport.AAP -> scope.launch { Console.sendAap(context, address, bytes) }
        }
    }

    fun openRfcomm(service: String, secure: Boolean) {
        val uuid = Console.uuid(service)
        if (uuid == null) {
            Console.note("not a UUID: $service")
        } else {
            scope.launch { Console.connectRfcomm(context, address, uuid, secure) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .page(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (targets.isEmpty()) {
            Note(stringResource(R.string.console_no_devices), Modifier.entry())
            return@Column
        }

        // Where the commands go: which device, over which way, and whether that way is open.
        Section(stringResource(R.string.console_where)) {
            SettingChoice(
                stringResource(R.string.console_device),
                targets.map { it.address.orEmpty() to DeviceText.name(context, it) },
                address,
            ) { address = it }
            SettingChoice(
                stringResource(R.string.console_transport),
                listOf(
                    Console.Transport.GATT to stringResource(R.string.console_way_gatt),
                    Console.Transport.RFCOMM to stringResource(R.string.console_way_rfcomm),
                    Console.Transport.HEADSET to stringResource(R.string.console_way_headset),
                    Console.Transport.AAP to stringResource(R.string.console_way_aap),
                ),
                transport,
            ) {
                transport = it
                if (it == Console.Transport.RFCOMM && target.isEmpty()) target = Console.SPP
            }
            if (transport == Console.Transport.RFCOMM) {
                AppTextField(stringResource(R.string.console_service), target, { target = it }, Modifier.entry(), monospace = true)
            }
            Note(
                if (connection.isNotEmpty()) connection else stringResource(R.string.console_not_connected),
                Modifier.entry(),
            )
            if (transport == Console.Transport.HEADSET) Note(stringResource(R.string.console_headset_note), Modifier.entry())
            Row(Modifier.entry(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (transport) {
                    Console.Transport.GATT -> AppButton(stringResource(R.string.console_connect)) {
                        Console.connectGatt(context, address)
                    }
                    Console.Transport.RFCOMM -> {
                        AppButton(stringResource(R.string.console_connect)) { openRfcomm(target, secure = true) }
                        AppButton(stringResource(R.string.console_connect_insecure), primary = false) { openRfcomm(target, secure = false) }
                    }
                    else -> {}
                }
                if (connection.isNotEmpty()) {
                    AppButton(stringResource(R.string.console_disconnect), primary = false) { Console.closeAll() }
                }
            }
        }

        // What goes: a template fills in the fields, a template that reads does so at once.
        Section(stringResource(R.string.console_command)) {
            val templates = Console.templates.filter { it.transport == transport }
            if (templates.isNotEmpty()) {
                SettingChoice(
                    stringResource(R.string.console_template),
                    listOf<Pair<Console.Template?, String>>(null to stringResource(R.string.console_template_none)) +
                        templates.map { it to it.title },
                    null,
                ) { template ->
                    if (template != null) {
                        template.target?.let { target = it }
                        payload = template.payload
                        hex = template.hex
                        if (template.read) Console.uuid(template.target.orEmpty())?.let(Console::read)
                    }
                }
            }
            if (transport == Console.Transport.GATT) {
                AppTextField(stringResource(R.string.console_characteristic), target, { target = it }, Modifier.entry(), monospace = true)
            }
            AppTextField(stringResource(R.string.console_payload), payload, { payload = it }, Modifier.entry(), monospace = true)
            if (transport != Console.Transport.HEADSET) {
                SettingToggle(stringResource(R.string.console_hex), hex) { hex = it }
            }
            Row(Modifier.entry(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(stringResource(R.string.console_send)) { send() }
                if (transport == Console.Transport.GATT) {
                    AppButton(stringResource(R.string.console_read), primary = false) {
                        Console.uuid(target)?.let(Console::read) ?: Console.note("not a UUID: $target")
                    }
                    AppButton(stringResource(R.string.console_subscribe), primary = false) {
                        Console.uuid(target)?.let(Console::subscribe) ?: Console.note("not a UUID: $target")
                    }
                }
            }
        }

        // What the GATT server offers: a tap picks the characteristic.
        if (transport == Console.Transport.GATT && characteristics.isNotEmpty()) {
            Section(stringResource(R.string.console_characteristics)) {
                for (characteristic in characteristics) {
                    val abilities = listOfNotNull(
                        "R".takeIf { characteristic.canRead },
                        "W".takeIf { characteristic.canWrite },
                        "N".takeIf { characteristic.canNotify },
                    ).joinToString("")
                    Text(
                        "${Console.name(characteristic.uuid)}  $abilities\n  in ${Console.name(characteristic.service)}",
                        Modifier
                            .fillMaxWidth()
                            .pressable { target = characteristic.uuid.toString() }
                            .entry(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // What went back and forth, newest last.
        Section(stringResource(R.string.console_output)) {
            val list = rememberLazyListState()
            LaunchedEffect(console.size) {
                if (console.isNotEmpty()) list.scrollToItem(console.size - 1)
            }
            LazyColumn(
                Modifier
                    .entry()
                    .heightIn(max = 360.dp),
                state = list,
            ) {
                items(console) { line ->
                    Text(
                        line.substringAfter("[console] "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
