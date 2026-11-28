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

    var showHotUpdate by remember { mutableStateOf(false) }
    var showDanmaku by remember { mutableStateOf(false) }

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
                SettingsNavRow(Icons.Filled.Sync, "源热更新", "填 PY 脚本远程地址，改源不发包") {
                    showHotUpdate = true
                }
            }

            SettingsSection("播放设置") {
                val playback by vm.playback.collectAsStateWithLifecycle()
                val ui by vm.ui.collectAsStateWithLifecycle()
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
                SwitchRow("退出播放自动变小窗", "按返回键进入悬浮小窗继续播放", ui.autoPip == true) {
                    vm.updateUi { u -> u.copy(autoPip = it) }
                }
                SettingsNavRow(Icons.Filled.Subtitles, "弹幕设置", "字号 / 速度 / 透明度 / 屏蔽词") {
                    showDanmaku = true
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

    // 源热更新弹窗：填远程地址 + 自动拉取开关，保存后可立即刷新
    if (showHotUpdate) {
        val savedUrl by vm.hotUpdateUrl.collectAsStateWithLifecycle()
        val savedAuto by vm.hotUpdateAuto.collectAsStateWithLifecycle()
        var urlInput by remember(showHotUpdate) { mutableStateOf(savedUrl) }
        var autoInput by remember(showHotUpdate) { mutableStateOf(savedAuto) }
        AlertDialog(
            onDismissRequest = { showHotUpdate = false },
            title = { Text("源热更新") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("PY 脚本地址（网盘 / GitHub 直链）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = autoInput, onCheckedChange = { autoInput = it })
                        Spacer(Modifier.width(10.dp))
                        Text("启动时自动拉取最新脚本", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已导入过该地址的脚本会原位覆盖，站点注册不变，改源无需重新安装",
                        color = TextSecondary, fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveHotUpdateConfig(urlInput, autoInput)
                    if (urlInput.isNotBlank()) vm.runHotUpdate()
                    showHotUpdate = false
                }) { Text("保存并刷新") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.saveHotUpdateConfig(urlInput, autoInput)
                    showHotUpdate = false
                }) { Text("仅保存") }
            }
        )
    }

    // 弹幕设置弹窗：字号 / 跨屏速度 / 透明度 / 屏蔽词
    if (showDanmaku) {
        val savedUi by vm.ui.collectAsStateWithLifecycle()
        var sizeInput by remember(showDanmaku) { mutableStateOf((savedUi.danmakuSize ?: 14).coerceIn(10, 24)) }
        var speedInput by remember(showDanmaku) { mutableStateOf((savedUi.danmakuSpeed ?: 9).coerceIn(5, 15)) }
        var alphaInput by remember(showDanmaku) { mutableStateOf((savedUi.danmakuAlpha ?: 100).coerceIn(20, 100)) }
        var blockInput by remember(showDanmaku) { mutableStateOf(savedUi.danmakuBlock ?: "") }
        AlertDialog(
            onDismissRequest = { showDanmaku = false },
            title = { Text("弹幕设置") },
            text = {
                Column {
                    Text("字号 ${sizeInput}sp", fontSize = 13.sp)
                    Slider(value = sizeInput.toFloat(), onValueChange = { sizeInput = it.toInt() }, valueRange = 10f..24f, steps = 6)
                    Text("跨屏速度 ${speedInput} 秒（越小越快）", fontSize = 13.sp)
                    Slider(value = speedInput.toFloat(), onValueChange = { speedInput = it.toInt() }, valueRange = 5f..15f, steps = 9)
                    Text("不透明度 $alphaInput%", fontSize = 13.sp)
                    Slider(value = alphaInput.toFloat(), onValueChange = { alphaInput = it.toInt() }, valueRange = 20f..100f, steps = 15)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = blockInput,
                        onValueChange = { blockInput = it },
                        label = { Text("屏蔽词（用逗号或顿号分隔）") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateUi { u -> u.copy(danmakuSize = sizeInput, danmakuSpeed = speedInput, danmakuAlpha = alphaInput, danmakuBlock = blockInput.trim()) }
                    showDanmaku = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showDanmaku = false }) { Text("取消") }
            }
        )
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
