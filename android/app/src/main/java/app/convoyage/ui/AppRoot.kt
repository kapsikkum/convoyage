package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    const val PROFILE = "profile"
    const val CONVOY = "convoy?join={join}"
    const val RIDE = "ride"
    const val REPORT = "report"

    fun convoy(join: Boolean) = "convoy?join=$join"
    fun garage(id: String) = "garage/$id"

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

    CompositionLocalProvider(LocalContainer provides container) {
        Box(Modifier.fillMaxSize().background(Cv.colors.void)) {
            NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize()) {
                composable(Routes.WELCOME) {
                    WelcomeScreen(onSignedIn = {
                        nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
                    })
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenConvoy = { join -> nav.navigate(Routes.convoy(join)) },
                        onOpenGarage = { nav.switchTab(Routes.GARAGE) },
                    )
                }
                composable(Routes.FEED) { FeedScreen() }
                composable(Routes.GARAGE) { GarageListScreen(onOpen = { nav.navigate(Routes.garage(it)) }) }
                composable(Routes.GARAGE_DETAIL) { backStack ->
                    GarageDetailScreen(backStack.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onOpenGarage = { nav.switchTab(Routes.GARAGE) },
                        onSignOut = {
                            container.convoy.disconnect()
                            container.session.signOut()
                            nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                        },
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
