package ru.fhnb16.btmanage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import btmanage.core.Native
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.BuildConfig
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.UpdateState
import ru.fhnb16.btmanage.data.Updates
import ru.fhnb16.btmanage.data.release
import ru.fhnb16.btmanage.data.WidgetConfig
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
        R.string.settings_tab_general,
        R.string.settings_tab_appearance,
        R.string.settings_tab_widget,
        R.string.settings_tab_about,
    )

    Column(modifier) {
        // Scrollable, so the labels keep their full width on a narrow screen.
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title).caps()) })
            }
        }

        // Keyed by the tab, so each page starts at its top.
        key(tab) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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

    Group(R.string.settings_notifications) {
        Toggle(R.string.setting_notify_arrival, settings.notifyArrival) { on -> change { it.copy(notifyArrival = on) } }
        Toggle(R.string.setting_notify_low, settings.notifyLow) { on -> change { it.copy(notifyLow = on) } }
        Choice(
            R.string.setting_threshold,
            listOf(10, 20, 30).map { it to DeviceText.percent(context, it) },
            settings.lowThreshold,
        ) { value -> change { it.copy(lowThreshold = value) } }
    }

    Group(R.string.settings_background) {
        Toggle(R.string.setting_start_on_boot, settings.startOnBoot) { on -> change { it.copy(startOnBoot = on) } }
    }
}

@Composable
private fun AppearancePage(settings: SettingsData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun change(edit: (SettingsData) -> SettingsData) = Settings.update(context, edit)

    Group(R.string.setting_design) {
        val designs = listOf(
            Triple("material", R.string.design_material, R.string.design_material_note),
            Triple("fluent", R.string.design_fluent, R.string.design_fluent_note),
            Triple("holo", R.string.design_holo, R.string.design_holo_note),
            Triple("eclair", R.string.design_eclair, R.string.design_eclair_note),
        )
        for ((value, title, note) in designs) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        change { it.copy(design = value) }
                        // The widget follows the design of the application.
                        scope.launch { runCatching { BatteryWidget().updateAll(context) } }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.design == value,
                    onClick = null,
                    modifier = Modifier.padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                )
                Column {
                    Text(stringResource(title))
                    Note(note)
                }
            }
        }
    }

    Group(R.string.settings_colours) {
        Choice(R.string.setting_theme, themeOptions().labelled(), settings.theme) { value -> change { it.copy(theme = value) } }
        Choice(R.string.setting_accent, accentOptions().labelled(), settings.accent) { value -> change { it.copy(accent = value) } }
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

    Group(R.string.widget_group_content) {
        Choice(R.string.widget_count, (1..4).map { it to it.toString() }, config.count) { value ->
            widget { it.copy(count = value) }
        }
        Choice(
            R.string.widget_layout,
            listOf("row" to R.string.layout_row, "column" to R.string.layout_column, "grid" to R.string.layout_grid).labelled(),
            config.layout,
        ) { value -> widget { it.copy(layout = value) } }
        Choice(
            R.string.widget_style,
            listOf("compact" to R.string.style_compact, "detailed" to R.string.style_detailed).labelled(),
            config.style,
        ) { value -> widget { it.copy(style = value) } }
    }

    Group(R.string.widget_group_look) {
        Choice(
            R.string.widget_background,
            listOf(
                "solid" to R.string.background_solid,
                "translucent" to R.string.background_translucent,
                "transparent" to R.string.background_transparent,
            ).labelled(),
            config.background,
        ) { value -> widget { it.copy(background = value) } }
        Note(R.string.widget_blur_note)
        Choice(R.string.setting_theme, themeOptions().labelled(), config.theme) { value -> widget { it.copy(theme = value) } }
        Choice(R.string.setting_accent, accentOptions().labelled(), config.accent) { value -> widget { it.copy(accent = value) } }
    }

    Group(R.string.widget_devices) {
        Note(R.string.widget_devices_hint)
        for (device in devices.filter { it.paired || it.id in config.devices }) {
            val checked = device.id in config.devices
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { widget { it.copy(devices = if (checked) it.devices - device.id else it.devices + device.id) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp, top = 8.dp, bottom = 8.dp))
                Text(DeviceText.name(context, device), maxLines = 1)
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

    Group(R.string.settings_updates) {
        Toggle(R.string.setting_auto_update, settings.checkUpdates) { on ->
            Settings.update(context) { it.copy(checkUpdates = on) }
        }
        Toggle(R.string.setting_auto_install, settings.autoUpdate) { on ->
            Settings.update(context) { it.copy(autoUpdate = on) }
            // Installing on its own needs Android's leave to install packages: ask for it now.
            if (on && !Updates.canInstall(context)) askInstallAccess()
        }
        Note(R.string.setting_auto_install_hint)

        if (update.release != null) {
            UpdateBanner(update, onInstall)
        } else {
            val checking = update is UpdateState.Checking
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    enabled = !checking,
                    shape = buttonShape(),
                    onClick = { scope.launch { Updates.check() } },
                ) {
                    Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
                }
                val status = when (update) {
                    is UpdateState.UpToDate -> R.string.update_none
                    is UpdateState.Failed -> R.string.update_check_failed
                    else -> null
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

    Group(R.string.settings_about) {
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, if (Native.available) Native.version() else "—"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun themeOptions() =
    listOf("system" to R.string.choice_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)

private fun accentOptions() =
    listOf("system" to R.string.choice_system, "bluetooth" to R.string.accent_bluetooth)

@Composable
private fun List<Pair<String, Int>>.labelled(): List<Pair<String, String>> = map { (value, label) -> value to stringResource(label) }

/** A titled group of settings on one panel. */
@Composable
private fun Group(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(title).caps(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    Panel(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Note(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A row of mutually exclusive options, under a label if there is one. */
@Composable
private fun <T> Choice(label: Int?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = segmentShape(index, options.size),
                    // No check mark: it would shift the label of the chosen option.
                    icon = {},
                ) {
                    Text(text, maxLines = 1)
                }
            }
        }
    }
}
