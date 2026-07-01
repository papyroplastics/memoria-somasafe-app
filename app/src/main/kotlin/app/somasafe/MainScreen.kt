package app.somasafe

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.somasafe.backend.data.AuthStore
import app.somasafe.backend.ui.BackendDownloadScreen
import app.somasafe.backend.ui.BackendLoginScreen
import app.somasafe.backend.ui.BackendModelsViewModel
import app.somasafe.backend.ui.ModelDetailScreen
import app.somasafe.backend.ui.ModelListScreen
import app.somasafe.bluetooth.ui.BluetoothPermissionBox
import app.somasafe.bluetooth.ui.ConnectDeviceScreen
import app.somasafe.bluetooth.ui.FindDevicesScreen
import app.somasafe.bluetooth.ui.LocalBluetoothSession
import app.somasafe.bluetooth.ui.ProvideBluetoothSession
import app.somasafe.capture.ui.CaptureScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

// One nested graph per bottom-nav tab, each with its own back stack.
@Serializable private object BluetoothTab
@Serializable private object CapturesTab
@Serializable private object BackendTab

@Serializable private object DeviceList
@Serializable private object DeviceDetail
@Serializable private object Captures
@Serializable private object BackendLogin
@Serializable private object BackendHome
@Serializable private object ModelList
@Serializable private data class ModelDetail(val key: String)

private enum class AppTab(val title: String, val icon: ImageVector, val graph: Any) {
    BLUETOOTH("BLE Scanner", Icons.Rounded.Bluetooth, BluetoothTab),
    CAPTURES("Captures", Icons.Rounded.Storage, CapturesTab),
    BACKEND("Backend", Icons.Rounded.Hub, BackendTab),
}

// Destinations that sit above a tab's root, so they show a back arrow.
private val CHILD_ROUTES: List<KClass<*>> =
    listOf(DeviceDetail::class, ModelList::class, ModelDetail::class)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() = ProvideBluetoothSession {
    val context = LocalContext.current
    val session = LocalBluetoothSession.current
    val navController = rememberNavController()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val selectedTab = AppTab.entries.firstOrNull { tab ->
        currentDest?.hierarchy?.any { it.hasRoute(tab.graph::class) } == true
    } ?: AppTab.BLUETOOTH
    val showBack = currentDest != null && CHILD_ROUTES.any { currentDest.hasRoute(it) }

    val onBack: () -> Unit = {
        if (navController.currentBackStackEntry?.destination?.hasRoute(DeviceDetail::class) == true) {
            session.disconnect()
        }
        navController.navigateUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedTab.title) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = {
                            navController.navigate(tab.graph) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BluetoothTab,
            modifier = Modifier.padding(innerPadding),
        ) {
            navigation<BluetoothTab>(startDestination = DeviceList) {
                composable<DeviceList> {
                    BluetoothPermissionBox {
                        FindDevicesScreen {
                            session.select(it)
                            navController.navigate(DeviceDetail)
                        }
                    }
                }
                composable<DeviceDetail> {
                    BackHandler { onBack() }
                    BluetoothPermissionBox {
                        val d = session.device
                        val conn = session.connection
                        val ctrl = session.controller
                        if (d != null && conn != null && ctrl != null) {
                            ConnectDeviceScreen(d, conn, ctrl)
                        } else {
                            // Device was cleared out from under us; fall back to the list.
                            LaunchedEffect(Unit) { navController.navigateUp() }
                        }
                    }
                }
            }

            navigation<CapturesTab>(startDestination = Captures) {
                composable<Captures> { CaptureScreen() }
            }

            navigation<BackendTab>(startDestination = BackendLogin) {
                composable<BackendLogin> {
                    LaunchedEffect(Unit) {
                        if (AuthStore.isLoggedIn(context)) {
                            navController.navigate(BackendHome) {
                                popUpTo(BackendLogin) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    BackendLoginScreen {
                        navController.navigate(BackendHome) {
                            popUpTo(BackendLogin) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                composable<BackendHome> {
                    val vm: BackendModelsViewModel = viewModel()
                    BackendDownloadScreen(
                        vm = vm,
                        onLogout = {
                            navController.navigate(BackendLogin) {
                                popUpTo(BackendHome) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onOpenLocalModels = { navController.navigate(ModelList) },
                    )
                }
                composable<ModelList> {
                    ModelListScreen { navController.navigate(ModelDetail(it)) }
                }
                composable<ModelDetail> { entry ->
                    ModelDetailScreen(entry.toRoute<ModelDetail>().key) { navController.navigateUp() }
                }
            }
        }
    }
}
