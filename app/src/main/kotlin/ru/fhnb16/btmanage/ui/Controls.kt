package ru.fhnb16.btmanage.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fhnb16.btmanage.R

/*
 * The controls of the application, one set per design.
 *
 * Material You uses the Material 3 components as they are. The other three designs draw
 * their own: Fluent after WinUI (toggle switch, combo box, pivot), Holo after Android 4.3
 * (the ON/OFF switch, the spinner with its corner triangle, tabs with a blue line, lists
 * with dividers under headers in capitals), Eclair after Android 2.1 (grey gradient
 * buttons and tabs, the green tick, the title bar, the orange highlight).
 *
 * Drawn from memory of those systems and never yet seen on a screen.
 */

// Android 2.1 drew its controls the same in the dark and the light theme.
private val EclairFace = Brush.verticalGradient(listOf(Color(0xFFFAFAFA), Color(0xFFC4C4C4)))
private val EclairEdge = Color(0xFF7A7A7A)
private val EclairGreen = Color(0xFF3A9B00)
private val EclairTitle = Brush.verticalGradient(listOf(Color(0xFF8C8C8C), Color(0xFF4A4A4A)))
private val EclairTab = Brush.verticalGradient(listOf(Color(0xFF6E6E6E), Color(0xFF3C3C3C)))
private val EclairTabChosen = Brush.verticalGradient(listOf(Color(0xFFF0F0F0), Color(0xFFBEBEBE)))

// --- Press feedback and lines -------------------------------------------------------------

/**
 * A click with the feedback of the design: the ripple in Material and Fluent, a wash of the
 * highlight colour while the finger is down in the historical ones.
 */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val design = LocalDesign.current
    if (!design.isHistorical) return focusRing().clickable(enabled = enabled, onClick = onClick)

    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val wash: Brush = if (design == DesignStyle.HOLO) {
        SolidColor(accent.copy(alpha = 0.4f))
    } else {
        Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.4f), accent))
    }
    val base = if (pressed) background(wash) else this
    return base.focusRing().clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * Marks the element that has the focus of a remote control or a keyboard: a wash and an
 * outline in the accent. Goes right before the modifier that makes the element focusable
 * (`clickable` and the like). Compose gives an element the focus only while keys are in
 * use, so a finger on a touch screen never brings the mark up.
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(4.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val mark = if (focused) {
        Modifier
            .background(accent.copy(alpha = 0.12f), shape)
            .border(2.dp, accent, shape)
    } else {
        Modifier
    }
    return onFocusChanged { focused = it.isFocused }.then(mark)
}

/** A divider along the bottom edge, as lists had in Android 2.1 and 4.3. */
@Composable
fun Modifier.hairline(): Modifier {
    val line = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val thickness = 1.dp.toPx()
        drawRect(line, Offset(0f, size.height - thickness), Size(size.width, thickness))
    }
}

/**
 * One entry of a [Section]. In the historical designs an entry is a list row: full width,
 * its own padding, a divider under it. In Material and Fluent the card around the section
 * gives the padding and the spacing.
 */
@Composable
fun Modifier.entry(): Modifier =
    if (LocalDesign.current.isHistorical) {
        fillMaxWidth().hairline().padding(horizontal = 8.dp, vertical = 12.dp)
    } else {
        fillMaxWidth()
    }

/** The padding of a scrolling page. Lists in the historical designs run edge to edge. */
@Composable
fun Modifier.page(): Modifier = when (LocalDesign.current) {
    DesignStyle.HOLO -> padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
    DesignStyle.ECLAIR -> padding(bottom = 16.dp)
    else -> padding(16.dp)
}

// --- Marks: switch, tick, radio -----------------------------------------------------------

