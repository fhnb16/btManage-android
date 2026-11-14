package ru.fhnb16.btmanage.ui

import android.content.Context
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Cell
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Role
import kotlin.math.pow
import kotlin.math.roundToInt

/** Wording shared by the screens, the widgets and the notifications. */
object DeviceText {
    fun name(context: Context, device: Device): String = when {
        device.name.isNotEmpty() -> device.name
        // An unpaired device has no name of its own; say what it is.
        device.model != null -> device.model
        device.origin == "fast_pair" -> context.getString(R.string.unknown_fast_pair)
        else -> context.getString(R.string.unknown_apple)
    }

    fun kind(context: Context, kind: String): String = context.getString(
        when (kind) {
            "earbuds", "headphones" -> R.string.kind_headphones
            "headset" -> R.string.kind_headset
            "speaker" -> R.string.kind_speaker
            "mouse" -> R.string.kind_mouse
            "keyboard" -> R.string.kind_keyboard
            "gamepad" -> R.string.kind_gamepad
            "remote" -> R.string.kind_remote
            "phone" -> R.string.kind_phone
            "tablet" -> R.string.kind_tablet
            "computer" -> R.string.kind_computer
            "watch" -> R.string.kind_watch
            "wearable" -> R.string.kind_wearable
            "car" -> R.string.kind_car
            else -> R.string.kind_other
        },
    )

    fun status(context: Context, device: Device): String = context.getString(
        when {
            device.connected -> R.string.status_connected
            device.nearby -> R.string.status_nearby
            else -> R.string.status_disconnected
        },
    )

    fun percent(context: Context, percent: Int): String = context.getString(R.string.percent_format, percent)

    fun roleLabel(context: Context, role: Role): String = context.getString(
        when (role) {
            Role.LEFT -> R.string.cell_left
            Role.RIGHT -> R.string.cell_right
            Role.CASE -> R.string.cell_case
            Role.MAIN -> R.string.cell_main
        },
    )

    /** "Left 90 %" for earbuds, the bare level for a device with one battery. */
    fun cellText(context: Context, device: Device, cell: Cell): String =
        if (device.hasEarbudCells) "${roleLabel(context, cell.role)} ${percent(context, cell.percent)}"
        else percent(context, cell.percent)

    /** "Left 90 %, Right 70 %, Case 50 %", or the single level of other devices. */
    fun levels(context: Context, device: Device): String =
        if (device.hasEarbudCells) device.cells.joinToString(", ") { cellText(context, device, it) }
        else device.cell(Role.MAIN)?.let { percent(context, it.percent) }.orEmpty()

    /** How long ago, or an empty string for a reading that is current. */
    fun age(context: Context, ageMs: Long): String = when {
        ageMs < 2 * 60_000 -> ""
        ageMs < 3_600_000 -> context.getString(R.string.age_minutes, ageMs / 60_000)
        ageMs < 48 * 3_600_000L -> context.getString(R.string.age_hours, ageMs / 3_600_000)
        else -> context.getString(R.string.age_days, ageMs / (24 * 3_600_000L))
    }

    /**
     * How far a device probably is, from the strength of its signal: "Closer than a metre",
     * "About 3 m", "Further than 10 m". Walls and bodies weaken a signal too, so this is a
     * rough guide, in whole metres.
     */
    fun distance(context: Context, rssi: Int): String {
        // The usual model of a signal fading with distance: −59 dBm at one metre, a path
        // loss exponent of 2, as in open space.
        val meters = 10.0.pow((MEASURED_POWER - rssi) / (10 * PATH_LOSS))
        return when {
            meters < 1.0 -> context.getString(R.string.distance_close)
            meters < 10.0 -> context.getString(R.string.distance_meters, meters.roundToInt())
            else -> context.getString(R.string.distance_far)
        }
    }

    private const val MEASURED_POWER = -59.0
    private const val PATH_LOSS = 2.0

    /** "About 3 h 20 min left". Past a few hours the minutes are false precision. */
    fun estimate(context: Context, minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60 / 5 * 5
        return when {
            hours == 0 -> context.getString(R.string.estimate_minutes, maxOf(minutes, 1))
            hours >= 5 || rest == 0 -> context.getString(R.string.estimate_hours, hours)
            else -> context.getString(R.string.estimate_hours_minutes, hours, rest)
        }
    }
}
