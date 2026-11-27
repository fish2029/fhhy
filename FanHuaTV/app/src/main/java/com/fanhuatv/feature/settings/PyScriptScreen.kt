package com.fanhuatv.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
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
import android.provider.DocumentsContract
import kotlinx.coroutines.launch
import com.fanhuatv.core.storage.entity.SavedScriptEntity
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.navigation.AppViewModel
import com.fanhuatv.navigation.Routes
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.TextSecondary

/** 批量导入弹窗内的一个待导入文件：key 唯一（URI），避免同名文件互相覆盖 */
private data class BatchPyFile(val key: String, val name: String, val content: String)

/** PY 脚本管理页：导入 / 列表 / 启停 / 删除 / 重新导入 / 日志 / 依赖检查。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyScriptScreen(
    navController: NavController,
    appVm: AppViewModel,
    vm: SettingsViewModel = hiltViewModel()
) {
    val scripts by vm.pyRuntime.scripts.collectAsStateWithLifecycle(initialValue = emptyList())
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        val message = toast
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.consumeToast()
        }
    }

    var missingDeps by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { missingDeps = vm.pyRuntime.dependencies() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // 导入是异步的：必须等落盘完成再刷新，否则 load() 会跑在脚本入库之前
            vm.importScriptFromUri(it) { appVm.load() }
        }
    }

    // 开启「所有文件访问权限」后返回，刷新权限提示
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionRefresh++ }
    val needsAllFiles = remember(permissionRefresh) { vm.needsAllFilesAccess() }

    var showLogDialog by remember { mutableStateOf<String?>(null) }
    var showReimport by remember { mutableStateOf<SavedScriptEntity?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }

    // 批量导入：待导入文件列表（唯一 key + 文件名 + 内容）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var batchFiles by remember { mutableStateOf<List<BatchPyFile>>(emptyList()) }
    var batchLoading by remember { mutableStateOf(false) }
    var batchReadErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    // 选择配置文件：选一个打包了所有 .py 的 zip，解压后全部加入待导入列表
    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        batchLoading = true
        scope.launch {
            val picked = mutableListOf<BatchPyFile>()
            val failed = mutableListOf<String>()
            runCatching {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: error("无法打开文件")
                java.util.zip.ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val fileName = entry.name.substringAfterLast('/')
                        if (!entry.isDirectory && fileName.isNotBlank() &&
                            (fileName.endsWith(".py", true) || fileName.endsWith(".txt", true))
                        ) {
                            val result = runCatching {
                                // 不能用 use：bufferedReader 关闭会连带关掉底层 ZipInputStream，
                                // 只读第一个 entry 就断流，导致 zip 里只导入一个脚本。
                                // 流统一由外层 zis.use 关闭。
                                val text = zis.bufferedReader(Charsets.UTF_8).readText()
                                if (text.isBlank()) error("内容为空")
                                val base = fileName.removeSuffix(".py").removeSuffix(".txt").ifBlank { "script" }
                                // key 用 zip 内完整相对路径，避免同名文件互相覆盖
                                Triple("$uri/${entry.name}", base, text)
                            }
                            result.onSuccess { (key, name, text) ->
                                picked += BatchPyFile(key, name, text)
                            }.onFailure { e ->
                                failed += "$fileName：${e.message ?: "读取失败"}"
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                if (picked.isEmpty()) failed += "zip 里没有找到 .py / .txt 文件"
            }.onFailure { e ->
                failed += "解压失败：${e.message ?: "请选择有效的 .zip 文件"}"
            }
            batchFiles = (batchFiles + picked).distinctBy { it.key }
            batchReadErrors = failed
            batchLoading = false
        }
    }

    // 选择本地目录：读取目录（含子目录）下所有 .py / .txt 文件加入待导入列表
    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { root ->
        if (root == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                root, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        batchLoading = true
        scope.launch {
            val picked = mutableListOf<BatchPyFile>()
            val failed = mutableListOf<String>()
            // OpenDocumentTree 返回 tree URI（.../tree/primary:xxx），必须用 getTreeDocumentId 解析根目录 ID；
            // 不能用 getDocumentId（只认 .../document/... 格式，否则抛 Invalid URI）。
            val rootDocId = try {
                DocumentsContract.getTreeDocumentId(root)
            } catch (e: Exception) {
                failed += "无法读取目录：${e.message ?: "无效目录"}"
                batchReadErrors = failed
                batchLoading = false
                return@launch
            }
            // docId 为目录的 documentId（相对于 tree root）；子节点 ID 从 cursor 读，不再从 URI 解析
            fun walk(docId: String) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, docId)
                val cursor = try {
                    context.contentResolver.query(
                        childrenUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                        null, null, null
                    )
                } catch (e: Exception) {
                    failed += "目录查询失败：${e.message ?: "未知错误"}"
                    null
                }
                cursor?.use { c ->
                    val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        val childId = if (idIdx >= 0) c.getString(idIdx) else null
                        val dispName = if (nameIdx >= 0) c.getString(nameIdx) else null
                        val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                        if (childId.isNullOrBlank()) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(childId)
                        } else if (dispName != null && (dispName.endsWith(".py", true) || dispName.endsWith(".txt", true))) {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(root, childId)
                            val result = runCatching {
                                val text = context.contentResolver.openInputStream(childUri)
                                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                if (text.isNullOrBlank()) error("内容为空")
                                val base = dispName.removeSuffix(".py").removeSuffix(".txt").ifBlank { "script" }
                                Triple(childUri.toString(), base, text)
                            }
                            result.onSuccess { (key, name, text) ->
                                picked += BatchPyFile(key, name, text)
                            }.onFailure { e ->
                                failed += "$dispName：${e.message ?: "读取失败"}"
                            }
                        }
                    }
                }
            }
            runCatching { walk(rootDocId) }.onFailure { e ->
                failed += "目录读取失败：${e.message ?: "未知错误"}"
            }
            if (picked.isEmpty() && failed.isEmpty()) failed += "目录里没有找到 .py / .txt 文件"
            batchFiles = (batchFiles + picked).distinctBy { it.key }
            batchReadErrors = failed
            batchLoading = false
        }
    }

    Scaffold(
        topBar = { SubTopBar("批量导入PY脚本", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // 底部固定「确认导入」
            Surface(shadowElevation = 8.dp, color = com.fanhuatv.ui.theme.BgSurface) {
                Button(
                    onClick = {
                        if (batchFiles.isEmpty()) return@Button
                        val filesToImport = batchFiles.map { it.name to it.content }
                        batchFiles = emptyList()
                        batchReadErrors = emptyList()
                        scope.launch {
                            val ok = vm.importScriptsBatch(filesToImport)
                            appVm.load()
                            // 导入成功就回首页，让用户直接看到分类而不是停在导入页
                            if (ok > 0) {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.PY_SCRIPT) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp).then(com.fanhuatv.ui.theme.materialButtonBackground()),
                    enabled = batchFiles.isNotEmpty(),
                    colors = com.fanhuatv.ui.theme.materialButtonColors(),
                    shape = com.fanhuatv.ui.theme.buttonShape()
                ) {
                    Text("确认导入", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            InfoBanner("PY 脚本优先级高于手动 JSON 接口。导入后自动识别站点配置、搜索与播放解析逻辑，无需再配置接口。\n[版本 v${com.fanhuatv.BuildConfig.VERSION_NAME} · Build ${com.fanhuatv.BuildConfig.VERSION_CODE}]")

            if (missingDeps.isNotEmpty()) {
                WarningBanner("缺少依赖库：${missingDeps.joinToString("、")}，请在 Chaquopy 配置中安装后重新构建。")
            }

            // TV Box / 手机本地目录导入前，建议开启所有文件访问权限
            if (needsAllFiles) {
                Surface(color = Accent.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("读取手机 / 电脑 / TV Box 目录的 .py 文件，需要「所有文件访问权限」。", color = Accent, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                vm.allFilesAccessIntent()?.let { allFilesLauncher.launch(it) }
                            },
                            colors = com.fanhuatv.ui.theme.materialButtonColors(),
                            modifier = Modifier.then(com.fanhuatv.ui.theme.materialButtonBackground())
                        ) {
                            Text("去开启文件访问权限", color = Color.Black, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ① 选择配置文件（一个打包了所有 .py 的 zip）+ 选择本地目录（整个文件夹）
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Button(
                    onClick = { batchLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth().then(com.fanhuatv.ui.theme.materialButtonBackground()),
                    colors = com.fanhuatv.ui.theme.materialButtonColors(),
                    shape = com.fanhuatv.ui.theme.buttonShape()
                ) {
                    Icon(Icons.Filled.FolderOpen, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text(if (batchLoading) "读取中..." else "选择配置文件（zip）", color = Color.Black)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { dirLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth().then(com.fanhuatv.ui.theme.materialButtonBackground()),
                    colors = com.fanhuatv.ui.theme.materialButtonColors(),
                    shape = com.fanhuatv.ui.theme.buttonShape()
                ) {
                    Icon(Icons.Filled.Folder, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text(if (batchLoading) "读取中..." else "选择本地目录", color = Color.Black)
                }
            }
            if (batchFiles.isNotEmpty()) {
                Text(
                    "已选 ${batchFiles.size} 个文件",
                    color = Accent, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            if (batchReadErrors.isNotEmpty()) {
                Text(batchReadErrors.joinToString("；"), color = Color.Red, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Text("粘贴内容", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.clickable { showPasteDialog = true })
                Text("按路径导入", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.clickable { showPathDialog = true })
            }

            if (scripts.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Code, null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("还没有导入脚本", color = TextSecondary)
                        Text("支持手机目录 / 电脑目录 / TV Box 目录的 .py 文件", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            } else {
                scripts.forEach { script ->
                    ScriptCard(
                        script = script,
                        onToggle = { vm.setScriptEnabled(script.id, !script.enabled); appVm.load() },
                        onDelete = { vm.deleteScript(script.id); appVm.load() },
                        onLog = { showLogDialog = script.id },
                        onReimport = { showReimport = script }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    showLogDialog?.let { id ->
        // 打开时真跑一次诊断：类名、实现了哪些方法、首页能否跑通，失败给 traceback
        var log by remember(id) { mutableStateOf("正在诊断脚本…") }
        LaunchedEffect(id) { log = vm.pyRuntime.probe(id) }
        AlertDialog(
            onDismissRequest = { showLogDialog = null },
            title = { Text("脚本诊断") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(log, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showLogDialog = null }) { Text("关闭") } }
        )
    }

    showReimport?.let { script ->
        var content by remember(script.id) { mutableStateOf("") }
        val ctx = LocalContext.current
        val reimportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                ctx.contentResolver.openInputStream(it)?.bufferedReader(Charsets.UTF_8)?.use { r -> content = r.readText() }
            }
        }
        AlertDialog(
            onDismissRequest = { showReimport = null },
            title = { Text("重新导入更新：${script.name}") },
            text = {
                Column {
                    Button(onClick = { reimportLauncher.launch(arrayOf("*/*")) }) { Text("选择新的 .py 文件") }
                    Spacer(Modifier.height(8.dp))
                    Text(if (content.isBlank()) "请先选择文件" else "已读取 ${content.length} 字符", color = TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (content.isNotBlank()) {
                        vm.reimportScript(script.id, content) { appVm.load() }
                    }
                    showReimport = null
                }) { Text("更新") }
            },
            dismissButton = { TextButton(onClick = { showReimport = null }) { Text("取消") } }
        )
    }

    if (showPasteDialog) {
        var pasteName by remember { mutableStateOf("") }
        var pasteContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴脚本内容") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pasteName,
                        onValueChange = { pasteName = it },
                        label = { Text("脚本名称（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it },
                        label = { Text("粘贴 .py 完整代码") },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        maxLines = 20
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importScriptFromText(pasteName, pasteContent) { appVm.load() }
                    showPasteDialog = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("取消") } }
        )
    }

    if (showPathDialog) {
        var pathInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text("按绝对路径导入") },
            text = {
                Column {
                    Text("示例：/storage/emulated/0/Download/site.py", color = TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        label = { Text("文件绝对路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("若提示无权限，请先到页面上方开启「所有文件访问权限」", color = Accent, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importScriptFromPath(pathInput) { appVm.load() }
                    showPathDialog = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showPathDialog = false }) { Text("取消") } }
        )
    }

}

@Composable
private fun InfoBanner(text: String) {
    Surface(color = Accent.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun WarningBanner(text: String) {
    Surface(color = Color.Red.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ScriptCard(
    script: SavedScriptEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onLog: () -> Unit,
    onReimport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = com.fanhuatv.ui.theme.BgSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(script.name, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "版本 ${script.version} · ${script.siteName.ifBlank { "通用站点" }} · ${script.author}",
                        color = TextSecondary, fontSize = 11.sp
                    )
                }
                Switch(checked = script.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onLog) { Text("日志") }
                TextButton(onClick = onReimport) { Text("重新导入") }
                TextButton(onClick = onDelete) { Text("删除", color = Color.Red) }
            }
        }
    }
}
