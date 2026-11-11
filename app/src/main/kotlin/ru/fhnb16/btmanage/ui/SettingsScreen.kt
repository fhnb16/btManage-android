package ru.fhnb16.btmanage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import ru.fhnb16.btmanage.data.UpdateInfo
import ru.fhnb16.btmanage.data.Updates
import ru.fhnb16.btmanage.data.WidgetConfig
import ru.fhnb16.btmanage.widget.BatteryWidget

/** Notifications, appearance, the widget, start-up and updates. */
@Composable
fun SettingsScreen(
    devices: List<Device>,
    settings: SettingsData,
    update: UpdateInfo?,
    modifier: Modifier = Modifier,
    onUpdate: (UpdateInfo?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun change(edit: (SettingsData) -> SettingsData) = Settings.update(context, edit)

    // A change to the widget is applied to the widgets on the home screen at once.
    fun widget(edit: (WidgetConfig) -> WidgetConfig) {
        change { it.copy(widget = edit(it.widget)) }
        scope.launch { runCatching { BatteryWidget().updateAll(context) } }
    }

    val theme = listOf("system" to R.string.choice_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
    val accent = listOf("system" to R.string.choice_system, "bluetooth" to R.string.accent_bluetooth)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(R.string.settings_notifications)
        Toggle(R.string.setting_notify_arrival, settings.notifyArrival) { on -> change { it.copy(notifyArrival = on) } }
        Toggle(R.string.setting_notify_low, settings.notifyLow) { on -> change { it.copy(notifyLow = on) } }
        Choice(
            R.string.setting_threshold,
            listOf(10, 20, 30).map { it to DeviceText.percent(context, it) },
            settings.lowThreshold,
        ) { value -> change { it.copy(lowThreshold = value) } }

        Section(R.string.settings_appearance)
        Choice(R.string.setting_theme, theme.labelled(), settings.theme) { value -> change { it.copy(theme = value) } }
        Choice(R.string.setting_accent, accent.labelled(), settings.accent) { value -> change { it.copy(accent = value) } }

        Section(R.string.settings_widget)
        val config = settings.widget
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
        Choice(R.string.widget_theme, theme.labelled(), config.theme) { value -> widget { it.copy(theme = value) } }
        Choice(R.string.widget_accent, accent.labelled(), config.accent) { value -> widget { it.copy(accent = value) } }

        Text(stringResource(R.string.widget_devices), style = MaterialTheme.typography.bodyLarge)
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

        Section(R.string.settings_background)
        Toggle(R.string.setting_start_on_boot, settings.startOnBoot) { on -> change { it.copy(startOnBoot = on) } }

        Section(R.string.settings_updates)
        Toggle(R.string.setting_auto_update, settings.checkUpdates) { on -> change { it.copy(checkUpdates = on) } }
        var checking by remember { mutableStateOf(false) }
        var checked by remember { mutableStateOf(false) }
        if (update != null) {
            UpdateBanner(update)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            onUpdate(Updates.check())
                            checking = false
                            checked = true
                        }
                    },
                ) {
                    Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
                }
                if (checked && !checking) {
                    Text(
                        stringResource(R.string.update_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Section(R.string.settings_about)
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, if (Native.available) Native.version() else "—"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun List<Pair<String, Int>>.labelled(): List<Pair<String, String>> = map { (value, label) -> value to stringResource(label) }

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
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

/** A label over a row of mutually exclusive options. */
@Composable
private fun <T> Choice(label: Int, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // No check mark: it would shift the label of the chosen option.
                    icon = {},
                ) {
                    Text(text, maxLines = 1)
                }
            }
        }
    }
}
