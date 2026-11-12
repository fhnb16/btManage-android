package ru.fhnb16.btmanage.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import btmanage.core.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fhnb16.btmanage.BuildConfig
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Diagnostics
import ru.fhnb16.btmanage.data.Elevated
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.UpdateState
import ru.fhnb16.btmanage.data.Updates
import ru.fhnb16.btmanage.data.WidgetConfig
import ru.fhnb16.btmanage.data.release
import ru.fhnb16.btmanage.widget.BatteryWidget

/** The settings, one tab per subject: general, appearance, the widget, about. */
@Composable
fun SettingsScreen(
    devices: List<Device>,
    settings: SettingsData,
    update: UpdateState,
    onInstall: () -> Unit,
    askInstallAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.settings_tab_general),
        stringResource(R.string.settings_tab_appearance),
        stringResource(R.string.settings_tab_widget),
        stringResource(R.string.settings_tab_about),
    )

    Column(modifier) {
        // Scrollable, so the labels keep their full width on a narrow screen.
        AppTabs(tabs, tab, scrollable = true) { tab = it }

        // Keyed by the tab, so each page starts at its top.
        key(tab) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .page(),
                verticalArrangement = Arrangement.spacedBy(if (LocalDesign.current.isHistorical) 0.dp else 8.dp),
            ) {
                when (tab) {
                    0 -> GeneralPage(settings)
                    1 -> AppearancePage(settings)
                    2 -> WidgetPage(devices, settings)
                    else -> AboutPage(settings, update, onInstall, askInstallAccess)
                }
            }
        }
    }
}

@Composable
private fun GeneralPage(settings: SettingsData) {
    val context = LocalContext.current
    fun change(edit: (SettingsData) -> SettingsData) = Settings.update(context, edit)

    Section(stringResource(R.string.settings_notifications)) {
        SettingToggle(stringResource(R.string.setting_notify_arrival), settings.notifyArrival) { on ->
            change { it.copy(notifyArrival = on) }
        }
        SettingToggle(stringResource(R.string.setting_notify_low), settings.notifyLow) { on ->
            change { it.copy(notifyLow = on) }
        }
        SettingChoice(
            stringResource(R.string.setting_threshold),
            listOf(10, 20, 30).map { it to DeviceText.percent(context, it) },
            settings.lowThreshold,
        ) { value -> change { it.copy(lowThreshold = value) } }
    }

    Section(stringResource(R.string.settings_background)) {
        SettingToggle(stringResource(R.string.setting_start_on_boot), settings.startOnBoot) { on ->
            change { it.copy(startOnBoot = on) }
        }
    }

    AccessSection(settings)
}

/**
 * Shizuku and root: the two ways the application can borrow the rights Android keeps for
 * system applications, which connecting and disconnecting a device needs.
 */
@Composable
private fun AccessSection(settings: SettingsData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(Elevated.shizukuRunning()) }
    var granted by remember { mutableStateOf(Elevated.shizukuGranted()) }
    var rootRefused by remember { mutableStateOf(false) }

    // Shizuku may be started, stopped or allowed while this page is open.
    LaunchedEffect(Unit) {
        while (true) {
            running = Elevated.shizukuRunning()
            granted = Elevated.shizukuGranted()
            delay(2_000)
        }
    }

    Section(stringResource(R.string.settings_access)) {
        Note(stringResource(R.string.access_note), Modifier.entry())

        Row(Modifier.entry(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            ) {
                Text(stringResource(R.string.access_shizuku))
                Note(
                    stringResource(
                        when {
                            granted -> R.string.access_shizuku_allowed
                            running -> R.string.access_shizuku_running
                            else -> R.string.access_shizuku_absent
                        },
                    ),
                )
            }
            if (running && !granted) {
                AppButton(stringResource(R.string.access_allow)) {
                    scope.launch { granted = Elevated.askShizuku() }
                }
            }
        }

        SettingToggle(stringResource(R.string.access_root), settings.useRoot) { on ->
            if (on) {
                // Turning it on asks the superuser manager at once, so a refusal shows here
                // and not later, in the middle of connecting a device.
                scope.launch {
                    val works = withContext(Dispatchers.IO) { Elevated.rootWorks() }
                    rootRefused = !works
                    if (works) Settings.update(context) { it.copy(useRoot = true) }
                }
            } else {
                rootRefused = false
                Settings.update(context) { it.copy(useRoot = false) }
            }
        }
        if (rootRefused) Note(stringResource(R.string.access_root_refused), Modifier.entry())
    }
}

@Composable
private fun AppearancePage(settings: SettingsData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun change(edit: (SettingsData) -> SettingsData) = Settings.update(context, edit)

    Section(stringResource(R.string.setting_design)) {
        val designs = listOf(
            Triple("material", R.string.design_material, R.string.design_material_note),
            Triple("fluent", R.string.design_fluent, R.string.design_fluent_note),
            Triple("holo", R.string.design_holo, R.string.design_holo_note),
            Triple("eclair", R.string.design_eclair, R.string.design_eclair_note),
        )
        for ((value, title, note) in designs) {
            OptionRow(stringResource(title), stringResource(note), settings.design == value, radio = true) {
                change { it.copy(design = value) }
                // The widget follows the design of the application.
                scope.launch { runCatching { BatteryWidget().updateAll(context) } }
            }
        }
    }

    Section(stringResource(R.string.settings_colours)) {
        SettingChoice(stringResource(R.string.setting_theme), themeOptions().labelled(), settings.theme) { value ->
            change { it.copy(theme = value) }
        }
        SettingChoice(stringResource(R.string.setting_accent), accentOptions().labelled(), settings.accent) { value ->
            change { it.copy(accent = value) }
        }
    }
}

