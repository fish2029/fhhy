package com.fanhuatv.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.fanhuatv.core.model.CategoryConfig
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.util.Resource
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.feature.home.BannerCarousel
import com.fanhuatv.navigation.AppViewModel
import com.fanhuatv.navigation.Routes
import com.fanhuatv.player.LivePlayBus
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.BgCard
import com.fanhuatv.ui.theme.TextPrimary
import com.fanhuatv.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 影视海报墙：订阅源全部影视铺满屏，顶部轮播大图，点海报进详情。
 * 数据全部来自订阅配置站点，不依赖任何账号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterWallScreen(
    config: com.fanhuatv.core.model.AppConfig?,
    navController: NavController,
    appVm: AppViewModel = hiltViewModel()
) {
    val categories = config?.categories.orEmpty()
    var selected by remember(categories) {
        mutableStateOf(categories.firstOrNull())
    }
    val banners = config?.banners.orEmpty()

    Scaffold(
        topBar = { SubTopBar("影视海报墙", onBack = { navController.popBackStack() }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 顶部轮播大图：与首页同一套数据，点图进详情
            if (banners.isNotEmpty()) {
                BannerCarousel(banners) { vodId ->
                    if (vodId.isNotBlank()) navController.navigate(Routes.detail(vodId))
                }
                Spacer(Modifier.height(8.dp))
            }
            // 分类栏：横向滚动，点谁换谁的内容
            if (categories.isNotEmpty()) {
                CategoryChips(categories, selected) { selected = it }
                Spacer(Modifier.height(4.dp))
            }
            if (selected == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("当前接口未提供分类", color = TextSecondary)
                }
            } else {
                PosterGrid(selected!!, appVm, navController)
            }
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<CategoryConfig>,
    selected: CategoryConfig?,
    onSelect: (CategoryConfig) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(categories) { _, c ->
            val picked = selected?.key == c.key && selected?.siteKey == c.siteKey
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (picked) Accent else BgCard)
                    .clickableNoRipple(onClick = { onSelect(c) })
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    c.name,
                    color = if (picked) Color.White else TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun PosterGrid(
    category: CategoryConfig,
    appVm: AppViewModel,
    navController: NavController
) {
    var items by remember(category.key, category.siteKey) { mutableStateOf<List<VodItem>>(emptyList()) }
    var page by remember(category.key, category.siteKey) { mutableIntStateOf(1) }
    var endReached by remember(category.key, category.siteKey) { mutableStateOf(false) }
    var loading by remember(category.key, category.siteKey) { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // 首屏 + 换分类：从第 1 页拉
    LaunchedEffect(category.key, category.siteKey) {
        loading = true
        val r = appVm.loadCategoryPage(category.key, "1", category.siteKey.removePrefix("py_"))
        if (r is Resource.Success) {
            items = r.data
            endReached = r.data.isEmpty()
        }
        loading = false
    }

    // 滚到接近底部自动加载下一页
    LaunchedEffect(gridState, items.size, endReached, loading) {
        if (endReached || loading) return@LaunchedEffect
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (items.isNotEmpty() && last >= items.size - 6) {
            loading = true
            val next = page + 1
            val r = appVm.loadCategoryPage(category.key, next.toString(), category.siteKey.removePrefix("py_"))
            if (r is Resource.Success) {
                val fresh = r.data.filter { n -> items.none { it.id == n.id && it.title == n.title } }
                if (fresh.isEmpty()) endReached = true else { items = items + fresh; page = next }
            } else endReached = true
            loading = false
        }
    }

    if (items.isEmpty() && loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("海报加载中…", color = TextSecondary)
        }
        return
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("该分类暂无内容", color = TextSecondary)
        }
        return
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id + it.title }) { item ->
            Column(
                modifier = Modifier.clickableNoRipple(onClick = {
                    LivePlayBus.rememberItems(listOf(item))
                    LivePlayBus.pendingDetail = item
                    navController.navigate(Routes.detail(item.id.ifBlank { item.title }))
                })
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(BgCard),
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(BgCard)
                    )
                    // 底部渐变 + 片名压图，海报墙质感
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                                )
                            )
                    )
                    Text(
                        item.displayTitle,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )
                }
            }
        }
        if (loading && items.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                    Text("加载中…", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}
