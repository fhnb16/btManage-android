package ru.fhnb16.btmanage.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** "system", "light" or "dark". */
typealias ThemeChoice = String

/** "system" for the colours Android derives from the wallpaper, "bluetooth" for Bluetooth blue. */
typealias AccentChoice = String

/** What the home screen widget shows and how. */
data class WidgetConfig(
    /** How many devices, 1 to 4. */
    val count: Int = 2,
    /** "row": side by side. "column": one under another. "grid": two by two. */
    val layout: String = "row",
    /** "compact": name and level. "detailed": a level for each earbud and the case, with meters. */
    val style: String = "detailed",
    /** "solid", "translucent" or "transparent". Android gives widgets no way to blur what is behind them. */
    val background: String = "solid",
    val accent: AccentChoice = "system",
    val theme: ThemeChoice = "system",
    /** Devices to show, in this order; empty means the active devices, as they come. */
    val devices: Set<String> = emptySet(),
)

data class SettingsData(
    val notifyArrival: Boolean = true,
    val notifyLow: Boolean = true,
    /** At or below this level, and not charging, a battery counts as low. */
    val lowThreshold: Int = 20,
    val theme: ThemeChoice = "system",
    val accent: AccentChoice = "system",
    /** Start watching the devices when the phone starts. */
    val startOnBoot: Boolean = true,
    val checkUpdates: Boolean = true,
    /** Devices the user wants no notifications about. */
    val muted: Set<String> = emptySet(),
    val widget: WidgetConfig = WidgetConfig(),
)

/** User preferences, kept in SharedPreferences and observable as a flow. */
object Settings {
    private const val FILE = "settings"

    private val data = MutableStateFlow(SettingsData())
    val state: StateFlow<SettingsData> = data
    val current: SettingsData get() = data.value

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val defaults = SettingsData()
        val widget = defaults.widget
        data.value = SettingsData(
            notifyArrival = prefs.getBoolean("notifyArrival", defaults.notifyArrival),
            notifyLow = prefs.getBoolean("notifyLow", defaults.notifyLow),
            lowThreshold = prefs.getInt("lowThreshold", defaults.lowThreshold),
            theme = prefs.getString("theme", defaults.theme)!!,
            accent = prefs.getString("accent", defaults.accent)!!,
            startOnBoot = prefs.getBoolean("startOnBoot", defaults.startOnBoot),
            checkUpdates = prefs.getBoolean("checkUpdates", defaults.checkUpdates),
            muted = prefs.getStringSet("muted", emptySet())!!.toSet(),
            widget = WidgetConfig(
                count = prefs.getInt("widget.count", widget.count),
                layout = prefs.getString("widget.layout", widget.layout)!!,
                style = prefs.getString("widget.style", widget.style)!!,
                background = prefs.getString("widget.background", widget.background)!!,
                accent = prefs.getString("widget.accent", widget.accent)!!,
                theme = prefs.getString("widget.theme", widget.theme)!!,
                devices = prefs.getStringSet("widget.devices", emptySet())!!.toSet(),
            ),
        )
    }

    /** Changes the settings and writes them down. */
    fun update(context: Context, change: (SettingsData) -> SettingsData) {
        val next = change(data.value)
        data.value = next
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putBoolean("notifyArrival", next.notifyArrival)
            putBoolean("notifyLow", next.notifyLow)
            putInt("lowThreshold", next.lowThreshold)
            putString("theme", next.theme)
            putString("accent", next.accent)
            putBoolean("startOnBoot", next.startOnBoot)
            putBoolean("checkUpdates", next.checkUpdates)
            putStringSet("muted", next.muted)
            putInt("widget.count", next.widget.count)
            putString("widget.layout", next.widget.layout)
            putString("widget.style", next.widget.style)
            putString("widget.background", next.widget.background)
            putString("widget.accent", next.widget.accent)
            putString("widget.theme", next.widget.theme)
            putStringSet("widget.devices", next.widget.devices)
        }.apply()
    }
}
