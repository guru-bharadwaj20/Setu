package `in`.setu.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import `in`.setu.app.R
import `in`.setu.app.SetuApp
import `in`.setu.app.mesh.MeshService
import `in`.setu.app.ui.screens.CourierScreen
import `in`.setu.app.ui.screens.PermissionsScreen
import `in`.setu.app.ui.screens.SosScreen
import `in`.setu.app.ui.screens.StatusScreen
import `in`.setu.app.ui.theme.SetuTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SetuTheme { SetuApp() }
        }
    }
}

private enum class Tab(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Sos("sos", R.string.nav_sos, Icons.Filled.Warning),
    Courier("courier", R.string.nav_courier, Icons.Filled.Share),
    Status("status", R.string.nav_status, Icons.Filled.Info),
}

@Composable
private fun SetuApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Permissions.essentialMissing(context).isEmpty()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Permissions.essentialMissing(context).isEmpty()
    }
    if (!granted) {
        PermissionsScreen(onGrant = { launcher.launch(Permissions.required().toTypedArray()) })
        return
    }

    // Permissions are in: bring the mesh up if the user wants it, then offer the battery whitelist once.
    val g = remember { SetuApp.graph(context.applicationContext as android.app.Application) }
    var askBattery by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = g.settings.snapshot()
        if (s.courierEnabled || s.sosActive) MeshService.start(context)
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            askBattery = !g.settings.batteryPromptShown.first()
        }
    }
    if (askBattery) {
        BatteryDialog(
            onAllow = {
                askBattery = false
                g.scope.launch { g.settings.setBatteryPromptShown() }
                @Suppress("BatteryLife")
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onLater = {
                askBattery = false
                g.scope.launch { g.settings.setBatteryPromptShown() }
            },
        )
    }

    Main()
}

@Composable
private fun BatteryDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.battery_title)) },
        text = { Text(stringResource(R.string.battery_body)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.battery_allow)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.battery_later)) } },
    )
}

@Composable
private fun Main() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val app = LocalContext.current.applicationContext as android.app.Application
    val factory = remember { SetuViewModelFactory(app) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Sos.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Sos.route) {
                val vm: SosViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                SosScreen(state, onSend = vm::send, onSafe = vm::markSafe)
            }
            composable(Tab.Courier.route) {
                val vm: CourierViewModel = viewModel(factory = factory)
                val enabled by vm.enabled.collectAsStateWithLifecycle()
                val mesh by vm.mesh.collectAsStateWithLifecycle()
                CourierScreen(enabled, mesh, onToggle = vm::setEnabled)
            }
            composable(Tab.Status.route) {
                val vm: StatusViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                StatusScreen(state, onRelayUrl = vm::setRelayUrl, onUploadNow = vm::uploadNow)
            }
        }
    }
}

