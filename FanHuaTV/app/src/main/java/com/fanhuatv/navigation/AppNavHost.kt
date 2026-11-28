package com.fanhuatv.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fanhuatv.feature.category.CategoryScreen
import com.fanhuatv.feature.detail.DetailScreen
import com.fanhuatv.feature.home.HomeScreen
import com.fanhuatv.feature.live.LiveScreen
import com.fanhuatv.feature.mine.MineScreen
import com.fanhuatv.feature.player.PlayerScreen
import com.fanhuatv.feature.search.SearchScreen
import com.fanhuatv.feature.settings.*
import com.fanhuatv.feature.history.HistoryScreen

@Composable
fun AppNavHost(appVm: AppViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val config by appVm.config.collectAsStateWithLifecycle()
    val status by appVm.status.collectAsStateWithLifecycle()
    val needStorage by appVm.needStorageAccess.collectAsStateWithLifecycle()

    // jar 配置中心读 /sdcard/TVBox 必备「所有文件访问」：缺权限时主动引导去系统设置
    if (needStorage) {
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { appVm.dismissStorageAccess() },
            title = { androidx.compose.material3.Text("需要文件访问权限") },
            text = {
                androidx.compose.material3.Text(
                    "配置中心要读取手机存储 TVBox 目录里的网盘配置（/sdcard/TVBox），请授予「所有文件访问」权限后返回。"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    appVm.dismissStorageAccess()
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { androidx.compose.material3.Text("去授权") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { appVm.dismissStorageAccess() }) {
                    androidx.compose.material3.Text("暂不")
                }
            }
        )
    }

    val showBottomBar = currentRoute in Routes.TAB_ROUTES

    // 底栏形态 / 自动收起：设置来自「我的-导航首页」
    val uiSettings by appVm.uiSettings.collectAsStateWithLifecycle()
    val autoHide = uiSettings.bottomBarAutoHide == true
    val bottomBarVisible = remember { mutableStateOf(true) }
    LaunchedEffect(autoHide) { if (!autoHide) bottomBarVisible.value = true }
    // 嵌套滚动监听：内容上滑（y<0）收底栏，下滑（y>0）拉回来；关闭自动收起时恒显
    val hideOnScroll = remember(autoHide) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (autoHide) {
                    if (available.y < -4f) bottomBarVisible.value = false
                    else if (available.y > 4f) bottomBarVisible.value = true
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = bottomBarVisible.value,
                    enter = androidx.compose.animation.slideInVertically { it },
                    exit = androidx.compose.animation.slideOutVertically { it }
                ) {
                    BottomBar(navController, currentRoute, showLabels = uiSettings.bottomBarStyle != "icons")
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .padding(padding)
                .nestedScroll(hideOnScroll),
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(220)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(220)) }
        ) {
            composable(Routes.HOME) {
                HomeScreen(config, status, appVm, navController)
            }
            composable(Routes.CATEGORY) {
                CategoryScreen(config, navController, appVm)
            }
            composable(Routes.LIVE) {
                LiveScreen(config, navController)
            }
            composable(Routes.MINE) {
                MineScreen(navController)
            }

            // ===== 二级页面 =====
            composable(Routes.SEARCH) { SearchScreen(config, navController) }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("vodId") { type = NavType.StringType })
            ) { backStack ->
                val vodId = backStack.arguments?.getString("vodId").orEmpty()
                DetailScreen(vodId, config, navController)
            }
            composable(Routes.PLAYER) { PlayerScreen(navController) }
            composable(Routes.SETTINGS) { SettingsScreen(navController) }
            composable(Routes.CONFIG) { ConfigScreen(navController, appVm) }
            composable(Routes.PY_SCRIPT) { PyScriptScreen(navController, appVm) }
            composable(Routes.HISTORY) { HistoryScreen(navController, asTab = true) }
            composable(Routes.FAVORITE) { HistoryScreen(navController, favoriteOnly = true) }
            composable(Routes.POSTER_WALL) { com.fanhuatv.feature.history.PosterWallScreen(config, navController) }
            composable(Routes.ABOUT) { AboutScreen(navController) }
            composable(Routes.SECURITY) { SecurityScreen(navController) }
            composable(Routes.CRASH_LOG) { CrashLogScreen(navController) }
        }
    }
}
