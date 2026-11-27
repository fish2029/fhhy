package com.fanhuatv.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.navigation.Routes
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.TextSecondary

/** 设置主页：四大配置区 + 关于入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel()
) {
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        val message = toast
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.consumeToast()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportConfig(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importConfig(it) } }

    Scaffold(
        topBar = { SubTopBar("设置", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection("核心配置") {
                SettingsNavRow(Icons.Filled.Api, "接口配置", "点播接口 / 直播源 / 多源线路") {
                    navController.navigate(Routes.CONFIG)
                }
                SettingsNavRow(Icons.Filled.Code, "PY 脚本管理", "导入 .py 自动注册数据源（优先于 JSON）") {
                    navController.navigate(Routes.PY_SCRIPT)
                }
                val priority by vm.scriptPriority.collectAsStateWithLifecycle()
                SwitchRow("PY 脚本优先", "脚本解析优先于手动 JSON 接口", priority) {
                    vm.setScriptPriority(it)
                }
            }

            SettingsSection("播放设置") {
                val playback by vm.playback.collectAsStateWithLifecycle()
                OptionRow("播放器内核", playback.kernel, listOf("media3", "ijk", "system")) {
                    vm.updatePlayback { p -> p.copy(kernel = it) }
                }
                OptionRow("解码方式", playback.decoder, listOf("hard", "soft")) {
                    vm.updatePlayback { p -> p.copy(decoder = it) }
                }
                OptionRow("渲染方式", playback.surfaceType, listOf("texture", "surface")) {
                    vm.updatePlayback { p -> p.copy(surfaceType = it) }
                }
                SwitchRow("自动连播", "播完自动下一集", playback.autoNext) {
                    vm.updatePlayback { p -> p.copy(autoNext = it) }
                }
                SwitchRow("跳过片头片尾", "", playback.skipIntro) {
                    vm.updatePlayback { p -> p.copy(skipIntro = it) }
                }
                SwitchRow("倍速记忆", "记住上次播放倍速", playback.rememberSpeed) {
                    vm.updatePlayback { p -> p.copy(rememberSpeed = it) }
                }
                OptionRow("画面比例", playback.scaleMode, listOf("fit", "fill", "zoom")) {
                    vm.updatePlayback { p -> p.copy(scaleMode = it) }
                }
            }

            SettingsSection("界面与通用") {
                val ui by vm.ui.collectAsStateWithLifecycle()
                SwitchRow(
                    "智能分类",
                    "站源自动归类：JAR内站点 / PY站点 / 音乐 / 短剧（JAR内站点置顶）",
                    ui.smartCategory
                ) {
                    vm.updateUi { u -> u.copy(smartCategory = it) }
                }
                OptionRow("首页推荐源", ui.homeRecommend, listOf("douban", "site", "history")) {
                    vm.updateUi { u -> u.copy(homeRecommend = it) }
                }
                OptionRow("搜索展示", ui.searchDisplay, listOf("thumb", "text")) {
                    vm.updateUi { u -> u.copy(searchDisplay = it) }
                }
                OptionRow("历史记录条数", ui.historyLimit.toString(), listOf("30", "50", "100")) {
                    vm.updateUi { u -> u.copy(historyLimit = it.toIntOrNull() ?: 50) }
                }
                OptionRow("深色模式", ui.themeMode, listOf("system", "dark", "light")) {
                    vm.updateUi { u -> u.copy(themeMode = it) }
                }
                OptionRow("启动默认页", ui.startPage, listOf("home", "live")) {
                    vm.updateUi { u -> u.copy(startPage = it) }
                }
            }

            SettingsSection("数据与安全") {
                val cacheText = remember { mutableStateOf(vm.cacheSizeText()) }
                NormalRow(Icons.Filled.CleaningServices, "清理缓存", "当前 ${cacheText.value}") {
                    vm.clearCache()
                    cacheText.value = vm.cacheSizeText()
                }
                NormalRow(Icons.Filled.Backup, "备份配置", "导出为 JSON 文件") {
                    exportLauncher.launch("fanhua_backup.json")
                }
                NormalRow(Icons.Filled.Restore, "恢复配置", "从 JSON 文件导入") {
                    importLauncher.launch(arrayOf("application/json"))
                }
                SettingsNavRow(Icons.Filled.Security, "安全状态", "签名校验 / 防二次打包") {
                    navController.navigate(Routes.SECURITY)
                }
            }

            SettingsSection("关于") {
                SettingsNavRow(Icons.Filled.Info, "关于与开源许可", "版本信息 / 用户协议 / 隐私政策") {
                    navController.navigate(Routes.ABOUT)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            color = Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        Surface(color = com.fanhuatv.ui.theme.BgSurface, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsNavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = TextSecondary)
    }
}

@Composable
private fun NormalRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    SettingsNavRow(icon, title, subtitle, onClick)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 15.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionRow(title: String, current: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(current, color = Accent, fontSize = 13.sp)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ArrowDropDown, null, tint = TextSecondary)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}
