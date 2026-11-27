package com.fanhuatv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.CategoryConfig
import com.fanhuatv.core.model.SmartSiteCategory
import com.fanhuatv.core.util.Resource
import com.fanhuatv.feature.common.SectionTitle
import com.fanhuatv.feature.common.VodCard
import com.fanhuatv.navigation.AppViewModel
import com.fanhuatv.navigation.CategoryBus
import com.fanhuatv.navigation.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HomeScreen(
    config: AppConfig?,
    status: Resource<AppConfig>,
    appVm: AppViewModel,
    navController: NavController
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // jar 配置项 action 执行提示：底部浮层，3 秒自动消失
        val actionToast by appVm.actionToast.collectAsStateWithLifecycle()
        if (actionToast.isNotBlank()) {
            LaunchedEffect(actionToast) {
                delay(3000)
                appVm.consumeActionToast()
            }
            Text(
                actionToast,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC3B2C6E))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        // 崩溃横幅：上次崩溃的一句话摘要，点一下消失，截图即可反馈，不用翻日志
        val context = LocalContext.current
        var crashBanner by remember {
            mutableStateOf(
                com.fanhuatv.core.CrashGuard.lastCrashSummary.ifBlank {
                    runCatching {
                        File(context.filesDir, "logs/last_crash.txt").readText()
                            .trim().lineSequence().firstOrNull().orEmpty()
                    }.getOrDefault("")
                }
            )
        }
        if (crashBanner.isNotBlank()) {
            Text(
                "上次崩溃：$crashBanner",
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC6B3A3A))
                    .clickable { crashBanner = "" }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        when (status) {
            is Resource.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            is Resource.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((status as Resource.Error).message, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { appVm.load() }) { Text("重试") }
                    TextButton(onClick = { navController.navigate(Routes.CONFIG) }) {
                        Text("去配置接口")
                    }
                }
            }
            is Resource.Success -> {
                // 「我的-导航首页」设置：大图横幅 / 海报密度 / 继续观看
                val uiSettings by appVm.uiSettings.collectAsStateWithLifecycle()
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(72.dp)) // 给悬浮搜索栏留位

                    // 轮播 Banner（可在「我的-导航首页-大图横幅」关闭）
                    val banners = config?.banners.orEmpty()
                    if (uiSettings.homeBanner != false && banners.isNotEmpty()) {
                        BannerCarousel(banners) { vodId ->
                            if (vodId.isNotBlank()) navController.navigate(Routes.detail(vodId))
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // 分类栏：来自 homeContent 的 class / ?ac=list。
                    // 点分类时把 tid + 所属脚本一起带过去，分类页才知道问谁要数据
                    val categories = config?.categories.orEmpty()
                    if (categories.isNotEmpty()) {
                        CategoryBar(categories) { category ->
                            CategoryBus.select(
                                category.key,
                                category.siteKey.removePrefix("py_"),
                                category.name
                            )
                            navController.navigate(Routes.CATEGORY) {
                                popUpTo(Routes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // 继续观看：有进度没看完的历史，点海报直接回详情续播（可在设置关闭）
                    val continueList by appVm.continueWatching.collectAsStateWithLifecycle()
                    if (uiSettings.continueWatching != false && continueList.isNotEmpty()) {
                        ContinueWatchingRow(continueList) { vodId ->
                            navController.navigate(Routes.detail(vodId))
                        }
                    }

                    val homeItems = config?.homeItems.orEmpty()
                    if (homeItems.isEmpty() && config?.categories?.isNotEmpty() == true) {
                        Text(
                            "当前脚本未提供推荐影片，可点上方分类查看",
                            color = com.fanhuatv.ui.theme.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    com.fanhuatv.player.LivePlayBus.rememberItems(homeItems)
                    LazyVerticalGrid(
                        // 列数自动判定：按设置密度换算最小卡宽，屏幕宽就多列、窄就少列，不再写死
                        columns = GridCells.Adaptive(
                            minSize = when (uiSettings.posterColumns ?: 3) {
                                2 -> 150.dp
                                4 -> 96.dp
                                else -> 115.dp
                            }
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(homeItems) { item ->
                            VodCard(
                                item = item,
                                // 海报下方信息显示：仅标题时隐藏评分角标
                                showExtra = uiSettings.posterInfo != "title",
                                onClick = {
                                    // jar 配置项（带 action）：执行动作（弹扫码/输入框等），不进详情播放
                                    if (item.source == "jar" && item.action.isNotBlank()) {
                                        appVm.runJarAction(item)
                                    } else {
                                        com.fanhuatv.player.LivePlayBus.pendingDetail = item
                                        navController.navigate(Routes.detail(item.id.ifBlank { item.title }))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        val smartCategory by appVm.smartCategory.collectAsStateWithLifecycle()

        // 顶部悬浮圆角栏：左边首页站源切换，右边搜索胶囊（缩窄）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeSiteSwitcher(
                appVm = appVm,
                config = config,
                smartCategory = smartCategory,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FloatingSearchBar(
                onClick = { navController.navigate(Routes.SEARCH) },
                modifier = Modifier.weight(2.2f)
            )
        }
    }
}

/** 首页站源切换：列出所有已启用站点，点哪个就把首页数据切到哪个脚本。
 *  smartCategory=true 时按 JAR内站点 / PY站点 / 音乐 / 短剧 分组渲染（JAR内站点置顶）。 */
@Composable
internal fun HomeSiteSwitcher(
    appVm: AppViewModel,
    config: AppConfig?,
    smartCategory: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val currentId by appVm.homeSiteId.collectAsStateWithLifecycle()
    val scriptSites by appVm.scriptSites.collectAsStateWithLifecycle()
    // 站源就是配置里真实存在的站点：接口里的站点（含 csp_/jar）+ 本地导入的 PY 脚本。
    val configSites = config?.sites.orEmpty().filter { it.enabled }
    val extraScripts = scriptSites.filterNot { s ->
        configSites.any { it.scriptId == s.scriptId && s.scriptId.isNotBlank() }
    }
    val sites = configSites + extraScripts
    val currentName = sites.firstOrNull {
        (it.scriptId.ifBlank { it.key }) == currentId && currentId.isNotBlank()
    }?.name ?: sites.firstOrNull()?.name ?: "站源"

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(com.fanhuatv.ui.theme.AccentPill))
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    currentName,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }

        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF3B2C6E), Color(0xFF241A47))
                            )
                        )
                        .padding(16.dp)
                ) {
                    // 顶部渐变标题栏（同参考图的"搜索站源"胶囊）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF7B5FE0), Color(0xFFB06DE6))
                                )
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("首页站源", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(14.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (smartCategory) {
                            // 智能分类：按固定顺序分组（JAR内站点强制第一），空分组不显示
                            SmartSiteCategory.group(sites).forEach { (category, groupSites) ->
                                Text(
                                    category,
                                    color = com.fanhuatv.ui.theme.Accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                                )
                                groupSites.forEach { site ->
                                    val id = site.scriptId.ifBlank { site.key }
                                    SitePill(
                                        label = site.name,
                                        selected = id == currentId && currentId.isNotBlank(),
                                        onClick = {
                                            appVm.switchHomeSite(id)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        } else {
                            sites.forEach { site ->
                                val id = site.scriptId.ifBlank { site.key }
                                SitePill(
                                    label = site.name,
                                    selected = id == currentId && currentId.isNotBlank(),
                                    onClick = {
                                        appVm.switchHomeSite(id)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 弹窗内的胶囊描边按钮，选中高亮 */
@Composable
private fun SitePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) Color(0xFFC08BFF) else Color(0x66B39BE8)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label + if (selected) "  ✓" else "",
                color = if (selected) Color(0xFFE4C6FF) else Color.White,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun CategoryBar(
    categories: List<CategoryConfig>,
    onSelect: (CategoryConfig) -> Unit
) {
    // 样式与原来一致（渐变胶囊 + 横向滚动），一屏正好放 4 个等宽胶囊，
    // 后面的分类按左右键移动焦点时自动滚出来
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 左内边距 12dp + 3 个 8dp 间距 = 36dp，剩余宽度均分 4 份，
        // 这样第 4 个胶囊右缘正好贴屏幕右缘，第 5 个完全在屏幕外不露头
        val itemWidth = (maxWidth - 36.dp) / 4
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(com.fanhuatv.ui.theme.AccentPill))
                        .clickable { onSelect(category) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        category.name.ifBlank { "未命名" },
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
                    )
                }
            }
            // 滚到最右时的收尾边距
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
internal fun FloatingSearchBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(com.fanhuatv.ui.theme.AccentPill))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("搜索影片、演员、导演", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun BannerCarousel(
    banners: List<com.fanhuatv.core.model.BannerConfig>,
    onClick: (String) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { banners.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            val next = (pagerState.currentPage + 1) % banners.size
            scope.launch { pagerState.animateScrollToPage(next) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        HorizontalPager(state = pagerState) { page ->
            val banner = banners[page]
            AsyncImage(
                model = banner.coverUrl,
                contentDescription = banner.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onClick(banner.targetId) }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    banner.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(banners.size) { index ->
                Box(
                    modifier = Modifier
                        .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (pagerState.currentPage == index)
                                com.fanhuatv.ui.theme.Accent
                            else Color.White.copy(alpha = 0.5f)
                        )
                )
            }
        }
    }
}

/** 继续观看一栏：横向海报 + 进度条，点一下回详情续播（数据来自观看历史里有进度没看完的） */
@Composable
internal fun ContinueWatchingRow(
    items: List<com.fanhuatv.core.storage.entity.HistoryEntity>,
    onClick: (String) -> Unit
) {
    Column {
        SectionTitle("继续观看")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items.size) { index ->
                val history = items[index]
                val fraction = if (history.duration > 0L)
                    (history.progress.toFloat() / history.duration).coerceIn(0f, 1f)
                else 0f
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .clickable { onClick(history.vodId) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        AsyncImage(
                            model = history.cover,
                            contentDescription = history.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        history.title,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (history.episode.isNotBlank()) {
                        Text(
                            history.episode,
                            color = com.fanhuatv.ui.theme.TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = com.fanhuatv.ui.theme.Accent,
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
