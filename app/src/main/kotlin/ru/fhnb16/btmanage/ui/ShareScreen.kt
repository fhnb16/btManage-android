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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.ShareHub
import ru.fhnb16.btmanage.data.ShareItem
import java.io.File

/**
 * Files and text to and from other btManage applications on the same network: who is
 * there, what to send, and what came and went.
 */
@Composable
fun ShareScreen(settings: SettingsData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by ShareHub.peers.collectAsState()
    val items by ShareHub.items.collectAsState()
    val running by ShareHub.running.collectAsState()
    var target by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var addresses by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        ShareHub.start(context)
        // The addresses change with the network; look again now and then.
        while (true) {
            addresses = ShareHub.addresses()
            delay(5_000)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { ShareHub.sendFile(context, target, uri) }
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
                Text(ShareHub.name, Modifier.entry())
                if (addresses.isNotEmpty()) Note(addresses.joinToString(", "), Modifier.entry())
            }
            SettingToggle(stringResource(R.string.share_receive), settings.shareReceive) { on ->
                Settings.update(context) { it.copy(shareReceive = on) }
                ShareHub.setReceiving(on)
            }
            Note(stringResource(R.string.share_note), Modifier.entry())
        }

        Section(stringResource(R.string.share_peers)) {
            if (peers.isEmpty()) Note(stringResource(R.string.share_no_peers), Modifier.entry())
            for (peer in peers) {
                OptionRow(peer.name, "${peer.platform} · ${peer.address}", target == peer.address, radio = true) {
                    target = peer.address
                }
            }
            AppTextField(stringResource(R.string.share_address), target, { target = it }, Modifier.entry(), monospace = true)
        }

        Section(stringResource(R.string.share_send)) {
            AppTextField(stringResource(R.string.share_text), text, { text = it }, Modifier.entry())
            Row(Modifier.entry(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(stringResource(R.string.share_send_text), enabled = target.isNotBlank() && text.isNotEmpty()) {
                    ShareHub.sendText(target, text)
                    text = ""
                }
                AppButton(stringResource(R.string.share_send_file), primary = false, enabled = target.isNotBlank()) {
                    pickFile.launch(arrayOf("*/*"))
                }
            }
        }

        if (items.isNotEmpty()) {
            Section(stringResource(R.string.share_history)) {
                for (item in items) HistoryItem(context, item)
            }
        }
    }
}

@Composable
private fun HistoryItem(context: Context, item: ShareItem) {
    val what = if (item.kind == "text") stringResource(R.string.share_kind_text) else item.name
    val title = when (item.type) {
        "received" -> stringResource(R.string.share_received, what, item.other)
        "sent" -> stringResource(R.string.share_sent, what, item.other)
        else -> stringResource(R.string.share_failed, what.ifEmpty { item.other }, item.error.orEmpty())
    }
    Column(Modifier.entry(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (item.text != null) {
            Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val path = item.path
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.text != null) {
                AppButton(stringResource(R.string.diagnostics_copy), primary = false) {
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("btManage", item.text))
                    }
                }
            }
            if (path != null) {
                AppButton(stringResource(R.string.share_open), primary = false) { openFile(context, File(path), Intent.ACTION_VIEW) }
                AppButton(stringResource(R.string.share_pass_on), primary = false) { openFile(context, File(path), Intent.ACTION_SEND) }
            }
        }
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
