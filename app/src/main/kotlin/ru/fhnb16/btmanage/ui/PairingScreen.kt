package ru.fhnb16.btmanage.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Discovery

/**
 * The search for a new device: a hint, the devices Android finds, nearest first, and a way
 * to search again. A tap on a device asks Android to pair with it. The search runs while
 * this is on screen. Give it a height: the list takes what the hint and the button leave.
 */
@Composable
fun PairingPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val design = LocalDesign.current
    val found by Discovery.found.collectAsState()
    val phase by Discovery.phase.collectAsState()
    // The device Android refused to pair with, to say so in its row.
    var refused by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        Discovery.start(context)
        onDispose { Discovery.stop() }
    }

    val status = when {
        phase == Discovery.Phase.UNAVAILABLE -> R.string.pair_unavailable
        Discovery.blockedByLocation(context) -> R.string.pair_location
        phase == Discovery.Phase.SEARCHING -> R.string.pair_searching
        found.isEmpty() -> R.string.pair_none
        else -> null
    }
    val side = if (design.isHistorical) 8.dp else 20.dp

    Column(modifier) {
        Text(
            stringResource(R.string.pair_hint),
            modifier = Modifier.padding(horizontal = side, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(found, key = { it.address }) { device ->
                val note = when {
                    device.paired -> R.string.pair_paired
                    device.pairing -> R.string.pair_pairing
                    refused == device.address -> R.string.pair_refused
                    else -> null
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pressable(enabled = !device.paired && !device.pairing) {
                            refused = if (Discovery.pair(device.address)) null else device.address
                        }
                        .then(if (design.isHistorical) Modifier.hairline() else Modifier)
                        .padding(horizontal = side, vertical = 12.dp),
                ) {
                    Text(device.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                    if (note != null) {
                        Text(
                            stringResource(note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = side, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                stringResource(R.string.pair_again),
                primary = false,
                enabled = phase != Discovery.Phase.SEARCHING,
            ) {
                refused = null
                Discovery.start(context)
            }
            if (status != null) {
                Text(
                    stringResource(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The search for a new device as a dialog over the application. */
@Composable
fun PairingDialog(onClose: () -> Unit) {
    val design = LocalDesign.current
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // Material lifts a dialog by its tone; the other designs draw an edge.
            border = if (design == DesignStyle.MATERIAL) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(top = 20.dp)) {
                Text(
                    stringResource(R.string.action_add_device),
                    modifier = Modifier.padding(horizontal = if (design.isHistorical) 8.dp else 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                PairingPanel(Modifier.height(380.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    AppButton(stringResource(R.string.action_close), primary = false, onClick = onClose)
                }
            }
        }
    }
}
