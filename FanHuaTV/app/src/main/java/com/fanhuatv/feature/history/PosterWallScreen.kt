package com.fanhuatv.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.feature.settings.SettingsViewModel
import com.fanhuatv.navigation.AppViewModel
import com.fanhuatv.navigation.Routes
import com.fanhuatv.player.LivePlayBus
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.BgCard
import com.fanhuatv.ui.theme.TextPrimary
import com.fanhuatv.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 影视海报墙：TMDB 热门影视铺满屏，官方高清海报 + 资料行。
 * 点海报拿片名去订阅源里匹配能播的版本，匹配到直接进详情。
 * TMDB Key 存设置项，页面内可直接填入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterWallScreen(
    navController: NavController,
    appVm: AppViewModel = hiltViewModel(),
    vm: SettingsViewModel = hiltViewModel()
) {
    val tmdbKey by vm.tmdbKey.collectAsState()

    Scaffold(
        topBar = { SubTopBar("影视海报墙", onBack = { navController.popBackStack() }) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (tmdbKey.isBlank()) {
                KeyGuide(vm, tmdbKey)
            } else {
                PosterWallContent(tmdbKey, appVm, navController)
            }
        }
    }
}

/** 无 Key：页面内引导 + 输入框，填完直接进海报墙 */
@Composable
private fun KeyGuide(vm: SettingsViewModel, currentKey: String) {
    var input by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("满屏海报随便逛", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "海报墙内容由 TMDB 提供，填入你的 API Key 即可使用。\n去 themoviedb.org 注册后，在 设置 → API 页面免费领取 Key。",
            color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            placeholder = { Text("粘贴 TMDB API Key", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { vm.saveTmdbKey(input.trim()) },
            enabled = input.trim().length >= 8,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text("保存并进入海报墙", fontSize = 15.sp)
        }
        if (currentKey.isBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("Key 只保存在你的手机里", color = TextSecondary, fontSize = 11.sp)
        }
    }
}

/** 有 Key：顶部 tab（热门电影/热门剧集）+ 海报网格 */
@Composable
private fun PosterWallContent(key: String, appVm: AppViewModel, navController: NavController) {
    var kind by remember { mutableStateOf("movie") }
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("movie" to "热门电影", "tv" to "热门剧集").forEach { (k, label) ->
                val picked = kind == k
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (picked) Accent else BgCard)
                        .clickableNoRipple(onClick = { kind = k })
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        label,
                        color = if (picked) Color.White else TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
        TmdbPosterGrid(key, kind, appVm, navController)
    }
}

/** TMDB 海报网格：3 列、2:3 海报、底部渐变压片名 + 评分，滚到底自动翻页 */
@Composable
private fun TmdbPosterGrid(
    key: String,
    kind: String,
    appVm: AppViewModel,
    navController: NavController
) {
    val scope = rememberCoroutineScope()
    var items by remember(kind) { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var page by remember(kind) { mutableIntStateOf(1) }
    var endReached by remember(kind) { mutableStateOf(false) }
    var loading by remember(kind) { mutableStateOf(false) }
    var matching by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    suspend fun fetch(p: Int): List<TmdbItem> = withContext(Dispatchers.IO) {
        TmdbClient.trending(kind, key, p)
    }

    // 首屏 + 换 tab：从第 1 页拉
    LaunchedEffect(kind, key) {
        loading = true
        val fresh = fetch(1)
        items = fresh
        endReached = fresh.isEmpty()
        page = 1
        loading = false
    }

    // 滚到接近底部自动加载下一页
    LaunchedEffect(gridState, items.size, endReached, loading) {
        if (endReached || loading) return@LaunchedEffect
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (items.isNotEmpty() && last >= items.size - 6) {
            loading = true
            val next = page + 1
            val fresh = fetch(next)
            if (fresh.isEmpty()) endReached = true else { items = items + fresh; page = next }
            loading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("海报加载中…", color = TextSecondary)
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("拿不到 TMDB 数据，检查网络或 Key 后重进", color = TextSecondary)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { kind + it.title + it.year }) { item ->
                    Column(
                        modifier = Modifier.clickableNoRipple(onClick = {
                            if (!matching) {
                                matching = true
                                appVm.matchLocalSource(item.title) { found ->
                                    matching = false
                                    if (found != null) {
                                        LivePlayBus.pendingDetail = found
                                        navController.navigate(Routes.detail(found.id.ifBlank { found.title }))
                                    }
                                }
                            }
                        })
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(14.dp))
                        ) {
                            AsyncImage(
                                model = item.poster,
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
                                item.title,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                            )
                            // 评分角标
                            if (item.rating.isNotBlank()) {
                                Text(
                                    "★${item.rating}",
                                    color = Color(0xFFFFC107),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                )
                            }
                        }
                        // 年份行
                        if (item.year.isNotBlank()) {
                            Text(
                                item.year,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
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
        // 匹配片源遮罩
        if (matching) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                Alignment.Center
            ) {
                Text("匹配片源中…", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}
