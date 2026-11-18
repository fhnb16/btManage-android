package ru.fhnb16.btmanage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.NoiseMode
import ru.fhnb16.btmanage.service.Aap

/**
 * The noise modes of headphones side by side, the one in effect filled with the accent, for
 * the widget's panel. A choice is sent over the accessory protocol; the fill moves when the
 * headphones confirm it. When the phone cannot reach them that way, a line says so.
 */
@Composable
fun NoiseChoice(device: Device, address: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember(device.id) { mutableStateOf<NoiseMode?>(null) }
    var failed by remember(device.id) { mutableStateOf(false) }
    LaunchedEffect(device.noiseMode) { pending = null }

    val shape = RoundedCornerShape(if (LocalDesign.current.isHistorical) 2.dp else 12.dp)
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (mode in device.noiseModes) {
                val chosen = (pending ?: device.noiseMode) == mode
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .background(if (chosen) scheme.primary else scheme.surfaceContainerHighest)
                        .pressable {
                            if (mode != device.noiseMode) {
                                pending = mode
                                failed = false
                                scope.launch {
                                    if (Aap.setNoise(context, address, mode) != null) {
                                        pending = null
                                        failed = true
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(noiseLabel(mode)),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (chosen) scheme.onPrimary else scheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.noise_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
