package com.fanhuatv.feature.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.fanhuatv.feature.settings.SettingsViewModel
import com.fanhuatv.navigation.Routes
import com.fanhuatv.ui.theme.Accent

/** 我的页（即设置页）：历史、收藏、智能分类开关、PY 脚本管理、接口配置、安全状态、关于入口。 */
@Composable
fun MineScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = com.fanhuatv.ui.theme.BgSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = Accent,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(Modifier, Alignment.Center) {
                        Text("繁华", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("繁华TV", color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("影视 · 直播 一站式", color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 12.sp)
                }
            }
        }

        Column(modifier = Modifier.padding(12.dp)) {
            MenuItem(Icons.Filled.History, "观看历史") { navController.navigate(Routes.HISTORY) }
            MenuItem(Icons.Filled.Favorite, "我的收藏") { navController.navigate(Routes.FAVORITE) }
            MenuDivider()
            MenuItem(Icons.Filled.Code, "PY 脚本管理") { navController.navigate(Routes.PY_SCRIPT) }
            MenuItem(Icons.Filled.Tv, "接口配置") { navController.navigate(Routes.CONFIG) }
            MenuDivider()
            MenuItem(Icons.Filled.Security, "安全状态") { navController.navigate(Routes.SECURITY) }
            MenuItem(Icons.Filled.BugReport, "崩溃日志") { navController.navigate(Routes.CRASH_LOG) }
            // 智能分类开关：开启后首页站源按 JAR内站点/PY站点/音乐/短剧 分组（JAR内站点置顶）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Category, contentDescription = null, tint = Accent)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("智能分类", color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp)
                    Text(
                        "站源自动归类：JAR内站点 / PY站点 / 音乐 / 短剧",
                        color = com.fanhuatv.ui.theme.TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = ui.smartCategory,
                    onCheckedChange = { on -> vm.updateUi { it.copy(smartCategory = on) } }
                )
            }
            MenuItem(Icons.Filled.Info, "关于") { navController.navigate(Routes.ABOUT) }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent)
        Spacer(Modifier.width(16.dp))
        Text(title, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = com.fanhuatv.ui.theme.TextSecondary
        )
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(color = com.fanhuatv.ui.theme.BgCard, modifier = Modifier.padding(vertical = 4.dp))
}
