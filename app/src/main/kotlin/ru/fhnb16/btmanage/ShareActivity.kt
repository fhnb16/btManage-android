package ru.fhnb16.btmanage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.data.Peer
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.ShareHub
import ru.fhnb16.btmanage.data.WebShare
import ru.fhnb16.btmanage.ui.AppButton
import ru.fhnb16.btmanage.ui.AppTextField
import ru.fhnb16.btmanage.ui.Glyph
import ru.fhnb16.btmanage.ui.GlyphIcon
import ru.fhnb16.btmanage.ui.LocalDesign
import ru.fhnb16.btmanage.ui.PairDialog
import ru.fhnb16.btmanage.ui.Target
import ru.fhnb16.btmanage.ui.isHistorical
import ru.fhnb16.btmanage.ui.platformName
import ru.fhnb16.btmanage.ui.pressable

/**
 * btManage in Android's share menu: files and text from any application go to another
 * btManage on the network, to a device paired with this one. A device heard but not paired
 * is paired here by its code; a browser on a device without btManage can take the files too.
 *
 * The files are copied before the panel closes, since the right to read them may end with it.
 *
 * Written without a run on a device.
 */
class ShareActivity : PanelActivity() {
    private var uris: List<Uri> = emptyList()
    private var text: String? = null

    override val heightShare: Float = 0.6f

    override fun onCreate(savedInstanceState: Bundle?) {
        uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        super.onCreate(savedInstanceState)
        ShareHub.start(this)
    }

    @Composable
    override fun Content(settings: SettingsData, close: () -> Unit) {
        SendPanel(uris, text, close)
    }

    /** A name for what is being sent: the file's own name, a count, or the start of the text. */
    fun describe(): String {
        if (uris.size == 1) {
            val name = runCatching {
                contentResolver.query(uris[0], arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            if (name != null) return name
        }
        if (uris.size > 1) return getString(R.string.send_files, uris.size)
        return text?.lineSequence()?.firstOrNull()?.take(80).orEmpty()
    }
}

@Composable
private fun SendPanel(uris: List<Uri>, text: String?, close: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ShareActivity
    val scope = rememberCoroutineScope()
    val peers by ShareHub.peers.collectAsState()
    val running by ShareHub.running.collectAsState()
    val webAddresses by WebShare.addresses.collectAsState()
    val side = if (LocalDesign.current.isHistorical) 12.dp else 20.dp
    val what = remember { activity?.describe().orEmpty() }

    var sendingTo by remember { mutableStateOf<String?>(null) }
    var pairingWith by remember { mutableStateOf<Target?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var offered by rememberSaveable { mutableStateOf(false) }

    BackHandler { close() }

    /** Copies and sends everything, then lets the panel go. */
    fun send(target: Target) {
        if (sendingTo != null) return
        sendingTo = target.name
        scope.launch {
            for (uri in uris) ShareHub.sendFile(context, target.address, uri)
            text?.let { ShareHub.sendText(target.address, it) }
            delay(600)
            close()
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            PanelHandle(Modifier.align(Alignment.CenterHorizontally))
            Column(
                Modifier.padding(start = side, end = side, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.send_title), style = MaterialTheme.typography.titleLarge)
                if (what.isNotEmpty()) {
                    Text(what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }

            val sending = sendingTo
            if (sending != null) {
                Text(
                    stringResource(R.string.send_sending, sending),
                    modifier = Modifier.padding(horizontal = side, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }
            if (uris.isEmpty() && text == null) {
                Text(
                    stringResource(R.string.send_nothing),
                    modifier = Modifier.padding(horizontal = side, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (!running) {
                    item { Note(stringResource(R.string.share_not_running), side) }
                } else if (peers.isEmpty()) {
                    item { Note(stringResource(R.string.share_no_peers), side) }
                }
                // Paired devices first: a tap sends. The others are paired first, by their code.
                items(peers.sortedByDescending { it.trusted }, key = { it.id }) { peer ->
                    PeerRow(peer, side) {
                        val target = Target(peer.name, peer.address)
                        if (peer.trusted) send(target) else pairingWith = target
                    }
                }
                item {
                    if (!manual) {
                        Row(Modifier.padding(horizontal = side, vertical = 8.dp)) {
                            AppButton(stringResource(R.string.share_by_address), primary = false) { manual = true }
                        }
                    } else {
                        Column(Modifier.padding(horizontal = side, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTextField(stringResource(R.string.share_address), typed, { typed = it }, monospace = true)
                            val target = Target(typed.trim(), typed.trim())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppButton(stringResource(R.string.share_send), enabled = typed.isNotBlank()) { send(target) }
                                AppButton(stringResource(R.string.share_pair), primary = false, enabled = typed.isNotBlank()) {
                                    pairingWith = target
                                }
                            }
                        }
                    }
                }
                // A device without btManage takes the files in its browser.
                if (uris.isNotEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable {
                                    if (!offered) {
                                        offered = true
                                        WebShare.start(context)
                                        // Not tied to the panel: the copying goes on if it closes.
                                        WebShare.background { WebShare.offerCopies(context.applicationContext, uris) }
                                    }
                                }
                                .padding(horizontal = side, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            GlyphIcon(Glyph.GLOBE, size = 22.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.send_browser), style = MaterialTheme.typography.bodyLarge)
                                val note = if (offered && webAddresses.isNotEmpty()) {
                                    stringResource(R.string.send_browser_open, webAddresses.joinToString("\n"))
                                } else {
                                    stringResource(R.string.send_browser_note)
                                }
                                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Row(Modifier.padding(horizontal = side, vertical = 12.dp)) {
                AppButton(stringResource(if (offered) R.string.send_done else R.string.action_cancel), primary = false) { close() }
            }
        }
    }

    pairingWith?.let { target ->
        PairDialog(target, onDismiss = { pairingWith = null }) { code -> ShareHub.pair(target.address, code) }
    }
}

/** A btManage on the network: what it runs on, and whether it is paired with this one. */
@Composable
private fun PeerRow(peer: Peer, side: Dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = side, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(if (peer.platform == "android") Glyph.PHONE else Glyph.COMPUTER, size = 22.dp, color = scheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(peer.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val state = stringResource(if (peer.trusted) R.string.share_paired else R.string.share_not_paired)
            Text("${platformName(peer.platform)} · $state", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (!peer.trusted) {
            Text(stringResource(R.string.share_pair), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

@Composable
private fun Note(text: String, side: Dp) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = side, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
