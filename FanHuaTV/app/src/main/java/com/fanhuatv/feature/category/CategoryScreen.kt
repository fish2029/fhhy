package com.fanhuatv.feature.category

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.util.Resource
import com.fanhuatv.feature.common.VodCard
import com.fanhuatv.feature.home.BannerCarousel
import com.fanhuatv.feature.home.FloatingSearchBar
import com.fanhuatv.feature.home.HomeSiteSwitcher
import com.fanhuatv.navigation.AppViewModel
import com.fanhuatv.navigation.CategoryBus
import com.fanhuatv.navigation.Routes
import com.fanhuatv.player.LivePlayBus

/** 首页分类胶囊带进来的锁定条件：只锁分类本身，不锁第几个位置。 */
private data class CategorySelection(
    val key: String = "",
    val name: String = "",
    val scriptId: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    config: AppConfig?,
    navController: NavController,
    appVm: AppViewModel,
    vm: CategoryViewModel = hiltViewModel()
) {
    val categories = config?.categories.orEmpty()
    var selectedIndex by remember { mutableIntStateOf(0) }
    val state by vm.state.collectAsStateWithLifecycle()
    val diagnostic by appVm.diagnostic.collectAsStateWithLifecycle()

    // 点哪个分类就锁哪个分类：把「名字 + tid + scriptId」一次性带进来。
    // 用 remember 在组合阶段同步消费，避免 LaunchedEffect 异步导致先落到第一个。
    val incoming = remember {
        CategorySelection(
            key = CategoryBus.consumeTid(),
            name = CategoryBus.consumeName(),
            scriptId = CategoryBus.consumeScriptId()
        )
    }
    var lock by remember { mutableStateOf(incoming) }
    var selectionReady by remember {
        mutableStateOf(incoming.name.isBlank() && incoming.key.isBlank())
    }

    // 分类列表就绪/变化时持续按锁定的分类定位；不清锁，
    // 防止后台 enrichment 刷新分类列表后又跳回第一个。
    LaunchedEffect(categories, lock) {
        if (categories.isEmpty()) return@LaunchedEffect
        val idx = when {
            lock.name.isNotBlank() -> categories.indexOfFirst { it.name.trim() == lock.name.trim() }
            lock.key.isNotBlank() -> categories.indexOfFirst { it.key == lock.key }
            else -> -1
        }
        if (idx >= 0) selectedIndex = idx
        if (selectedIndex !in categories.indices) selectedIndex = 0
        selectionReady = true
    }

    // 锁定的分类还没在列表里出现前不加载，避免先闪一下第一行的影片。
    LaunchedEffect(categories, selectedIndex, selectionReady) {
        if (!selectionReady) return@LaunchedEffect
        val category = categories.getOrNull(selectedIndex) ?: return@LaunchedEffect
        if (category.key.isBlank()) return@LaunchedEffect
        val scriptId = category.siteKey.removePrefix("py_").ifBlank { incoming.scriptId }
        vm.load(category.key, scriptId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // jar 配置项 action 执行提示：底部浮层，3 秒自动消失
        val actionToast by appVm.actionToast.collectAsStateWithLifecycle()
        if (actionToast.isNotBlank()) {
            LaunchedEffect(actionToast) {
                kotlinx.coroutines.delay(3000)
                appVm.consumeActionToast()
            }
            Text(
                actionToast,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(Color(0xCC3B2C6E))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(72.dp)) // 给悬浮站源+搜索栏留位

            // 第2排：Banner，与首页一致
            val banners = config?.banners.orEmpty()
            if (banners.isNotEmpty()) {
                BannerCarousel(banners) { vodId ->
                    if (vodId.isNotBlank()) navController.navigate(Routes.detail(vodId))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (categories.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无分类数据", color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        Text(diagnostic, color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { appVm.load() }) { Text("重新加载") }
                        TextButton(onClick = { navController.navigate(Routes.PY_SCRIPT) }) {
                            Text("去导入 PY 脚本", fontSize = 12.sp)
                        }
                    }
                }
            } else {
                // 第3排：分类Tab排
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    containerColor = com.fanhuatv.ui.theme.BgSurface,
                    contentColor = Color.White,
                    edgePadding = 8.dp,
                    indicator = { tabPositions ->
                        if (selectedIndex < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                color = com.fanhuatv.ui.theme.Accent
                            )
                        }
                    }
                ) {
                    categories.forEachIndexed { index, category ->
                        Tab(
                            selected = selectedIndex == index,
                            onClick = {
                                lock = CategorySelection()
                                selectedIndex = index
                            },
                            text = { Text(category.name) },
                            selectedContentColor = com.fanhuatv.ui.theme.Accent,
                            unselectedContentColor = com.fanhuatv.ui.theme.TextSecondary
                        )
                    }
                }

                when (val result = state) {
                    is Resource.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is Resource.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(result.message, color = com.fanhuatv.ui.theme.TextSecondary)
                    }
                    is Resource.Success -> {
                        val items = result.data.ifEmpty {
                            config?.homeItems?.filter {
                                it.category == categories.getOrNull(selectedIndex)?.name ||
                                        it.category == categories.getOrNull(selectedIndex)?.key
                            }.orEmpty()
                        }
                        if (items.isEmpty()) {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Text("该分类暂无内容", color = com.fanhuatv.ui.theme.TextSecondary)
                            }
                        } else {
                            LivePlayBus.rememberItems(items)
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(items) { item: VodItem ->
                                    VodCard(item = item, onClick = {
                                        // jar 配置项（带 action）：执行动作（弹扫码/输入框等），不进详情播放
                                        if (item.source == "jar" && item.action.isNotBlank()) {
                                            appVm.runJarAction(item)
                                        } else {
                                            LivePlayBus.pendingDetail = item
                                            navController.navigate(Routes.detail(item.id.ifBlank { item.title }))
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }

        // 第1排：悬浮站源切换 + 搜索，与首页一致
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeSiteSwitcher(appVm = appVm, config = config, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FloatingSearchBar(
                onClick = { navController.navigate(Routes.SEARCH) },
                modifier = Modifier.weight(2.2f)
            )
        }
    }
}
