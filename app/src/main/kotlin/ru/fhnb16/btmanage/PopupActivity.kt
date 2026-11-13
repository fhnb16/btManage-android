package ru.fhnb16.btmanage

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
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
import java.util.function.Consumer

/**
 * What a tap on the widget opens: a panel over the lower half of the home screen with the
 * connected devices and their levels, a way into the application and a way to add a
 * device.
 *
 * The window is the panel itself, so that Android blurs the home screen behind the panel
 * only (`setBackgroundBlurRadius`, Android 12 and later), and the panel's fill shades that
 * blur; the rest of the screen is left as it is. Where Android blurs nothing (before
 * Android 12, in battery saver, or with blur switched off), the panel is all but opaque so
 * that it stays readable.
 *
 * The panel rises from the bottom edge and sinks back into it. A tap above it, Back, or a
 * pull on its top edge closes it. The window itself stays in place; the panel moves inside
 * it, and the blur moves with the panel's background.
 *
 * Written without a run on a device.
 */
class PopupActivity : ComponentActivity() {
    /** Whether Android blurs behind windows at the moment. */
    private val blurred = mutableStateOf(false)
    private var blurListener: Consumer<Boolean>? = null

    // The pull on the top edge of the panel, in screen pixels.
    private var dragFrom: Float? = null
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var moving: ValueAnimator? = null
    private var closing = false

    /**
     * The window stays where it is, over the lower half of the screen; the panel moves
     * inside it. The blur Android draws behind the window background follows the
     * background as it moves, and what goes below the window is cut off at the bottom edge
     * of the screen.
     */
    private val decor get() = window.decorView
    private val offset: Float get() = decor.translationY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        withoutWindowAnimation()
        Settings.load(this)
        Devices.open(this)
        DeviceService.start(this)

        // The window lies over the lower half of the screen, down behind the navigation bar.
        // Nothing outside it is dimmed or blurred: the panel's own fill shades what it blurs.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.BOTTOM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setBackgroundBlurRadius(BLUR_RADIUS)
                // Told at once, and again whenever Android switches blur on or off.
                val listener = Consumer<Boolean> { enabled -> blurred.value = enabled }
                windowManager.addCrossWindowBlurEnabledListener(listener)
                blurListener = listener
            } catch (_: Throwable) {
                // No blur: the panel is drawn all but opaque.
            }
        }

        setContent {
            val settings by Settings.state.collectAsState()
            AppTheme(settings) {
                PanelBackground(window, blurred.value)
                Popup(settings, onClose = { close() }, openApp = { open(it) })
            }
        }
        val height = panelHeight()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)

        // The panel starts below the edge of the screen and rises into place.
        decor.translationY = height.toFloat()
        decor.post { moveTo(0f, OPEN_MS, null) }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { listener -> runCatching { windowManager.removeCrossWindowBlurEnabledListener(listener) } }
        }
        moving?.cancel()
        velocity?.recycle()
        super.onDestroy()
    }

    /** Half the height of the screen. */
    private fun panelHeight(): Int {
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            resources.displayMetrics.heightPixels
        }
        return screen / 2
    }

    /** The panel moves by itself; Android's own animation of the window would move it twice. */
    private fun withoutWindowAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Lets the panel sink below the edge of the screen, then ends the activity. */
    private fun close() {
        if (closing) return
        closing = true
        dragging = false
        dragFrom = null
        moveTo(decor.height.toFloat(), CLOSE_MS) { finish() }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Opens the application on the list of devices, or on the page of one. */
    private fun open(deviceId: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DEVICE, deviceId)
        runCatching { startActivity(intent) }
        closing = true
        finish()
    }

    /** Moves the panel to [target] pixels below its place, then does [then], if given. */
    private fun moveTo(target: Float, millis: Long, then: (() -> Unit)?) {
        moving?.cancel()
        val animator = ValueAnimator.ofFloat(offset, target)
        animator.duration = millis
        animator.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        animator.addUpdateListener { decor.translationY = it.animatedValue as Float }
        if (then != null) {
            animator.addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) then()
                    }
                },
            )
        }
        moving = animator
        animator.start()
    }

    /**
     * A tap outside the panel closes it: the window takes every touch on the screen, and
     * those above it arrive with coordinates above its top. A pull that starts on the top
     * edge of the panel moves it; the finger is followed in screen coordinates.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (closing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val outside = event.y < offset || event.y > decor.height || event.x < 0 || event.x > decor.width
                if (outside) {
                    close()
                    return true
                }
                moving?.cancel()
                dragging = false
                dragFrom = if (event.y - offset <= dp(PULL_EDGE)) event.rawY - offset else null
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                track(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val from = dragFrom
                if (from != null) {
                    track(event)
                    val pull = (event.rawY - from).coerceAtLeast(0f)
                    if (!dragging && pull - offset > ViewConfiguration.get(this).scaledTouchSlop) {
                        dragging = true
                        // The controls under the finger let go of the touch.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        decor.translationY = pull
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                dragFrom = null
                if (wasDragging) {
                    track(event)
                    velocity?.computeCurrentVelocity(1000)
                    val speed = velocity?.yVelocity ?: 0f
                    val away = event.actionMasked == MotionEvent.ACTION_UP &&
                        (offset > dp(PULL_TO_CLOSE) || speed > dp(FLING_TO_CLOSE))
                    if (away) close() else moveTo(0f, SETTLE_MS, null)
                    return true
                }
                // A touch that stopped the panel on its way: let it finish the way into place.
                if (offset > 0f) moveTo(0f, SETTLE_MS, null)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Feeds the velocity tracker with screen coordinates. */
    private fun track(event: MotionEvent) {
        val screen = MotionEvent.obtain(event)
        screen.setLocation(event.rawX, event.rawY)
        velocity?.addMovement(screen)
        screen.recycle()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val BLUR_RADIUS = 48
        const val OPEN_MS = 300L
        const val CLOSE_MS = 220L
        const val SETTLE_MS = 200L

        /** How far down from the top of the panel a pull may start, in dp. */
        const val PULL_EDGE = 72f

        /** A pull further than this, or a fling faster than this (dp a second), closes the panel. */
        const val PULL_TO_CLOSE = 96f
        const val FLING_TO_CLOSE = 1000f
    }
}