@Composable
private fun WidgetPage(devices: List<Device>, settings: SettingsData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = settings.widget

    // A change is applied to the widgets on the home screen at once.
    fun widget(edit: (WidgetConfig) -> WidgetConfig) {
        Settings.update(context) { it.copy(widget = edit(it.widget)) }
        scope.launch { runCatching { BatteryWidget().updateAll(context) } }
    }

    Section(stringResource(R.string.widget_group_content)) {
        SettingChoice(stringResource(R.string.widget_count), (1..4).map { it to it.toString() }, config.count) { value ->
            widget { it.copy(count = value) }
        }
        SettingChoice(
            stringResource(R.string.widget_layout),
            listOf("row" to R.string.layout_row, "column" to R.string.layout_column, "grid" to R.string.layout_grid).labelled(),
            config.layout,
        ) { value -> widget { it.copy(layout = value) } }
        SettingChoice(
            stringResource(R.string.widget_style),
            listOf("compact" to R.string.style_compact, "detailed" to R.string.style_detailed).labelled(),
            config.style,
        ) { value -> widget { it.copy(style = value) } }
    }

    Section(stringResource(R.string.widget_group_look)) {
        SettingChoice(
            stringResource(R.string.widget_background),
            listOf(
                "solid" to R.string.background_solid,
                "translucent" to R.string.background_translucent,
                "transparent" to R.string.background_transparent,
            ).labelled(),
            config.background,
        ) { value -> widget { it.copy(background = value) } }
        Note(stringResource(R.string.widget_blur_note), Modifier.entry())
        SettingChoice(stringResource(R.string.setting_theme), themeOptions().labelled(), config.theme) { value ->
            widget { it.copy(theme = value) }
        }
        SettingChoice(stringResource(R.string.setting_accent), accentOptions().labelled(), config.accent) { value ->
            widget { it.copy(accent = value) }
        }
    }

    Section(stringResource(R.string.widget_devices)) {
        Note(stringResource(R.string.widget_devices_hint), Modifier.entry())
        for (device in devices.filter { it.paired || it.id in config.devices }) {
            val checked = device.id in config.devices
            OptionRow(DeviceText.name(context, device), null, checked, radio = false) {
                widget { it.copy(devices = if (checked) it.devices - device.id else it.devices + device.id) }
            }
        }
    }
}

@Composable
private fun AboutPage(
    settings: SettingsData,
    update: UpdateState,
    onInstall: () -> Unit,
    askInstallAccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Section(stringResource(R.string.settings_updates)) {
        SettingToggle(stringResource(R.string.setting_auto_update), settings.checkUpdates) { on ->
            Settings.update(context) { it.copy(checkUpdates = on) }
        }
        SettingToggle(stringResource(R.string.setting_auto_install), settings.autoUpdate) { on ->
            Settings.update(context) { it.copy(autoUpdate = on) }
            // Installing on its own needs Android's leave to install packages: ask for it now.
            if (on && !Updates.canInstall(context)) askInstallAccess()
        }
        Note(stringResource(R.string.setting_auto_install_hint), Modifier.entry())

        if (update.release != null) {
            UpdateBanner(update, onInstall, Modifier.entry())
        } else {
            val checking = update is UpdateState.Checking
            Row(
                Modifier.entry(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppButton(
                    text = stringResource(if (checking) R.string.update_checking else R.string.update_check),
                    enabled = !checking,
                ) {
                    scope.launch { Updates.check() }
                }
                val status = when (update) {
                    is UpdateState.UpToDate -> R.string.update_none
                    is UpdateState.Failed -> R.string.update_check_failed
                    else -> null
                }
                if (status != null) Note(stringResource(status))
            }
        }
    }

    Section(stringResource(R.string.settings_about)) {
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, if (Native.available) Native.version() else "—"),
            Modifier.entry(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    DiagnosticsSection()
}

/**
 * What the service has seen, for a report that something does not work. The text is
 * technical and not translated: it is meant to be copied into the report as it is.
 */
@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(Diagnostics.report()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            report = Diagnostics.report()
        }
    }

    Section(stringResource(R.string.settings_diagnostics)) {
        Note(stringResource(R.string.diagnostics_note), Modifier.entry())
        Text(report, Modifier.entry(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Box(Modifier.entry()) {
            AppButton(stringResource(R.string.diagnostics_copy), primary = false) {
                runCatching {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("btManage", report))
                }
            }
        }
    }
}

private fun themeOptions() =
    listOf("system" to R.string.choice_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)

private fun accentOptions() =
    listOf("system" to R.string.choice_system, "bluetooth" to R.string.accent_bluetooth)

@Composable
private fun List<Pair<String, Int>>.labelled(): List<Pair<String, String>> = map { (value, label) -> value to stringResource(label) }
