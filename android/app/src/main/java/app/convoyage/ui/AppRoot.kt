package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.convoyage.AppContainer
import app.convoyage.design.Cv
import app.convoyage.design.CvTabBar
import app.convoyage.design.Tab

object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val FEED = "feed"
    const val GARAGE = "garage"
    const val GARAGE_DETAIL = "garage/{id}"
    const val GARAGE_EDIT = "garage-edit?id={id}"
    const val RECAP = "recap"
    const val RIDE_DETAIL = "ride/{id}"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val CONVOY = "convoy?join={join}"
    const val RIDE = "ride"
    const val REPORT = "report"

    fun convoy(join: Boolean) = "convoy?join=$join"
    fun garage(id: String) = "garage/$id"
    fun garageEdit(id: String?) = if (id == null) "garage-edit" else "garage-edit?id=$id"
    fun ride(id: String) = "ride/$id"

    val tabRoutes = mapOf(HOME to Tab.Home, FEED to Tab.Feed, GARAGE to Tab.Garage, PROFILE to Tab.Profile)
}

@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    val name by container.session.name.collectAsState()
    val start = if (name.isNullOrBlank()) Routes.WELCOME else Routes.HOME
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = Routes.tabRoutes[route]

    // The session decides where we are: signing in (form, deep link) opens Home; signing out, or the server
    // refusing our refresh token, goes back to Welcome from anywhere.
    LaunchedEffect(name, route) {
        val signedIn = !name.isNullOrBlank()
        if (route == null) return@LaunchedEffect
        if (signedIn && route == Routes.WELCOME) nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
        if (!signedIn && route != Routes.WELCOME && route != Routes.SETTINGS) nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
    }

    CompositionLocalProvider(LocalContainer provides container, LocalOpenRecap provides { nav.navigate(Routes.RECAP) { launchSingleTop = true } }) {
        Box(Modifier.fillMaxSize().background(Cv.colors.void)) {
            NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize()) {
                composable(Routes.WELCOME) {
                    WelcomeScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
                }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.RECAP) { RecapScreen(rideId = null, onClose = { nav.popBackStack() }) }
                composable(Routes.RIDE_DETAIL) { backStack ->
                    RecapScreen(rideId = backStack.arguments?.getString("id"), onClose = { nav.popBackStack() })
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenConvoy = { join -> nav.navigate(Routes.convoy(join)) },
                        onOpenGarage = { nav.switchTab(Routes.GARAGE) },
                    )
                }
                composable(Routes.FEED) { FeedScreen() }
                composable(Routes.GARAGE) {
                    GarageListScreen(onOpen = { nav.navigate(Routes.garage(it)) }, onAdd = { nav.navigate(Routes.garageEdit(null)) })
                }
                composable(Routes.GARAGE_DETAIL) { backStack ->
                    GarageDetailScreen(
                        backStack.arguments?.getString("id").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onEdit = { nav.navigate(Routes.garageEdit(it)) },
                    )
                }
                composable(
                    Routes.GARAGE_EDIT,
                    arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null }),
                ) { backStack ->
                    GarageEditScreen(backStack.arguments?.getString("id"), onDone = { nav.popBackStack() })
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onOpenGarage = { nav.switchTab(Routes.GARAGE) },
                        onOpenVehicle = { nav.navigate(Routes.garage(it)) },
                        onAddVehicle = { nav.navigate(Routes.garageEdit(null)) },
                        onOpenRide = { nav.navigate(Routes.ride(it)) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onSignOut = container::signOut,
                    )
                }
                composable(
                    Routes.CONVOY,
                    arguments = listOf(androidx.navigation.navArgument("join") { type = androidx.navigation.NavType.BoolType; defaultValue = false }),
                ) { backStack ->
                    ConvoyScreen(
                        startWithJoin = backStack.arguments?.getBoolean("join") ?: false,
                        onRide = { nav.navigate(Routes.RIDE) },
                        onReport = { nav.navigate(Routes.REPORT) },
                        onClose = { nav.popBackStack() },
                    )
                }
                composable(Routes.RIDE) { RideScreen(onExit = { nav.popBackStack() }, onReport = { nav.navigate(Routes.REPORT) }) }
                composable(Routes.REPORT) { ReportScreen(onBack = { nav.popBackStack() }) }
            }

            if (tab != null) {
                CvTabBar(
                    selected = tab,
                    onSelect = { t ->
                        when (t) {
                            Tab.Convoy -> nav.navigate(Routes.convoy(false))
                            Tab.Home -> nav.switchTab(Routes.HOME)
                            Tab.Feed -> nav.switchTab(Routes.FEED)
                            Tab.Garage -> nav.switchTab(Routes.GARAGE)
                            Tab.Profile -> nav.switchTab(Routes.PROFILE)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
