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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.fhnb16.btmanage.data.Devices
import kotlinx.coroutines.launch
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.Updates
import ru.fhnb16.btmanage.service.DeviceService
import ru.fhnb16.btmanage.ui.AppTheme
import ru.fhnb16.btmanage.ui.DeviceDetail
import ru.fhnb16.btmanage.ui.DeviceText
import ru.fhnb16.btmanage.ui.DevicesScreen
import ru.fhnb16.btmanage.ui.SettingsScreen
import ru.fhnb16.btmanage.ui.buttonShape
import ru.fhnb16.btmanage.ui.caps
import ru.fhnb16.btmanage.ui.topBarColors

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

    // While the application is on screen Android's installation dialog may be opened directly.
    override fun onStart() {
        super.onStart()
        Updates.foreground = true
    }

    override fun onStop() {
        Updates.foreground = false
        super.onStop()
    }

    companion object {
        const val EXTRA_DEVICE = "device"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(requested: String?, select: (String?) -> Unit) {
    val context = LocalContext.current
    val settings by Settings.state.collectAsState()
    val devices by Devices.list.collectAsState()
    var granted by remember { mutableStateOf(DeviceService.hasPermissions(context)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val update by Updates.state.collectAsState()
    val scope = rememberCoroutineScope()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = DeviceService.hasPermissions(context)
        if (granted) DeviceService.start(context)
    }

    // Android's screen where the user lets the application install packages. Coming back
    // from it with the right granted, the update that was waiting for it goes ahead.
    val installAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Updates.canInstall(context)) scope.launch { Updates.install(context) }
    }
    val askInstallAccess: () -> Unit = {
        Settings.update(context) { it.copy(installAsked = true) }
        runCatching { installAccess.launch(Updates.permissionIntent(context)) }
    }
    val startUpdate: () -> Unit = {
        if (Updates.canInstall(context)) scope.launch { Updates.install(context) } else askInstallAccess()
    }

    LaunchedEffect(settings.checkUpdates) {
        if (settings.checkUpdates && Updates.check()?.apkUrl != null && Settings.current.autoUpdate) {
            // Install on its own. Without the right to, ask Android for it once; after that
            // the update waits for the user in the banner.
            if (Updates.canInstall(context) || !Settings.current.installAsked) startUpdate()
        }
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
                        colors = topBarColors(),
                    )
                    if (selected == null && granted && Devices.available) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_devices).caps()) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_settings).caps()) })
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
                tab == 0 -> DevicesScreen(devices, settings, update, startUpdate, modifier) { select(it) }
                else -> SettingsScreen(devices, settings, update, startUpdate, askInstallAccess, modifier)
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
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp), shape = buttonShape()) { Text(action) }
        }
    }
}
