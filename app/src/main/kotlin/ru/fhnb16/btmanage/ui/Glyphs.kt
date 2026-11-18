package ru.fhnb16.btmanage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Role

/**
 * Outline glyphs drawn in code, on a 32 by 32 grid with a 1.5 stroke, as the Windows and
 * Linux applications draw theirs: earbuds, the case, kinds of device, and a few marks.
 * The application carries no icon font.
 */
enum class Glyph(val path: String) {
    LEFT("M 20 11 L 20 24 A 3 3 0 0 1 14 24 L 14 17.93 A 7 7 0 1 1 20 11 Z"),
    RIGHT("M 12 11 L 12 24 A 3 3 0 0 0 18 24 L 18 17.93 A 7 7 0 1 0 12 11 Z"),
    CASE(
        "M 11 6 H 21 A 6 6 0 0 1 27 12 V 20 A 6 6 0 0 1 21 26 H 11 A 6 6 0 0 1 5 20 V 12 A 6 6 0 0 1 11 6 Z " +
            "M 5 13 H 27 M 15 18 A 1 1 0 1 0 17 18 A 1 1 0 1 0 15 18 Z",
    ),
    HEADPHONES(
        "M 7 18 V 15 A 9 9 0 0 1 25 15 V 18 M 6.5 18 H 7.5 A 2.5 2.5 0 0 1 10 20.5 V 24.5 A 2.5 2.5 0 0 1 7.5 27 " +
            "H 6.5 A 2.5 2.5 0 0 1 4 24.5 V 20.5 A 2.5 2.5 0 0 1 6.5 18 Z M 24.5 18 H 25.5 A 2.5 2.5 0 0 1 28 20.5 " +
            "V 24.5 A 2.5 2.5 0 0 1 25.5 27 H 24.5 A 2.5 2.5 0 0 1 22 24.5 V 20.5 A 2.5 2.5 0 0 1 24.5 18 Z",
    ),
    EARBUDS(
        "M 14 9 L 14 21 A 2.5 2.5 0 0 1 9 21 L 9 15.5 A 5.5 5.5 0 1 1 14 9 Z " +
            "M 18 9 L 18 21 A 2.5 2.5 0 0 0 23 21 L 23 15.5 A 5.5 5.5 0 1 0 18 9 Z",
    ),
    MOUSE("M 16 5 A 7 7 0 0 1 23 12 V 20 A 7 7 0 0 1 9 20 V 12 A 7 7 0 0 1 16 5 Z M 16 9 V 13"),
    KEYBOARD(
        "M 4 9 H 28 A 2 2 0 0 1 30 11 V 22 A 2 2 0 0 1 28 24 H 4 A 2 2 0 0 1 2 22 V 11 A 2 2 0 0 1 4 9 Z " +
            "M 7 14 H 9 M 12 14 H 14 M 17 14 H 19 M 22 14 H 24 M 10 19 H 22",
    ),
    GAMEPAD(
        "M 10 10 H 22 A 6 6 0 0 1 28 16 L 29 21 A 3 3 0 0 1 24 23.5 L 21.5 20 H 10.5 L 8 23.5 A 3 3 0 0 1 3 21 " +
            "L 4 16 A 6 6 0 0 1 10 10 Z M 9 14.5 V 18.5 M 7 16.5 H 11 M 22 15 V 15.5 M 24.5 17.5 V 18",
    ),
    PHONE("M 11 3 H 21 A 2 2 0 0 1 23 5 V 27 A 2 2 0 0 1 21 29 H 11 A 2 2 0 0 1 9 27 V 5 A 2 2 0 0 1 11 3 Z M 15 25 H 17"),
    WATCH(
        "M 12 8 H 20 A 3 3 0 0 1 23 11 V 21 A 3 3 0 0 1 20 24 H 12 A 3 3 0 0 1 9 21 V 11 A 3 3 0 0 1 12 8 Z " +
            "M 12 8 L 13 3 H 19 L 20 8 M 12 24 L 13 29 H 19 L 20 24",
    ),
    SPEAKER(
        "M 10 4 H 22 A 2 2 0 0 1 24 6 V 26 A 2 2 0 0 1 22 28 H 10 A 2 2 0 0 1 8 26 V 6 A 2 2 0 0 1 10 4 Z " +
            "M 21 19 A 5 5 0 1 1 11 19 A 5 5 0 1 1 21 19 Z M 16 9 V 9.5",
    ),
    BLUETOOTH("M 10 10 L 22 22 L 16 28 V 4 L 22 10 L 10 22"),
    BOLT("M 17 4 L 9 18 H 15.5 L 14.5 28 L 23 14 H 16.5 Z"),
    /** A gear of eight teeth around a hub: tooth tops on a radius of 12.5, the roots on 9.5. */
    GEAR(
        "M 25.22 13.70 L 28.35 14.05 A 12.5 12.5 0 0 1 28.35 17.96 L 25.22 18.30 " +
            "A 9.5 9.5 0 0 1 24.14 20.89 L 26.11 23.35 A 12.5 12.5 0 0 1 23.35 26.11 L 20.89 24.14 " +
            "A 9.5 9.5 0 0 1 18.30 25.22 L 17.96 28.35 A 12.5 12.5 0 0 1 14.05 28.35 L 13.70 25.22 " +
            "A 9.5 9.5 0 0 1 11.11 24.14 L 8.65 26.11 A 12.5 12.5 0 0 1 5.89 23.35 L 7.86 20.89 " +
            "A 9.5 9.5 0 0 1 6.78 18.30 L 3.65 17.96 A 12.5 12.5 0 0 1 3.65 14.05 L 6.78 13.70 " +
            "A 9.5 9.5 0 0 1 7.86 11.11 L 5.89 8.65 A 12.5 12.5 0 0 1 8.65 5.89 L 11.11 7.86 " +
            "A 9.5 9.5 0 0 1 13.70 6.78 L 14.05 3.65 A 12.5 12.5 0 0 1 17.96 3.65 L 18.30 6.78 " +
            "A 9.5 9.5 0 0 1 20.89 7.86 L 23.35 5.89 A 12.5 12.5 0 0 1 26.11 8.65 L 24.14 11.11 " +
            "A 9.5 9.5 0 0 1 25.22 13.70 Z " +
            "M 20 16 A 4 4 0 1 1 12 16 A 4 4 0 1 1 20 16 Z",
    ),
    COMPUTER("M 5 7 H 27 A 2 2 0 0 1 29 9 V 21 A 2 2 0 0 1 27 23 H 5 A 2 2 0 0 1 3 21 V 9 A 2 2 0 0 1 5 7 Z M 12 27 H 20 M 16 23 V 27"),
    PLUS("M 16 7 V 25 M 7 16 H 25"),
    SHARE("M 16 4 V 20 M 10 10 L 16 4 L 22 10 M 8 16 V 26 A 2 2 0 0 0 10 28 H 22 A 2 2 0 0 0 24 26 V 16"),
    GLOBE(
        "M 28 16 A 12 12 0 1 1 4 16 A 12 12 0 1 1 28 16 Z M 4 16 H 28 " +
            "M 16 4 A 6 12 0 0 1 16 28 A 6 12 0 0 1 16 4 Z",
    ),
    ;

    companion object {
        /** The glyph of a battery of earbuds. */
        fun of(role: Role): Glyph = when (role) {
            Role.LEFT -> LEFT
            Role.RIGHT -> RIGHT
            Role.CASE -> CASE
            Role.MAIN -> HEADPHONES
        }

        /** The glyph of a kind of device, from the core's name for it. */
        fun of(device: Device): Glyph {
            val kind = device.kind
            return when {
                device.hasEarbudCells || "earbud" in kind -> EARBUDS
                "headphone" in kind || "headset" in kind -> HEADPHONES
                "mouse" in kind -> MOUSE
                "keyboard" in kind -> KEYBOARD
                "gamepad" in kind || "controller" in kind -> GAMEPAD
                "phone" in kind -> PHONE
                "watch" in kind || "band" in kind || "wearable" in kind -> WATCH
                "speaker" in kind -> SPEAKER
                else -> BLUETOOTH
            }
        }
    }
}

/** Draws a [Glyph] at [size] in [color]. */
@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = LocalContentColor.current) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(modifier.size(size)) {
        scale(this.size.width / GRID, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private const val GRID = 32f
private const val STROKE = 1.5f
