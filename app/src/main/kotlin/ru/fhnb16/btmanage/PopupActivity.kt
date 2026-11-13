package ru.fhnb16.btmanage

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.service.DeviceService
import ru.fhnb16.btmanage.ui.AppButton
import ru.fhnb16.btmanage.ui.AppTheme
import ru.fhnb16.btmanage.ui.DesignStyle
import ru.fhnb16.btmanage.ui.DeviceText
import ru.fhnb16.btmanage.ui.LocalDesign
import ru.fhnb16.btmanage.ui.PairingPanel
import ru.fhnb16.btmanage.ui.SegmentedMeter
import ru.fhnb16.btmanage.ui.isHistorical
import ru.fhnb16.btmanage.ui.noiseLabel
import ru.fhnb16.btmanage.ui.pressable
import ru.fhnb16.btmanage.ui.tabular

/**
 * What a tap on the widget opens: a panel over the lower half of the home screen with the
 * connected devices and their levels, a way into the application and a way to add a
 * device. The home screen stays visible behind it, dimmed, and blurred where Android
 * offers that (12 and later, unless the phone has window blur switched off).
 *
 * Written without a run on a device.
 */
class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Settings.load(this)
        Devices.open(this)
        DeviceService.start(this)

        // The shade is drawn by the panel itself, so that it fades in and out with it; the
        // blur comes from Android and appears at once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = BLUR_RADIUS }
        }

        setContent {
            val settings by Settings.state.collectAsState()
            AppTheme(settings) {
                Popup(settings, onClose = { finish() }, openApp = { open(it) })
            }
        }
    }

    /** Opens the application on the list of devices, or on the page of one. */
    private fun open(deviceId: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DEVICE, deviceId)
        runCatching { startActivity(intent) }
        finish()
    }

    private companion object {
        const val BLUR_RADIUS = 40
    }
}

@Composable
private fun Popup(settings: SettingsData, onClose: () -> Unit, openApp: (String?) -> Unit) {
    val context = LocalContext.current
    val design = LocalDesign.current
    val scope = rememberCoroutineScope()
    val devices by Devices.list.collectAsState()
    var pairing by rememberSaveable { mutableStateOf(false) }
    val ready = Devices.available && DeviceService.hasPermissions(context)
    val corner = when (design) {
        DesignStyle.MATERIAL -> 28.dp
        DesignStyle.FLUENT -> 8.dp
        DesignStyle.HOLO -> 2.dp
        DesignStyle.ECLAIR -> 0.dp
    }
    val side = if (design.isHistorical) 8.dp else 20.dp

    // The panel rises from the bottom edge when it opens and sinks back when it closes;
    // the shade behind it fades with it. The activity ends once the panel is gone.
    var open by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = if (open) OPEN_MS else CLOSE_MS, easing = FastOutSlowInEasing),
        label = "panel",
        finishedListener = { value -> if (value == 0f && closing) onClose() },
    )
    LaunchedEffect(Unit) { open = true }
    val close = {
        if (!closing) {
            closing = true
            open = false
        }
    }

    // Pulled down by its top, the panel follows the finger; let go far enough or fast
    // enough, it closes, otherwise it settles back.
    val pull = remember { Animatable(0f) }
    val pullToClose = with(LocalDensity.current) { PULL_TO_CLOSE.toPx() }
    val dragging = rememberDraggableState { delta ->
        scope.launch { pull.snapTo((pull.value + delta).coerceAtLeast(0f)) }
    }

    BackHandler {
        if (pairing) pairing = false else close()
    }

    // A tap beside the panel closes it.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SHADE * shown))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .graphicsLayer { translationY = (1f - shown) * size.height + pull.value },
            shape = RoundedCornerShape(topStart = corner, topEnd = corner),
            // Not quite opaque: the blurred home screen tints the panel.
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.86f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (design == DesignStyle.MATERIAL) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.navigationBarsPadding()) {
                // The top of the panel is what the finger pulls; the list below scrolls.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragging,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                if (pull.value > pullToClose || velocity > FLING_TO_CLOSE) close() else pull.animateTo(0f)
                            },
                        ),
                ) {
                    if (design == DesignStyle.MATERIAL || design == DesignStyle.FLUENT) {
                        // The handle of a bottom sheet, which says the panel can be pulled.
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .align(Alignment.CenterHorizontally)
                                .size(width = 32.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                        )
                    }
                    Text(
                        stringResource(if (pairing) R.string.action_add_device else R.string.group_connected),
                        modifier = Modifier.padding(start = side, end = side, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (pairing) {
                    PairingPanel(Modifier.weight(1f))
                } else {
                    Connected(devices.filter { it.connected }, settings, ready, side, Modifier.weight(1f)) { openApp(it) }
                }
                Row(
                    Modifier.padding(horizontal = side, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pairing) {
                        AppButton(stringResource(R.string.back), primary = false) { pairing = false }
                    } else {
                        AppButton(stringResource(R.string.action_open_app)) { openApp(null) }
                        if (ready) {
                            AppButton(stringResource(R.string.action_add_device), primary = false) { pairing = true }
                        }
                    }
                }
            }
        }
    }
}

private const val OPEN_MS = 300
private const val CLOSE_MS = 220

/** How dark the shade over the home screen gets. */
private const val SHADE = 0.32f

/** A pull further than this, or a fling faster than this (pixels a second), closes the panel. */
private val PULL_TO_CLOSE = 96.dp
private const val FLING_TO_CLOSE = 1200f

/** The connected devices, each with the level that runs out first and, for earbuds, all three. */
@Composable
private fun Connected(
    devices: List<Device>,
    settings: SettingsData,
    ready: Boolean,
    side: Dp,
    modifier: Modifier,
    open: (String) -> Unit,
) {
    val context = LocalContext.current
    if (devices.isEmpty()) {
        Text(
            stringResource(if (ready) R.string.popup_none else R.string.permission_title),
            modifier = modifier.padding(horizontal = side, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        items(devices, key = { it.id }) { device ->
            val cell = device.lowestCell
            Column(
                Modifier
                    .fillMaxWidth()
                    .pressable { open(device.id) }
                    .padding(horizontal = side, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DeviceText.name(context, device),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (cell != null) {
                        Text(
                            DeviceText.percent(context, cell.percent),
                            style = MaterialTheme.typography.bodyLarge.tabular(),
                            color = when {
                                !cell.isFresh -> MaterialTheme.colorScheme.outline
                                cell.isLow(settings.lowThreshold) -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                if (cell != null) {
                    SegmentedMeter(cell.percent, low = cell.isLow(settings.lowThreshold), dimmed = !cell.isFresh)
                }
                // Each battery of earbuds, and the noise mode where the headphones told it.
                val details = listOfNotNull(
                    DeviceText.levels(context, device).takeIf { device.hasEarbudCells },
                    device.noiseMode?.let { stringResource(noiseLabel(it)) },
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
