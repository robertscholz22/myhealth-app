package com.myhealth.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlin.reflect.KClass
import org.jetbrains.compose.resources.StringResource

/** The five bottom-navigation destinations (§4.1). */
enum class BottomDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val labelRes: StringResource,
    val icon: ImageVector,
) {
    TODAY(TodayRoute, TodayRoute::class, Res.string.nav_today, Icons.Filled.Today),
    CALENDAR(CalendarRoute, CalendarRoute::class, Res.string.nav_calendar, Icons.Filled.CalendarMonth),
    NUTRITION(NutritionRoute, NutritionRoute::class, Res.string.nav_nutrition, Icons.Filled.Restaurant),
    TRAINING(TrainingRoute, TrainingRoute::class, Res.string.nav_training, Icons.Filled.FitnessCenter),
    MORE(MoreRoute, MoreRoute::class, Res.string.nav_more, Icons.Filled.MoreHoriz),
}

@Composable
fun MyHealthBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current: NavDestination? = backStackEntry?.destination
    NavigationBar {
        BottomDestination.entries.forEach { destination ->
            val selected = current.isOn(destination)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected) {
                        navController.popToBottomRoot(destination)
                    } else {
                        navController.navigateToBottomDestination(destination)
                    }
                },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}

private fun NavDestination?.isOn(destination: BottomDestination): Boolean =
    this?.hierarchyContains(destination.routeClass) == true

private fun NavDestination.hierarchyContains(routeClass: KClass<*>): Boolean {
    var node: NavDestination? = this
    while (node != null) {
        if (node.hasRoute(routeClass)) return true
        node = node.parent
    }
    return false
}

/**
 * Bottom-nav behaviour (§4.1): single top, popped to Today, with the leaving tab's stack saved.
 *
 * The Today tab deliberately does **not** restore its own saved stack (POLISH-6): "Today" is the
 * dashboard, and a user who left it on Load & Recovery (opened from a Today card) expects the tab
 * to take them back to the dashboard, not to the detail screen they walked away from. The other
 * four tabs keep full save/restore semantics.
 */
fun NavHostController.navigateToBottomDestination(destination: BottomDestination) {
    val isHome = destination == BottomDestination.TODAY
    navigate(destination.route) {
        popUpTo(TodayRoute) { saveState = true }
        launchSingleTop = true
        restoreState = !isHome
    }
}

/** Re-selecting the current tab pops it back to its own root (POLISH-6). */
fun NavHostController.popToBottomRoot(destination: BottomDestination) {
    popBackStack(destination.route, inclusive = false)
}