private fun DrawScope.tick(color: Color, width: Float) {
    val path = Path().apply {
        moveTo(size.width * 0.22f, size.height * 0.52f)
        lineTo(size.width * 0.42f, size.height * 0.72f)
        lineTo(size.width * 0.78f, size.height * 0.30f)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.outline(color: Color, corner: Float, width: Float, inset: Float = 0f) {
    val edge = inset + width / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(edge, edge),
        size = Size(size.width - 2 * edge, size.height - 2 * edge),
        cornerRadius = CornerRadius(corner),
        style = Stroke(width),
    )
}

/** The state of a switch, without behaviour: the row it sits in takes the click. */
@Composable
fun SwitchMark(checked: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (LocalDesign.current) {
        DesignStyle.MATERIAL -> Switch(checked = checked, onCheckedChange = null, modifier = modifier)

        // The toggle switch of Windows 11: a pill, outlined when off and filled when on.
        DesignStyle.FLUENT -> {
            val position by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
            Canvas(modifier.size(width = 40.dp, height = 20.dp)) {
                val radius = size.height / 2
                if (checked) {
                    drawRoundRect(scheme.primary, cornerRadius = CornerRadius(radius))
                } else {
                    outline(scheme.outline, radius, 1.dp.toPx())
                }
                drawCircle(
                    color = if (checked) scheme.onPrimary else scheme.onSurfaceVariant,
                    radius = 6.dp.toPx(),
                    center = Offset(radius + (size.width - 2 * radius) * position, radius),
                )
            }
        }

        // The Holo switch: a dark track and a thumb that says which way it is.
        DesignStyle.HOLO -> Box(
            modifier
                .size(width = 88.dp, height = 26.dp)
                .background(scheme.surfaceContainerHighest, RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(width = 44.dp, height = 26.dp)
                    .background(if (checked) scheme.primary else scheme.outline, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (checked) R.string.switch_on else R.string.switch_off),
                    color = if (checked) scheme.onPrimary else scheme.surface,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }

        // Android 2.1 had no switch: a setting was a tick box.
        DesignStyle.ECLAIR -> CheckMark(checked, modifier)
    }
}

@Composable
fun CheckMark(checked: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (LocalDesign.current) {
        DesignStyle.MATERIAL -> Checkbox(checked = checked, onCheckedChange = null, modifier = modifier)

        DesignStyle.FLUENT -> Canvas(modifier.size(20.dp)) {
            val corner = 4.dp.toPx()
            if (checked) {
                drawRoundRect(scheme.primary, cornerRadius = CornerRadius(corner))
                tick(scheme.onPrimary, 1.5.dp.toPx())
            } else {
                outline(scheme.outline, corner, 1.dp.toPx())
            }
        }

        // A thin grey box; the tick is in the Holo blue and larger than the box.
        DesignStyle.HOLO -> Canvas(modifier.size(24.dp)) {
            outline(scheme.outline, 0f, 1.5.dp.toPx(), inset = 3.dp.toPx())
            if (checked) tick(scheme.primary, 2.5.dp.toPx())
        }

        DesignStyle.ECLAIR -> Canvas(modifier.size(24.dp)) {
            val corner = 2.dp.toPx()
            drawRoundRect(EclairFace, cornerRadius = CornerRadius(corner))
            outline(EclairEdge, corner, 1.dp.toPx())
            if (checked) tick(EclairGreen, 3.dp.toPx())
        }
    }
}

@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (LocalDesign.current) {
        DesignStyle.MATERIAL -> RadioButton(selected = selected, onClick = null, modifier = modifier)

        DesignStyle.FLUENT -> Canvas(modifier.size(20.dp)) {
            val radius = size.minDimension / 2
            if (selected) {
                drawCircle(scheme.primary, radius)
                drawCircle(scheme.onPrimary, 4.dp.toPx())
            } else {
                drawCircle(scheme.outline, radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
        }

        DesignStyle.HOLO -> Canvas(modifier.size(24.dp)) {
            drawCircle(scheme.outline, 9.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            if (selected) drawCircle(scheme.primary, 5.dp.toPx())
        }

        DesignStyle.ECLAIR -> Canvas(modifier.size(24.dp)) {
            val radius = size.minDimension / 2
            drawCircle(EclairFace, radius)
            drawCircle(EclairEdge, radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            if (selected) drawCircle(EclairGreen, 5.dp.toPx())
        }
    }
}

// --- Buttons ------------------------------------------------------------------------------

/**
 * A button. [primary] is the main action of its place; the other kind is quieter: a text
 * button in Material, a neutral button in Fluent, a borderless one in Holo.
 */
@Composable
fun AppButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    when (LocalDesign.current) {
        DesignStyle.MATERIAL -> {
            val focus = modifier.focusRing(RoundedCornerShape(percent = 50))
            if (primary) {
                Button(onClick = onClick, modifier = focus, enabled = enabled) { Text(text, maxLines = 1) }
            } else {
                TextButton(onClick = onClick, modifier = focus, enabled = enabled) { Text(text, maxLines = 1) }
            }
        }

        DesignStyle.FLUENT -> DrawnButton(
            text = text,
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            fill = SolidColor(if (primary) scheme.primary else scheme.surfaceContainerLow),
            pressedFill = null,
            edge = if (primary) null else scheme.outlineVariant,
            content = if (primary) scheme.onPrimary else scheme.onSurface,
            shape = RoundedCornerShape(4.dp),
            minHeight = 32.dp,
        )

        DesignStyle.HOLO -> DrawnButton(
            text = text,
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            fill = if (primary) SolidColor(scheme.surfaceContainerHighest) else null,
            pressedFill = SolidColor(scheme.secondaryContainer),
            edge = null,
            content = scheme.onSurface,
            shape = RoundedCornerShape(2.dp),
            minHeight = 40.dp,
        )

        DesignStyle.ECLAIR -> DrawnButton(
            text = text,
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            fill = EclairFace,
            pressedFill = Brush.verticalGradient(listOf(lerp(scheme.primary, Color.White, 0.4f), scheme.primary)),
            edge = EclairEdge,
            content = Color.Black,
            shape = RoundedCornerShape(3.dp),
            minHeight = 40.dp,
        )
    }
}

@Composable
private fun DrawnButton(
    text: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    fill: Brush?,
    pressedFill: Brush?,
    edge: Color?,
    content: Color,
    shape: Shape,
    minHeight: Dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background = if (pressed && pressedFill != null) pressedFill else fill
    // Where the fill does not change under the finger, the ripple shows the press.
    val indication = if (pressedFill == null) LocalIndication.current else null

    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .then(if (edge != null) Modifier.border(1.dp, edge, shape) else Modifier)
            .focusRing(shape)
            .clickable(interactionSource = source, indication = indication, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minWidth = 64.dp, minHeight = minHeight)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

// --- Top bar and tabs ---------------------------------------------------------------------

@Composable
private fun BackArrow(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val path = Path().apply {
            moveTo(20 * unit, 12 * unit)
            lineTo(4 * unit, 12 * unit)
            moveTo(11 * unit, 5 * unit)
            lineTo(4 * unit, 12 * unit)
            lineTo(11 * unit, 19 * unit)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The bar at the top: Material's app bar, the action bar of Holo with its "up" caret, the
 * narrow grey title bar of Android 2.1. That last one has no way back on it; Android 2.1
 * left that to the Back key, and the system's back gesture does the same today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    when (LocalDesign.current) {
        DesignStyle.MATERIAL, DesignStyle.FLUENT -> TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                        BackArrow(LocalContentColor.current)
                    }
                }
            },
        )

        DesignStyle.HOLO -> Column(
            Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHigh)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .then(if (onBack != null) Modifier.pressable(onClick = onBack) else Modifier)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Canvas(Modifier.size(width = 10.dp, height = 20.dp)) {
                        val path = Path().apply {
                            moveTo(size.width * 0.8f, size.height * 0.15f)
                            lineTo(size.width * 0.2f, size.height * 0.5f)
                            lineTo(size.width * 0.8f, size.height * 0.85f)
                        }
                        drawPath(path, scheme.onSurfaceVariant, style = Stroke(2.dp.toPx()))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1)
            }
            // With tabs below, their line closes the bar; without, the bar has its own.
            if (onBack != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(scheme.primary),
                )
            }
        }

        DesignStyle.ECLAIR -> Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .statusBarsPadding(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(EclairTitle)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** A row of tabs. [scrollable] keeps the labels at their own width and lets the row scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTabs(titles: List<String>, selected: Int, scrollable: Boolean = false, onSelect: (Int) -> Unit) {
    if (LocalDesign.current != DesignStyle.MATERIAL) {
        DrawnTabs(titles, selected, scrollable, onSelect)
    } else if (scrollable) {
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 16.dp) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.focusRing(),
                    text = { Text(title, maxLines = 1) },
                )
            }
        }
    } else {
        PrimaryTabRow(selectedTabIndex = selected) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.focusRing(),
                    text = { Text(title, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun DrawnTabs(titles: List<String>, selected: Int, scrollable: Boolean, onSelect: (Int) -> Unit) {
    val design = LocalDesign.current
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary

    // Holo ran a thin line in its blue under the whole row.
    val bar = if (design == DesignStyle.HOLO) {
        Modifier
            .background(scheme.surfaceContainerHigh)
            .drawBehind {
                val line = 2.dp.toPx()
                drawRect(accent, Offset(0f, size.height - line), Size(size.width, line))
            }
    } else {
        Modifier
    }

    Row(
        Modifier
            .fillMaxWidth()
            .then(bar)
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    ) {
        titles.forEachIndexed { index, title ->
            val chosen = index == selected
            val face = if (design != DesignStyle.ECLAIR) {
                Modifier
            } else {
                Modifier.background(if (chosen) EclairTabChosen else EclairTab)
            }
            Box(
                (if (scrollable) Modifier else Modifier.weight(1f))
                    .height(if (design == DesignStyle.FLUENT) 40.dp else 48.dp)
                    .then(face)
                    .pressable { onSelect(index) }
                    .drawBehind {
                        if (design == DesignStyle.FLUENT && chosen) {
                            // The pivot of Windows: a short rounded bar under the chosen item.
                            val width = 16.dp.toPx()
                            val height = 3.dp.toPx()
                            drawRoundRect(
                                color = accent,
                                topLeft = Offset((size.width - width) / 2, size.height - height),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(height / 2),
                            )
                        } else if (design == DesignStyle.HOLO && chosen) {
                            val height = 5.dp.toPx()
                            drawRect(accent, Offset(0f, size.height - height), Size(size.width, height))
                        } else if (design == DesignStyle.ECLAIR) {
                            val line = 1.dp.toPx()
                            drawRect(Color(0xFF2A2A2A), Offset(size.width - line, 0f), Size(line, size.height))
                        }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.caps(),
                    maxLines = 1,
                    style = when (design) {
                        DesignStyle.FLUENT -> MaterialTheme.typography.bodyMedium
                        DesignStyle.HOLO -> MaterialTheme.typography.labelMedium
                        else -> MaterialTheme.typography.labelLarge
                    },
                    fontWeight = if (design == DesignStyle.FLUENT && chosen) FontWeight.SemiBold else null,
                    color = when {
                        design == DesignStyle.ECLAIR -> if (chosen) Color.Black else Color.White
                        chosen || design == DesignStyle.HOLO -> scheme.onSurface
                        else -> scheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// --- Sections and their entries -----------------------------------------------------------

/** The heading of a group: a quiet label, capitals over a rule in Holo, a grey band in Android 2.1. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (LocalDesign.current) {
        DesignStyle.HOLO -> Column(modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(scheme.outlineVariant),
            )
        }

        DesignStyle.ECLAIR -> Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            modifier = modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHigh)))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )

        else -> Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurfaceVariant,
            modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        )
    }
}

/**
 * A titled group. Its children are entries: give each `Modifier.entry()`, or wrap content
 * that is not a row of its own in [Entry].
 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title)
        if (LocalDesign.current.isHistorical) {
            Column(Modifier.fillMaxWidth(), content = content)
        } else {
            Panel(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
fun Entry(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.entry(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** An explanation in small print. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A setting that is on or off. The whole row takes the tap. */
@Composable
fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .pressable { onChange(!checked) }
            .entry(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 16.dp))
        SwitchMark(checked)
    }
}

/**
 * One of a list of options with a tick box or a radio mark. Material and Fluent put the
 * mark first; Android 2.1 and 4.3 put it at the far edge of the row.
 */
@Composable
fun OptionRow(title: String, note: String?, selected: Boolean, radio: Boolean, onClick: () -> Unit) {
    val atEnd = LocalDesign.current.isHistorical
    Row(
        Modifier
            .pressable(onClick = onClick)
            .entry(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!atEnd) {
            if (radio) RadioMark(selected) else CheckMark(selected)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title)
            if (note != null) Note(note)
        }
        if (atEnd) {
            Spacer(Modifier.width(12.dp))
            if (radio) RadioMark(selected) else CheckMark(selected)
        }
    }
}

/**
 * A choice of one among a few. Material shows all options as a segmented row. The other
 * designs show the chosen one and open a list: the combo box of Windows, the spinner of
 * Android 4.3 and 2.1.
 */
@Composable
fun <T> SettingChoice(label: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    if (LocalDesign.current == DesignStyle.MATERIAL) {
        Column(Modifier.entry(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, text) ->
                    val shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    SegmentedButton(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        modifier = Modifier.focusRing(shape),
                        shape = shape,
                        // No check mark: it would shift the label of the chosen option.
                        icon = {},
                    ) {
                        Text(text, maxLines = 1)
                    }
                }
            }
        }
    } else {
        Row(Modifier.entry(), verticalAlignment = Alignment.CenterVertically) {
            if (label != null) Text(label, Modifier.weight(1f).padding(end = 16.dp))
            Spinner(options, selected, onSelect, if (label != null) Modifier.width(176.dp) else Modifier.weight(1f))
        }
    }
}

@Composable
private fun <T> Spinner(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box(modifier) {
        SpinnerFace(chosen) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, text) in options) {
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun SpinnerFace(text: String, onClick: () -> Unit) {
    val design = LocalDesign.current
    val scheme = MaterialTheme.colorScheme
    val mark = if (design == DesignStyle.ECLAIR) Color.Black else scheme.onSurfaceVariant
    val shape = RoundedCornerShape(if (design == DesignStyle.FLUENT) 4.dp else 2.dp)
    val face = when (design) {
        DesignStyle.FLUENT ->
            Modifier
                .clip(shape)
                .background(scheme.surfaceContainerLow)
                .border(1.dp, scheme.outlineVariant, shape)

        DesignStyle.ECLAIR ->
            Modifier
                .clip(shape)
                .background(EclairFace)
                .border(1.dp, EclairEdge, shape)

        // Holo: a line under the value that ends in a small triangle at the right.
        else -> Modifier.drawBehind {
            val line = 1.dp.toPx()
            val corner = 8.dp.toPx()
            drawRect(mark, Offset(0f, size.height - line), Size(size.width, line))
            val triangle = Path().apply {
                moveTo(size.width, size.height)
                lineTo(size.width - corner, size.height)
                lineTo(size.width, size.height - corner)
                close()
            }
            drawPath(triangle, mark)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .then(face)
            .focusRing(shape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = if (design == DesignStyle.FLUENT) 32.dp else 40.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = if (design == DesignStyle.ECLAIR) Color.Black else scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        if (design != DesignStyle.HOLO) {
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.size(width = 10.dp, height = 6.dp)) {
                val arrow = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width / 2, size.height)
                    lineTo(size.width, 0f)
                }
                if (design == DesignStyle.ECLAIR) {
                    arrow.close()
                    drawPath(arrow, mark)
                } else {
                    drawPath(arrow, mark, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
