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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
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
                color = com.fanhuatv.ui.theme.TextPrimary,
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
                color = com.fanhuatv.ui.theme.TextPrimary,
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
                    Text((status as Resource.Error).message, color = com.fanhuatv.ui.theme.TextPrimary)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { appVm.load() },
                        shape = com.fanhuatv.ui.theme.buttonShape(),
                        colors = com.fanhuatv.ui.theme.materialButtonColors(),
                        modifier = Modifier.then(com.fanhuatv.ui.theme.materialButtonBackground())
                    ) { Text("重试", color = com.fanhuatv.ui.theme.materialButtonTextColor()) }
                    TextButton(onClick = { navController.navigate(Routes.CONFIG) }) {
                        Text("去配置接口")
                    }
                }
            }
            is Resource.Success -> {
                // 「我的-导航首页」设置：大图横幅 / 海报密度 / 继续观看
                val uiSettings by appVm.uiSettings.collectAsStateWithLifecycle()
                // TMDB 模式：填了 Key 后，轮播大图和影片格子都走 TMDB 热门榜
                val settingsVm: com.fanhuatv.feature.settings.SettingsViewModel = hiltViewModel()
                val tmdbKey by settingsVm.tmdbKey.collectAsStateWithLifecycle()
                val tmdbItems = if (tmdbKey.isNotBlank()) rememberTmdbTrending(tmdbKey) else emptyList()
                var tmdbMatching by remember { mutableStateOf(false) }
                val tmdbMatch: (String) -> Unit = { title ->
                    if (!tmdbMatching) {
                        tmdbMatching = true
                        appVm.matchLocalSource(title) { found ->
                            tmdbMatching = false
                            if (found != null) {
                                com.fanhuatv.player.LivePlayBus.pendingDetail = found
                                navController.navigate(Routes.detail(found.id.ifBlank { found.title }))
                            }
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(72.dp)) // 给悬浮搜索栏留位

                    // 轮播 Banner：TMDB 模式走热门榜横图，否则用订阅源配置（都受「我的-导航首页-大图横幅」开关控制）
                    val banners = config?.banners.orEmpty()
                    if (uiSettings.homeBanner != false) {
                        if (tmdbKey.isNotBlank()) {
                            if (tmdbItems.isNotEmpty()) {
                                TmdbBanner(tmdbItems.take(5)) { tmdbMatch(it) }
                                Spacer(Modifier.height(8.dp))
                            }
                        } else if (banners.isNotEmpty()) {
                            BannerCarousel(banners) { vodId ->
                                if (vodId.isNotBlank()) navController.navigate(Routes.detail(vodId))
                            }
                            Spacer(Modifier.height(8.dp))
                        }
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
                    if (tmdbKey.isBlank() && homeItems.isEmpty() && config?.categories?.isNotEmpty() == true) {
                        Text(
                            "当前脚本未提供推荐影片，可点上方分类查看",
                            color = com.fanhuatv.ui.theme.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    com.fanhuatv.player.LivePlayBus.rememberItems(homeItems)
                    // TMDB 模式：格子走热门榜；否则用订阅源推荐
                    if (tmdbKey.isNotBlank()) {
                        BoxWithConstraints(modifier = Modifier.weight(1f).clipToBounds()) {
                            val gridColumns = (uiSettings.posterColumns ?: 3).coerceIn(2, 4)
                            TmdbGrid(tmdbItems, tmdbMatching, gridColumns, uiSettings.posterInfo != "title") { tmdbMatch(it) }
                            if (tmdbMatching) {
                                Box(
                                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                                    Alignment.Center
                                ) {
                                    Text("匹配片源中…", color = Color.White, fontSize = 15.sp)
                                }
                            }
                        }
                    } else {
                    // 网格占满首页剩余空间：两排卡片 + 行间距刚好占满，
                    // 卡片高度按剩余空间反算——不管轮播图开关、不管几列，
                    // 打开首页两排都完整显示，不出头、不被切
                    val gridColumns = (uiSettings.posterColumns ?: 3).coerceIn(2, 4)
                    BoxWithConstraints(modifier = Modifier.weight(1f).clipToBounds()) {
                        // 单张卡片完整高度 = (剩余高度 - 行间距10dp - 上下contentPadding12dp) / 2
                        val cardHeight = (maxHeight - 10.dp - 12.dp) / 2
                        // 封面高度 = 卡片高度 - 标题区（12sp 单行文字 + 5dp 上边距 + 余量）
                        val posterHeight = cardHeight - 24.dp
                        LazyVerticalGrid(
                            // 选几列显示几列（自适应算列在窄屏会少一列，已回退）
                            columns = GridCells.Fixed(gridColumns),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(homeItems) { item ->
                                VodCard(
                                    item = item,
                                    // 封面固定高度：宽度仍铺满列宽，图片自动裁切
                                    posterHeight = posterHeight,
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
                .clip(com.fanhuatv.ui.theme.buttonShape())
                .then(com.fanhuatv.ui.theme.buttonFill())
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = com.fanhuatv.ui.theme.buttonTextColor(),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    currentName,
                    color = com.fanhuatv.ui.theme.buttonTextColor(),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = com.fanhuatv.ui.theme.buttonTextColor()
                )
            }
        }

        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(com.fanhuatv.ui.theme.pageGradient())
                        .padding(16.dp)
                ) {
                    // 顶部渐变标题栏（同参考图的"搜索站源"胶囊）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(com.fanhuatv.ui.theme.buttonShape())
                            .background(
                                Brush.horizontalGradient(com.fanhuatv.ui.theme.AccentPill)
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("首页站源", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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

/** 弹窗内的胶囊描边按钮，选中高亮（跟随全局按钮样式与文字颜色） */
@Composable
private fun SitePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) com.fanhuatv.ui.theme.Accent else com.fanhuatv.ui.theme.Accent.copy(alpha = 0.45f)
    Surface(
        shape = com.fanhuatv.ui.theme.buttonShape(),
        color = com.fanhuatv.ui.theme.Accent.copy(alpha = 0.12f),
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
                color = if (selected) com.fanhuatv.ui.theme.Accent else com.fanhuatv.ui.theme.TextPrimary,
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
                        .clip(com.fanhuatv.ui.theme.buttonShape())
                        .then(com.fanhuatv.ui.theme.buttonFill())
                        .clickable { onSelect(category) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        category.name.ifBlank { "未命名" },
                        color = com.fanhuatv.ui.theme.buttonTextColor(),
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
            .clip(com.fanhuatv.ui.theme.buttonShape())
            .then(com.fanhuatv.ui.theme.buttonFill())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = com.fanhuatv.ui.theme.buttonTextColor())
            Spacer(Modifier.width(10.dp))
            Text("搜索影片、演员、导演", color = com.fanhuatv.ui.theme.buttonTextColor(), fontSize = 14.sp)
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
                    .height(80.dp)
                    // 底部渐变遮罩：图上文字压得住，低清图也显得有质感
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
            ) {
                Text(
                    banner.title,
                    color = com.fanhuatv.ui.theme.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                )
                if (banner.rating.isNotBlank()) {
                    Text(
                        "★ ${banner.rating}",
                        color = com.fanhuatv.ui.theme.TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                    )
                }
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
                            .clip(RoundedCornerShape(14.dp))
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
                        color = com.fanhuatv.ui.theme.TextPrimary,
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
