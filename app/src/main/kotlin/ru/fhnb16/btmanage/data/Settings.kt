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
    /**
     * The look of the application: "material" for Material You, "fluent" for the Windows
     * look, "holo" for Android 4.3, "eclair" for Android 2.1.
     */
    val design: String = "material",
    val theme: ThemeChoice = "system",
    val accent: AccentChoice = "system",
    /** Start watching the devices when the phone starts. */
    val startOnBoot: Boolean = true,
    val checkUpdates: Boolean = true,
    /** Download and install a newer release without being asked. */
    val autoUpdate: Boolean = true,
    /** The user has been shown Android's screen for letting the application install packages. */
    val installAsked: Boolean = false,
    /** Use root, through `su`, for what Android allows only system applications to do. */
    val useRoot: Boolean = false,
    /** Devices the user wants no notifications about. */
    val muted: Set<String> = emptySet(),
    /**
     * List earbuds that are not paired with this phone but broadcast their levels nearby.
     * Off by default: anybody's earbuds broadcast, and nothing tells whose they are.
     */
    val showUnpaired: Boolean = false,
    /** Devices the user took off the list. */
    val hidden: Set<String> = emptySet(),
    /** Write a detailed log of what the application sees and does to a file. */
    val verboseLog: Boolean = false,
    /** Show the tools for looking into devices: the console, raw commands. */
    val developer: Boolean = false,
    val widget: WidgetConfig = WidgetConfig(),
) {
    /** Whether a device belongs in the lists, the widget and the notifications. */
    fun shows(device: Device): Boolean = device.id !in hidden && (device.paired || showUnpaired)
}

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
            design = prefs.getString("design", defaults.design)!!,
            theme = prefs.getString("theme", defaults.theme)!!,
            accent = prefs.getString("accent", defaults.accent)!!,
            startOnBoot = prefs.getBoolean("startOnBoot", defaults.startOnBoot),
            checkUpdates = prefs.getBoolean("checkUpdates", defaults.checkUpdates),
            autoUpdate = prefs.getBoolean("autoUpdate", defaults.autoUpdate),
            installAsked = prefs.getBoolean("installAsked", defaults.installAsked),
            useRoot = prefs.getBoolean("useRoot", defaults.useRoot),
            muted = prefs.getStringSet("muted", emptySet())!!.toSet(),
            showUnpaired = prefs.getBoolean("showUnpaired", defaults.showUnpaired),
            hidden = prefs.getStringSet("hidden", emptySet())!!.toSet(),
            verboseLog = prefs.getBoolean("verboseLog", defaults.verboseLog),
            developer = prefs.getBoolean("developer", defaults.developer),
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
            putString("design", next.design)
            putString("theme", next.theme)
            putString("accent", next.accent)
            putBoolean("startOnBoot", next.startOnBoot)
            putBoolean("checkUpdates", next.checkUpdates)
            putBoolean("autoUpdate", next.autoUpdate)
            putBoolean("installAsked", next.installAsked)
            putBoolean("useRoot", next.useRoot)
            putStringSet("muted", next.muted)
            putBoolean("showUnpaired", next.showUnpaired)
            putStringSet("hidden", next.hidden)
            putBoolean("verboseLog", next.verboseLog)
            putBoolean("developer", next.developer)
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
