package ru.fhnb16.btmanage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.SettingsData
import ru.fhnb16.btmanage.data.UpdateInfo
import ru.fhnb16.btmanage.data.Updates
import ru.fhnb16.btmanage.service.DeviceService
import ru.fhnb16.btmanage.ui.DeviceDetail
import ru.fhnb16.btmanage.ui.DeviceText
import ru.fhnb16.btmanage.ui.DevicesScreen
import ru.fhnb16.btmanage.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    /** The device a notification or a widget asked for. */
    private var requested by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Settings.load(this)
        Devices.open(this)
        DeviceService.start(this)
        requested = intent.getStringExtra(EXTRA_DEVICE)
        setContent {
            App(requested) { requested = it }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requested = intent.getStringExtra(EXTRA_DEVICE)
    }

    companion object {
        const val EXTRA_DEVICE = "device"
    }
}

/** Material 3 in the theme and the accent the user chose. */
@Composable
fun AppTheme(settings: SettingsData, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        settings.accent == "bluetooth" -> if (dark) BluetoothDark else BluetoothLight
        // The colours Android derives from the wallpaper, from Android 12 on.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private val BluetoothLight = lightColorScheme(
    primary = Color(0xFF0067D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FF),
    onPrimaryContainer = Color(0xFF001C3B),
)

private val BluetoothDark = darkColorScheme(
    primary = Color(0xFF8CC4FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E9FF),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(requested: String?, select: (String?) -> Unit) {
    val context = LocalContext.current
    val settings by Settings.state.collectAsState()
    val devices by Devices.list.collectAsState()
    var granted by remember { mutableStateOf(DeviceService.hasPermissions(context)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = DeviceService.hasPermissions(context)
        if (granted) DeviceService.start(context)
    }

    LaunchedEffect(settings.checkUpdates) {
        if (settings.checkUpdates) update = Updates.check()
    }

    val selected = requested?.let { id -> devices.firstOrNull { it.id == id } }
    BackHandler(enabled = selected != null) { select(null) }

    AppTheme(settings) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(selected?.let { DeviceText.name(context, it) } ?: stringResource(R.string.app_name))
                        },
                        navigationIcon = {
                            if (selected != null) {
                                TextButton(onClick = { select(null) }) { Text(stringResource(R.string.back)) }
                            }
                        },
                    )
                    if (selected == null && granted && Devices.available) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_devices)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_settings)) })
                        }
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when {
                !Devices.available -> Message(stringResource(R.string.core_missing), null, modifier)
                !granted -> Message(
                    title = stringResource(R.string.permission_title),
                    hint = stringResource(R.string.permission_hint),
                    modifier = modifier,
                    action = stringResource(R.string.permission_grant),
                ) {
                    val wanted = DeviceService.requiredPermissions().toMutableList()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
                    permissions.launch(wanted.toTypedArray())
                }
                selected != null -> DeviceDetail(selected, settings, modifier)
                tab == 0 -> DevicesScreen(devices, settings, update, modifier) { select(it) }
                else -> SettingsScreen(devices, settings, update, modifier) { update = it }
            }
        }
    }
}

/** A title, an explanation and at most one thing to do. */
@Composable
private fun Message(
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
        }
    }
}
