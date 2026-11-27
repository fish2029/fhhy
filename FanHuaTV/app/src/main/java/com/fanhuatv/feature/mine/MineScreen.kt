package com.fanhuatv.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.fanhuatv.feature.settings.SettingsViewModel
import com.fanhuatv.navigation.Routes
import com.fanhuatv.ui.theme.ACCENT_SCHEMES
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.BgCard
import com.fanhuatv.ui.theme.BgSurface
import com.fanhuatv.ui.theme.TextSecondary

/** 我的页（即设置页）：卡片式分组——我的内容 / 核心配置 / 安全调试 / 主题色彩 / 导航首页 / 系统。 */
@Composable
fun MineScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部：品牌标识
        Surface(color = BgSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Accent,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(Modifier, Alignment.Center) {
                        Text("繁华", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("繁华TV", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("影视 · 直播 一站式", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ===== 1. 我的内容 =====
            GroupCard("我的内容") {
                NavRow(Icons.Filled.History, "观看历史", "接着上次继续看") {
                    navController.navigate(Routes.HISTORY)
                }
                NavRow(Icons.Filled.Favorite, "我的收藏", "收藏的影片都在这") {
                    navController.navigate(Routes.FAVORITE)
                }
            }

            // ===== 2. 核心配置 =====
            GroupCard("核心配置") {
                NavRow(Icons.Filled.Code, "PY 脚本管理", "导入 .py 自动注册数据源（优先于 JSON）") {
                    navController.navigate(Routes.PY_SCRIPT)
                }
                NavRow(Icons.Filled.Tv, "接口配置", "点播接口 / 直播源 / 多源线路") {
                    navController.navigate(Routes.CONFIG)
                }
            }

            // ===== 3. 安全调试 =====
            GroupCard("安全调试") {
                NavRow(Icons.Filled.Security, "安全状态", "签名校验 / 防二次打包") {
                    navController.navigate(Routes.SECURITY)
                }
                NavRow(Icons.Filled.BugReport, "崩溃日志", "上次崩溃的完整堆栈，截图即可反馈") {
                    navController.navigate(Routes.CRASH_LOG)
                }
            }

            // ===== 4. 主题色彩 =====
            GroupCard("主题色彩") {
                // 外观模式：三段胶囊（跟随系统 / 浅色日间 / 深色夜间），切换即时生效
                SettingHeader(Icons.Filled.Palette, "外观模式", current = modeLabel(ui.themeMode))
                SegmentedCapsule(
                    options = listOf("system" to "跟随系统", "light" to "浅色日间", "dark" to "深色夜间"),
                    selected = ui.themeMode,
                    onSelect = { key -> vm.updateUi { it.copy(themeMode = key) } }
                )
                // 强调色：一排色点，选中的带勾，点击全局即时换色
                SettingHeader(Icons.Filled.ColorLens, "主题强调色", current = accentLabel(ui.accent ?: "purple"))
                AccentDotRow(
                    selected = ui.accent ?: "purple",
                    onSelect = { key -> vm.updateUi { it.copy(accent = key) } }
                )
                OptionRow(Icons.Filled.FormatSize, "界面整体缩放", current = "${ui.fontScale ?: 100}%") { close ->
                    DropdownItem("90%") { vm.updateUi { it.copy(fontScale = 90) }; close() }
                    DropdownItem("100%") { vm.updateUi { it.copy(fontScale = 100) }; close() }
                    DropdownItem("110%") { vm.updateUi { it.copy(fontScale = 110) }; close() }
                    DropdownItem("120%") { vm.updateUi { it.copy(fontScale = 120) }; close() }
                }
                Text(
                    "三档外观即时切换：浅色日间为亮灰调，跟随系统为默认深黑，深色夜间为纯黑；切换不会中断浏览位置。",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp)
                )
            }

            // ===== 5. 导航首页 =====
            GroupCard("导航首页") {
                OptionRow(Icons.Filled.ViewWeek, "底部导航栏形态", current = bottomBarLabel(ui.bottomBarStyle ?: "labeled")) { close ->
                    DropdownItem("图标+文字") { vm.updateUi { it.copy(bottomBarStyle = "labeled") }; close() }
                    DropdownItem("仅图标") { vm.updateUi { it.copy(bottomBarStyle = "icons") }; close() }
                }
                OptionRow(Icons.Filled.SwapVert, "滑动页面时底栏自动收起", current = if (ui.bottomBarAutoHide == true) "上滑折叠" else "常驻显示") { close ->
                    DropdownItem("常驻显示") { vm.updateUi { it.copy(bottomBarAutoHide = false) }; close() }
                    DropdownItem("上滑折叠") { vm.updateUi { it.copy(bottomBarAutoHide = true) }; close() }
                }
                SwitchRow(Icons.Filled.PhotoLibrary, "首页精选大图横幅", "顶部轮播大图", ui.homeBanner != false) { on ->
                    vm.updateUi { it.copy(homeBanner = on) }
                }
                OptionRow(Icons.Filled.GridView, "海报墙排布密度", current = columnsLabel(ui.posterColumns ?: 3)) { close ->
                    DropdownItem("疏松（2 列）") { vm.updateUi { it.copy(posterColumns = 2) }; close() }
                    DropdownItem("标准（3 列）") { vm.updateUi { it.copy(posterColumns = 3) }; close() }
                    DropdownItem("紧密（4 列）") { vm.updateUi { it.copy(posterColumns = 4) }; close() }
                }
                OptionRow(Icons.Filled.Subtitles, "海报下方信息显示", current = if (ui.posterInfo == "title") "仅标题" else "全部显示") { close ->
                    DropdownItem("全部显示") { vm.updateUi { it.copy(posterInfo = "all") }; close() }
                    DropdownItem("仅标题") { vm.updateUi { it.copy(posterInfo = "title") }; close() }
                }
                SwitchRow(Icons.Filled.History, "首页显示继续观看", "没看完的影片在首页接着看", ui.continueWatching != false) { on ->
                    vm.updateUi { it.copy(continueWatching = on) }
                }
            }

            // ===== 6. 系统 =====
            GroupCard("系统") {
                SwitchRow(Icons.Filled.Category, "智能分类", "站源自动归类：JAR内站点 / PY站点 / 音乐 / 短剧", ui.smartCategory) { on ->
                    vm.updateUi { it.copy(smartCategory = on) }
                }
                NavRow(Icons.Filled.Info, "关于", "版本信息 / 用户协议 / 隐私政策") {
                    navController.navigate(Routes.ABOUT)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun modeLabel(key: String): String = when (key) {
    "dark" -> "深色夜间"
    "light" -> "浅色日间"
    else -> "跟随系统"
}

private fun accentLabel(key: String): String = when (key) {
    "purple" -> "典雅紫"
    "blue" -> "深海蓝"
    "cyan" -> "湖水青"
    "pink" -> "绯红玫粉"
    "orange" -> "暖阳橙"
    "green" -> "松石绿"
    else -> key
}

private fun bottomBarLabel(key: String): String = when (key) {
    "icons" -> "仅图标"
    else -> "图标+文字"
}

private fun columnsLabel(columns: Int): String = when (columns) {
    2 -> "疏松"
    4 -> "紧密"
    else -> "标准"
}

/** 卡片式分组：圆角卡片 + 紫色组标题 */
@Composable
private fun GroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = BgCard,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                title,
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            content()
        }
    }
}

/** 行左侧的彩色小圆角图标（全局紫主题：淡紫底 + 紫图标） */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(Accent.copy(alpha = 0.15f), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(17.dp))
    }
}

/** 带图标和右侧当前值的行头（配合胶囊/色点等展开控件用） */
@Composable
private fun SettingHeader(icon: ImageVector, title: String, current: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(current, color = Accent, fontSize = 13.sp)
    }
}

/** 三段式分段胶囊（外观模式），选中段紫底白字 */
@Composable
private fun SegmentedCapsule(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (key, label) ->
            val isSel = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSel) Accent else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSel) Color.White else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** 强调色色点选择器：一排圆点，选中的带勾 + 描边 */
@Composable
private fun AccentDotRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ACCENT_SCHEMES.forEach { (key, scheme) ->
            val isSel = key == selected
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.base)
                    .border(
                        width = if (isSel) 2.dp else 0.dp,
                        color = if (isSel) Color.White.copy(alpha = 0.85f) else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center
            ) {
                if (isSel) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 下拉选项行：点整行弹出菜单，选项用 DropdownItem 逐个写 */
@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    current: String,
    items: @Composable (close: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(current, color = Accent, fontSize = 13.sp)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextSecondary)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

@Composable
private fun DropdownItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}
