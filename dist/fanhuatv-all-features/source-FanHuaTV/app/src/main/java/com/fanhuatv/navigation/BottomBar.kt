package com.fanhuatv.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.navigation.NavController
import com.fanhuatv.R
import com.fanhuatv.ui.theme.Accent

data class TabItem(val route: String, val labelRes: Int, val icon: ImageVector)

/** 底部导航栏：固定在屏幕最底部，五个主 Tab。showLabels=false 时仅显示图标（底栏形态-仅图标）。 */
@Composable
fun BottomBar(navController: NavController, currentRoute: String?, showLabels: Boolean = true) {
    val tabs = listOf(
        TabItem(Routes.HOME, R.string.tab_home, Icons.Filled.Home),
        TabItem(Routes.CATEGORY, R.string.tab_category, Icons.Filled.GridView),
        TabItem(Routes.HISTORY, R.string.tab_history, Icons.Filled.History),
        TabItem(Routes.LIVE, R.string.tab_live, Icons.Filled.LiveTv),
        TabItem(Routes.MINE, R.string.tab_mine, Icons.Filled.Person)
    )
    NavigationBar(containerColor = com.fanhuatv.ui.theme.BgSurface) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    if (currentRoute != tab.route) {
                        navController.navigate(tab.route) {
                            popUpTo(Routes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = if (showLabels) {
                    { Text(stringResource(tab.labelRes)) }
                } else null,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    indicatorColor = Accent.copy(alpha = 0.12f),
                    unselectedIconColor = com.fanhuatv.ui.theme.TextSecondary,
                    unselectedTextColor = com.fanhuatv.ui.theme.TextSecondary
                )
            )
        }
    }
}
