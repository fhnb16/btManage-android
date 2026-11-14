package ru.fhnb16.btmanage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Peer
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.ShareHub
import ru.fhnb16.btmanage.data.ShareItem
import ru.fhnb16.btmanage.data.WebShare
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Where something is to go: a device heard on the network, or an address typed in. */
private data class Target(val name: String, val address: String)

/**
 * Files and text to and from other devices with btManage on the same network.
 *
 * At the top, this device: whether it takes items, and the code another device types in
 * to pair with it. Then the devices heard, each with what can be done with it: pair, or
 * send text or a file. Last, what came and went, with what can be done with it.
 */
@Composable
fun ShareScreen(settings: SettingsData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by ShareHub.peers.collectAsState()
    val items by ShareHub.items.collectAsState()
    val running by ShareHub.running.collectAsState()
    val code by ShareHub.code.collectAsState()
    var addresses by remember { mutableStateOf(emptyList<String>()) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }

    // What a dialog is open for: text to a device, pairing with one.
    var writingTo by remember { mutableStateOf<Target?>(null) }
    var pairingWith by remember { mutableStateOf<Target?>(null) }
    var fileTo by remember { mutableStateOf<Target?>(null) }

    val webRunning by WebShare.running.collectAsState()
    val webAddresses by WebShare.addresses.collectAsState()
    val offered by WebShare.offered.collectAsState()

    LaunchedEffect(Unit) {
        ShareHub.start(context)
        // The addresses change with the network; look again now and then.
        while (true) {
            addresses = ShareHub.addresses()
            WebShare.refreshAddresses()
            delay(5_000)
        }
    }
    val pickOffered = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) WebShare.offer(context, uris)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = fileTo
        if (uri != null && target != null) scope.launch { ShareHub.sendFile(context, target.address, uri) }
    }
    fun sendFile(target: Target) {
        fileTo = target
        pickFile.launch(arrayOf("*/*"))
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .page(),
        verticalArrangement = Arrangement.spacedBy(if (LocalDesign.current.isHistorical) 0.dp else 8.dp),
    ) {
        Section(stringResource(R.string.share_this_device)) {
            if (!running) {
                Note(stringResource(R.string.share_not_running), Modifier.entry())
            } else {
                Column(Modifier.entry(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ShareHub.name, style = MaterialTheme.typography.titleMedium)
                    Note(stringResource(R.string.share_visible))
                }
            }
            SettingToggle(stringResource(R.string.share_receive), settings.shareReceive) { on ->
                Settings.update(context) { it.copy(shareReceive = on) }
                ShareHub.setReceiving(on)
            }
            if (running && code.isNotEmpty()) {
                Column(Modifier.entry(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.share_code),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The moment of this screen: six digits, read out across the room.
                    Text(
                        code.chunked(3).joinToString(" "),
                        style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp).tabular(),
                    )
                    Note(stringResource(R.string.share_code_note))
                }
            }
        }

        Section(stringResource(R.string.share_peers)) {
            if (peers.isEmpty()) Note(stringResource(R.string.share_no_peers), Modifier.entry())
            for (peer in peers) {
                PeerRow(
                    peer = peer,
                    onText = { writingTo = Target(peer.name, peer.address) },
                    onFile = { sendFile(Target(peer.name, peer.address)) },
                    onPair = { pairingWith = Target(peer.name, peer.address) },
                    onForget = { ShareHub.forget(peer.id) },
                )
            }
            if (!manual) {
                Row(Modifier.entry()) {
                    AppButton(stringResource(R.string.share_by_address), primary = false) { manual = true }
                }
            } else {
                AppTextField(stringResource(R.string.share_address), typed, { typed = it }, Modifier.entry(), monospace = true)
                if (addresses.isNotEmpty()) Note(stringResource(R.string.share_own_address, addresses.joinToString(", ")), Modifier.entry())
                val target = Target(typed.trim(), typed.trim())
                Row(Modifier.entry(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(stringResource(R.string.share_pair), primary = false, enabled = typed.isNotBlank()) { pairingWith = target }
                    AppButton(stringResource(R.string.share_kind_text), primary = false, enabled = typed.isNotBlank()) { writingTo = target }
                    AppButton(stringResource(R.string.share_kind_file), primary = false, enabled = typed.isNotBlank()) { sendFile(target) }
                }
            }
        }

        // Devices without btManage: a page in their browser.
        Section(stringResource(R.string.web_title)) {
            SettingToggle(stringResource(R.string.web_toggle), webRunning) { on ->
                if (on) WebShare.start(context) else WebShare.stop()
            }
            if (!webRunning) {
                Note(stringResource(R.string.web_off_note), Modifier.entry())
            } else {
                Column(Modifier.entry(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (webAddresses.isEmpty()) {
                        Note(stringResource(R.string.web_no_network))
                    } else {
                        Text(
                            stringResource(R.string.web_open),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (address in webAddresses) {
                            Text(address, style = MaterialTheme.typography.titleMedium.tabular())
                        }
                    }
                    Note(stringResource(R.string.web_note))
                }
                Row(Modifier.entry(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(stringResource(R.string.web_offer)) { pickOffered.launch(arrayOf("*/*")) }
                    webAddresses.firstOrNull()?.let { address ->
                        AppButton(stringResource(R.string.diagnostics_copy), primary = false) {
                            runCatching {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("btManage", address))
                            }
                        }
                    }
                }
                for (file in offered) {
                    Row(Modifier.entry(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            if (file.size >= 0) Note(android.text.format.Formatter.formatShortFileSize(context, file.size))
                        }
                        AppButton(stringResource(R.string.web_withdraw), primary = false) { WebShare.withdraw(file.id) }
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            Section(stringResource(R.string.share_history)) {
                for (item in items) {
                    HistoryRow(context, item) {
                        val known = peers.firstOrNull { it.address == item.other }
                        pairingWith = Target(known?.name ?: item.other, item.other)
                    }
                }
            }
        }
    }

    writingTo?.let { target -> TextDialog(target, onDismiss = { writingTo = null }) { text -> ShareHub.sendText(target.address, text) } }
    pairingWith?.let { target -> PairDialog(target, onDismiss = { pairingWith = null }) { entered -> ShareHub.pair(target.address, entered) } }
}

/** A device on the network and what can be done with it. */
@Composable
private fun PeerRow(peer: Peer, onText: () -> Unit, onFile: () -> Unit, onPair: () -> Unit, onForget: () -> Unit) {
    Column(Modifier.entry(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(peer.name, style = MaterialTheme.typography.titleMedium)
            val state = stringResource(if (peer.trusted) R.string.share_paired else R.string.share_not_paired)
            Note("${platformName(peer.platform)} · $state")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (peer.trusted) {
                AppButton(stringResource(R.string.share_kind_text), onClick = onText)
                AppButton(stringResource(R.string.share_kind_file), primary = false, onClick = onFile)
                AppButton(stringResource(R.string.share_forget), primary = false, onClick = onForget)
            } else {
                AppButton(stringResource(R.string.share_pair), onClick = onPair)
            }
        }
    }
}

@Composable
private fun platformName(platform: String): String = when (platform) {
    "android" -> "Android"
    "windows" -> "Windows"
    "linux" -> "Linux"
    else -> platform
}

/** One thing that came or went: who, what, when, and what can be done with it now. */
@Composable
private fun HistoryRow(context: Context, item: ShareItem, onPair: () -> Unit) {
    val time = remember(item.at) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.at)) }
    val title = when (item.type) {
        "received" -> stringResource(R.string.share_from, item.other)
        "sent" -> stringResource(R.string.share_to, item.other)
        "paired" -> stringResource(R.string.share_paired_with, item.other)
        else -> stringResource(R.string.share_not_sent, item.other)
    }
    val what = when {
        item.type == "paired" -> null
        item.type == "failed" -> if (item.needsPairing) stringResource(R.string.share_needs_pairing) else item.error
        item.kind == "text" -> item.text ?: stringResource(R.string.share_kind_text)
        else -> item.name
    }
    Column(Modifier.entry(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(time, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!what.isNullOrEmpty()) {
            Text(
                what,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.type == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
        val path = item.path
        val text = item.text
        if (item.type == "received" && (text != null || path != null)) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (text != null) {
                    AppButton(stringResource(R.string.diagnostics_copy), primary = false) {
                        runCatching {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("btManage", text))
                        }
                    }
                }
                if (path != null) {
                    AppButton(stringResource(R.string.share_open), primary = false) { openFile(context, File(path), Intent.ACTION_VIEW) }
                    AppButton(stringResource(R.string.share_pass_on), primary = false) { openFile(context, File(path), Intent.ACTION_SEND) }
                }
            }
        }
        if (item.type == "failed" && item.needsPairing) {
            Row(Modifier.padding(top = 4.dp)) {
                AppButton(stringResource(R.string.share_pair), primary = false, onClick = onPair)
            }
        }
    }
}

@Composable
private fun TextDialog(target: Target, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AppDialog(
        title = stringResource(R.string.share_text_to, target.name),
        onDismiss = onDismiss,
        buttons = {
            AppButton(stringResource(R.string.action_cancel), primary = false, onClick = onDismiss)
            AppButton(stringResource(R.string.share_send), enabled = text.isNotEmpty()) {
                onSend(text)
                onDismiss()
            }
        },
    ) {
        AppTextField(stringResource(R.string.share_text), text, { text = it }, singleLine = false)
    }
}

@Composable
private fun PairDialog(target: Target, onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val digits = code.filter(Char::isDigit)
    AppDialog(
        title = stringResource(R.string.share_pair_with, target.name),
        onDismiss = onDismiss,
        buttons = {
            AppButton(stringResource(R.string.action_cancel), primary = false, onClick = onDismiss)
            AppButton(stringResource(R.string.share_pair), enabled = digits.length == 6) {
                onPair(digits)
                onDismiss()
            }
        },
    ) {
        Note(stringResource(R.string.share_pair_note, target.name))
        AppTextField(stringResource(R.string.share_code), code, { code = it.take(7) }, monospace = true)
    }
}

/** Opens a received file in another application, or hands it on through the share menu. */
private fun openFile(context: Context, file: File, action: String) {
    val uri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", file) }.getOrNull() ?: return
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    val intent = Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (action == Intent.ACTION_SEND) intent.setType(type).putExtra(Intent.EXTRA_STREAM, uri) else intent.setDataAndType(uri, type)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
