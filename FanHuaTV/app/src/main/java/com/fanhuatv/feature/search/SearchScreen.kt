package com.fanhuatv.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.feature.common.VodCard
import com.fanhuatv.navigation.Routes
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.AccentDeep
import com.fanhuatv.ui.theme.TextSecondary

/**
 * 搜索页：两段式。
 * ① 第一弹窗（点搜索框进入）：渐变背景 + 紫色胶囊搜索框 + 历史 + 热搜
 * ② 输入剧名提交后：左右双栏——左站点切换列表，右实时聚合海报墙
 */
@Composable
fun SearchScreen(
    config: AppConfig?,
    navController: NavController,
    vm: SearchViewModel = hiltViewModel()
) {
    var keyword by remember { mutableStateOf("") }
    var selectedSite by remember { mutableStateOf("") }   // 空 = 全部站点
    var phase by remember { mutableStateOf("input") }     // input=第一弹窗 result=结果页
    val items by vm.items.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val displayMode by vm.displayMode.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val sites = config?.sites.orEmpty().filter { it.searchable && it.api != "csp_Config" }

    fun startSearch(word: String) {
        if (word.isBlank()) return
        keyword = word
        selectedSite = ""   // 新搜索一律全站并发，站点过滤在结果页左栏动态选择
        phase = "result"
        vm.search(word, "")
    }

    if (phase == "input") {
        // ── ① 第一弹窗：渐变背景 + 紫色胶囊搜索框 + 历史 + 热搜 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFA9A3C8), Color(0xFF9FB4D8), Color(0xFFF0B8C4))
                    )
                )
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(10.dp))
            // 搜索框行：返回 + 紫色胶囊输入框 + 首页
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { startSearch(keyword) }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (keyword.isBlank()) {
                                    Text(
                                        "关键字…",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 13.sp
                                    )
                                }
                                inner()
                            }
                        )
                    }
                }
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Filled.Home, contentDescription = "首页", tint = Color.White)
                }
            }

            // ── 历史区 ──
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "历史",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    history.take(10).forEach { h ->
                        tagPill(h.keyword) { startSearch(h.keyword) }
                    }
                }
            }

            // ── 热搜区 ──
            Spacer(Modifier.height(18.dp))
            Text(
                "热搜",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hotWords.forEach { w ->
                    tagPill(w) { startSearch(w) }
                }
            }
        }
    } else {
        // ── ② 结果页：左右双栏——左站点列表，右实时聚合海报墙 ──
        Scaffold(
            topBar = {
                SubTopBar(keyword, onBack = { phase = "input" }, actions = {
                    TextButton(onClick = { vm.toggleDisplayMode() }) {
                        Text(if (displayMode == "thumb") "列表" else "缩略图")
                    }
                })
            }
        ) { padding ->
            Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                // 左栏只列出有命中结果的站点：没结果的静默隐藏，列表干净不误导。
                // 不用 remember：结果列表一变，角标数量必须立刻跟着变，
                // 否则会出现左侧显示命中数、右侧却说未找到的错位状态。
                val counts = items.groupingBy { it.siteKey }.eachCount()
                // 当前选中站变无结果（新搜索清空、该站被隐藏）时自动回退"全部"
                LaunchedEffect(items) {
                    if (selectedSite.isNotBlank() && counts[selectedSite] == null) selectedSite = ""
                }
                val siteChips = sites.distinctBy { it.key }.filter { counts[it.key] != null }.map { site ->
                    site.key to (site.name + " ${counts[site.key]}")
                }
                // 右栏结果按选中站点过滤（"全部"= 所有聚合结果）
                val filtered = if (selectedSite.isBlank()) items else items.filter { it.siteKey == selectedSite }
                val siteNameMap = sites.associate { it.key to it.name }

                // 左栏："全部" + 配置里的全部可搜站点
                Column(
                    modifier = Modifier
                        .width(112.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    siteChip("全部", selectedSite.isBlank()) { selectedSite = "" }
                    siteChips.forEach { (k, name) ->
                        siteChip(name, selectedSite == k) {
                            selectedSite = k
                        }
                    }
                }

                // 右栏：实时聚合结果（按选中站点过滤）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (filtered.isNotEmpty()) {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(if (displayMode == "thumb") 2 else 1),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalItemSpacing = 12.dp,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filtered) { item ->
                                ResultCard(
                                    item,
                                    // 站点中文名优先：自带站名 > 站点表 name > key，不露 csp_xxx
                                    item.siteName.ifBlank { siteNameMap[item.siteKey] ?: item.siteKey },
                                    navController
                                )
                            }
                        }
                        if (searching) {
                            Text(
                                statusText,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else if (searching) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(10.dp))
                                Text(statusText, color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    // 无结果静默：不显示任何提示文案
                }
            }
        }
    }
}

/** 第一弹窗的标签胶囊：紫色填充，点击发起搜索 */
@Composable
private fun tagPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 结果页左栏站点胶囊：默认紫色填充，选中深紫 + 浅紫描边，占满左栏宽度 */
@Composable
private fun siteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AccentDeep else Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 搜索结果卡：竖向长条——红色来源角标 + 封面(占位图) + 绿色状态角标 + 底部深色标题条 */
@Composable
private fun ResultCard(item: VodItem, sourceName: String, navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF20242E))
            .clickable {
                com.fanhuatv.player.LivePlayBus.pendingDetail = item
                com.fanhuatv.player.LivePlayBus.rememberItems(listOf(item))
                navController.navigate(Routes.detail(item.id.ifBlank { item.title }))
            }
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.displayTitle,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFF2A2F3A)),
                placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFF2A2F3A))
            )
            // 无海报时的占位图标（山+太阳样式）
            if (item.coverUrl.isBlank()) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.Image,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            }
            // 左上：红色来源角标（站点名）
            Surface(
                color = Color(0xFFE24B4A),
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text(
                    sourceName,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            // 左下：绿色资源状态角标（更新至第x集 / HD / 全集）
            if (item.displayRating.isNotBlank()) {
                Surface(
                    color = Color(0xFF3FA855),
                    shape = RoundedCornerShape(topEnd = 8.dp),
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    Text(
                        item.displayRating,
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
        // 底部深色标题条：白字居中
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16181D))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                item.displayTitle,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 内置热搜词（无热搜接口，用常见热门影视词兜底） */
private val hotWords = listOf(
    "战狼2", "熊出没", "狂飙", "庆余年", "三体", "流浪地球", "甄嬛传", "哪吒", "斗罗大陆", "新闻联播"
)