/**
 * The background of the window, and with it the shape Android blurs behind: rounded at the
 * top in the design's radius, with the design's edge, and see-through where Android blurs.
 */
@Composable
private fun PanelBackground(window: Window, blurred: Boolean) {
    val design = LocalDesign.current
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerLow.copy(alpha = if (blurred) 0.78f else 0.97f).toArgb()
    val edge = if (design == DesignStyle.MATERIAL) null else scheme.outlineVariant.toArgb()
    val corner = with(density) { cornerOf(design).toPx() }
    val stroke = with(density) { 1.dp.roundToPx() }.coerceAtLeast(1)
    SideEffect {
        val background = GradientDrawable()
        background.cornerRadii = floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
        background.setColor(fill)
        if (edge != null) background.setStroke(stroke, edge)
        window.setBackgroundDrawable(background)
    }
}

private fun cornerOf(design: DesignStyle): Dp = when (design) {
    DesignStyle.MATERIAL -> 28.dp
    DesignStyle.FLUENT -> 8.dp
    DesignStyle.HOLO -> 2.dp
    DesignStyle.ECLAIR -> 0.dp
}

@Composable
private fun Popup(settings: SettingsData, onClose: () -> Unit, openApp: (String?) -> Unit) {
    val context = LocalContext.current
    val design = LocalDesign.current
    val devices by Devices.list.collectAsState()
    var pairing by rememberSaveable { mutableStateOf(false) }
    val ready = Devices.available && DeviceService.hasPermissions(context)
    val side = if (design.isHistorical) 8.dp else 20.dp

    BackHandler {
        if (pairing) pairing = false else onClose()
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            if (design == DesignStyle.MATERIAL || design == DesignStyle.FLUENT) {
                // The handle of a bottom sheet: the top of the panel can be pulled down.
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
