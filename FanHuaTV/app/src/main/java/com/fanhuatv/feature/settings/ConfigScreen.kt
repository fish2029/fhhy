package com.fanhuatv.feature.settings

import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import com.fanhuatv.core.model.ConfigSource
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.navigation.AppViewModel
import com.fanhuatv.navigation.Routes
import com.fanhuatv.ui.theme.Accent

/** 接口配置页：点播接口多源管理 + 扫码推送 + 直播源配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    navController: NavController,
    appVm: AppViewModel,
    vm: SettingsViewModel = hiltViewModel()
) {
    val sources by vm.configSources.collectAsStateWithLifecycle()
    val activeId by vm.activeSourceId.collectAsStateWithLifecycle()
    val liveUrl by vm.liveUrl.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        val message = toast
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.consumeToast()
        }
    }

    var inputUrl by remember { mutableStateOf("") }
    var liveInput by remember { mutableStateOf(liveUrl) }
    var liveTemplate by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(liveUrl) { liveInput = liveUrl }

    Scaffold(
        topBar = { SubTopBar("接口配置", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            // 点播接口
            SectionCard("点播接口（可添加多个线路）") {
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    placeholder = { Text("粘贴远程 JSON 接口地址，如 https://xxx/config.json") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = com.fanhuatv.ui.theme.BgCard
                    )
                )
                Spacer(Modifier.height(8.dp))
                // 醒目的确认按钮：填好地址点它 → 设为当前线路 + 真正加载 + 刷新首页
                Button(
                    onClick = {
                        if (confirming) return@Button
                        confirming = true
                        scope.launch {
                            val ok = vm.confirmAndActivate(inputUrl)
                            if (ok) {
                                inputUrl = ""
                                appVm.load()
                                // 加载成功后直接回首页，让用户立刻看到分类/影片是否真的出来了
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.HOME) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                            confirming = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !confirming,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    if (confirming) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载...", color = Color.Black, fontWeight = FontWeight.Bold)
                    } else {
                        Text("✓ 确认并加载接口", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "填好地址后点上面按钮，立即生效并刷新首页（输入框留空则重新加载当前线路）",
                    color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = {
                        vm.addSource(inputUrl)
                        inputUrl = ""
                    }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("仅添加", color = Color.Black)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        Toast.makeText(context, "扫码推送：请用手机扫描配置平台二维码后粘贴地址", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("扫码推送")
                    }
                }

                Spacer(Modifier.height(12.dp))
                sources.forEach { source ->
                    SourceRow(
                        source = source,
                        isActive = source.id == activeId,
                        onActive = { vm.setActiveSource(source.id); appVm.load() },
                        onTest = { vm.testSource(source) },
                        onDelete = { vm.removeSource(source.id) }
                    )
                }
                if (testResult.isNotBlank()) {
                    Text(testResult, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // 直播源
            SectionCard("直播源配置") {
                OutlinedTextField(
                    value = liveInput,
                    onValueChange = { liveInput = it },
                    placeholder = { Text("IPTV 直播源地址（txt / m3u8）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = com.fanhuatv.ui.theme.BgCard
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = liveTemplate,
                    onValueChange = { liveTemplate = it },
                    placeholder = { Text("自定义模板（可选），如 #EXTM3U 头信息") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = com.fanhuatv.ui.theme.BgCard
                    )
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.setLive(liveInput, liveTemplate) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("保存直播源", color = Color.Black)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(title, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        Surface(color = com.fanhuatv.ui.theme.BgSurface, shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun SourceRow(
    source: ConfigSource,
    isActive: Boolean,
    onActive: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onActive),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Accent.copy(alpha = 0.15f) else com.fanhuatv.ui.theme.BgCard
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${source.name} ${if (isActive) "· 当前线路" else ""}",
                    color = if (isActive) Accent else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(source.url, color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 11.sp, maxLines = 1)
            }
            IconButton(onClick = onTest) {
                Icon(Icons.Filled.CheckCircle, "测试", tint = Accent)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "删除", tint = Color.Red)
            }
        }
    }
}
