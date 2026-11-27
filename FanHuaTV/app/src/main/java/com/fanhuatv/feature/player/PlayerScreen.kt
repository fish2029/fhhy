package com.fanhuatv.feature.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.fanhuatv.player.LivePlayBus
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.AccentDeep
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放器页：Media3 播放 + 实用手势控制 + OSD 功能条。
 *
 * 控制功能一览：
 * - 左 1/3 竖滑：调节亮度          右 1/3 竖滑：调节音量
 * - 中间竖滑：切换上一集/下一集     长按画面：2 倍速快进，松开恢复
 * - 顶栏「倍速」：0.5x ~ 3.0x 选择（rememberSpeed 开启时记忆）
 * - 顶栏「锁」：锁定控制防误触
 * - 底部 OSD 功能条：倍速 / 比例 / 循环 / 刷新 / 片头跳过 / 选集
 * - 播放失败时显示具体错误（网络/403/解码），不再纯黑屏
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    navController: NavController,
    vm: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val (url, title, item) = remember { LivePlayBus.consume() }
    val settings by vm.settings.collectAsStateWithLifecycle()
    var isFullscreen by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showEpisodeDialog by remember { mutableStateOf(false) }
    var gestureTip by remember { mutableStateOf("") }
    var currentSpeed by remember { mutableStateOf(1f) }
    var loopOn by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var currentUrl by remember { mutableStateOf(url) }

    // 弹幕：地址来自 jar 播放结果的 danmaku 字段（本地代理转发 jar 内弹幕服务）。
    // 默认关闭：网盘/冷门源根本没有弹幕，自动拉只会触发 jar 内「弹幕加载失败」
    // 的垃圾 toast；用户想开再手动打开，此时才真正去拉取
    var danmakuOn by remember { mutableStateOf(false) }
    val danmakuUrl = remember { LivePlayBus.pendingDanmaku }
    var danmakuList by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var danmakuIdx by remember { mutableStateOf(0) }
    var danmakuBullets by remember { mutableStateOf<List<DanmakuBullet>>(emptyList()) }
    var danmakuLane by remember { mutableStateOf(0) }
    var lastDanmakuPos by remember { mutableStateOf(0L) }

    // 歌手海报 / 歌词：来自 jar 播放结果的 artwork 与 lyric/lyrics/subs 字段（音乐/MV 类源才有）。
    // 与弹幕不同，这两个是「有就显示、没有就不占位」，所以不做默认值伪造
    var artwork by remember { mutableStateOf(LivePlayBus.pendingArtwork) }
    var lyricRaw by remember { mutableStateOf(LivePlayBus.pendingLyric) }
    LaunchedEffect(currentUrl) {
        // 起播时先取一次；同一地址内点「刷新」会重新解析，也可能带回新的海报/歌词，
        // 所以再轮询一小段，值有变化才覆盖（不变就不动，歌词不会被重建闪动）
        artwork = LivePlayBus.pendingArtwork
        lyricRaw = LivePlayBus.pendingLyric
        while (isActive) {
            delay(500)
            if (artwork != LivePlayBus.pendingArtwork) artwork = LivePlayBus.pendingArtwork
            if (lyricRaw != LivePlayBus.pendingLyric) lyricRaw = LivePlayBus.pendingLyric
        }
    }
    // 与电视版一致：jar 只给歌词没给海报时，拿影片封面当歌手海报
    val posterUrl = artwork.ifBlank {
        if (lyricRaw.isBlank()) "" else item?.coverUrl.orEmpty()
    }
    var lyricLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var lyricIdx by remember { mutableStateOf(-1) }
    LaunchedEffect(lyricRaw) {
        lyricLines = emptyList()
        lyricIdx = -1
        if (lyricRaw.isBlank()) return@LaunchedEffect
        // 地址形态两种：LRC 直链（下载原文）或直接就是 LRC 文本
        val text = if (lyricRaw.startsWith("http")) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(lyricRaw).openConnection()
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrDefault("")
            }
        } else lyricRaw
        if (text.isNotBlank()) lyricLines = parseLrc(text)
    }
    // 当前句跟随播放进度走；没有时间轴的歌词（lyricLines 为空）就不显示
    LaunchedEffect(lyricLines, currentUrl) {
        while (isActive && lyricLines.isNotEmpty()) {
            delay(300)
            val pos = runCatching { vm.engine.player?.currentPosition ?: 0L }.getOrDefault(0L)
            lyricIdx = lyricLines.indexOfLast { it.time <= pos }.coerceAtLeast(0)
        }
    }

    // 拉取弹幕：换播放地址时重新加载；jar 没给地址或服务没数据就保持空列表
    LaunchedEffect(danmakuUrl, danmakuOn) {
        danmakuList = emptyList()
        danmakuIdx = 0
        danmakuBullets = emptyList()
        if (danmakuUrl.isBlank() || !danmakuOn) return@LaunchedEffect
        val xml = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL(danmakuUrl).openConnection()
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrDefault("")
        }
        if (xml.isNotBlank()) danmakuList = parseDanmakuXml(xml)
    }

    // 弹幕发射：跟随播放进度触发；回退超过 3 秒时重新定位起点
    LaunchedEffect(danmakuList, currentUrl) {
        danmakuIdx = 0
        while (isActive) {
            delay(200)
            if (!danmakuOn || danmakuList.isEmpty()) continue
            val p = vm.engine.player ?: continue
            val pos = p.currentPosition
            if (pos < lastDanmakuPos - 3000) {
                danmakuIdx = danmakuList.indexOfFirst { it.first >= pos }.coerceAtLeast(0)
            }
            lastDanmakuPos = pos
            var fired = 0
            while (danmakuIdx < danmakuList.size && danmakuList[danmakuIdx].first <= pos && fired < 8) {
                val c = danmakuList[danmakuIdx]
                danmakuBullets = danmakuBullets + DanmakuBullet(System.currentTimeMillis(), c.second, danmakuLane % 4)
                danmakuLane++
                danmakuIdx++
                fired++
            }
        }
    }

    // 片头跳过：每次换 url 只自动跳一次；设置开关用 rememberUpdatedState 让监听读到最新值
    var skipIntroDone by remember { mutableStateOf(false) }
    LaunchedEffect(url) { skipIntroDone = false }
    val skipIntroEnabled by rememberUpdatedState(settings.skipIntro)
    val autoNextEnabled by rememberUpdatedState(settings.autoNext)

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val scope = rememberCoroutineScope()

    // 视频解码帧计数（由 AnalyticsListener 在视频渲染器启用时写入），供卡帧看门狗轮询
    var videoCounters by remember { mutableStateOf<DecoderCounters?>(null) }

    // 播放错误监听 + 片头自动跳过
    // LocalLifecycleOwner.current 是 @Composable 调用，必须在组合期读取，不能放进 DisposableEffect 块里
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val player = vm.engine.get()
        // 延迟确认：引擎自动重试需要时间，等 3 秒后仍停在 IDLE 才算真失败。
        // 协程醒来时播放页可能已经退出（onDispose 会 release 播放器）：
        // 必须先确认引擎里还是同一个播放器实例再读状态——已释放的播放器读状态
        // 会抛 IllegalStateException 直接闪退。v34 把 403/解析类报错推迟了 3 秒，
        // 用户更容易在这 3 秒内退出播放页，这个竞态窗口从罕见变成了常见路径
        // （爱听播放闪退的根因），这里统一收口防住。
        fun confirmLater(message: String) {
            scope.launch {
                delay(3000)
                if (vm.engine.player !== player) return@launch
                val state = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
                if (state == Player.STATE_IDLE && playError.isBlank()) {
                    playError = message
                }
            }
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 各分支必须真的写进 playError，否则出错时界面无声无息像黑屏
                playError = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                        // 引擎会自动换 UA 重试一次；这里延迟确认，
                        // 重试成功（回到 READY/BUFFERING）就不弹错误，重试失败（IDLE）才提示
                        confirmLater("网络连接失败，已自动重试仍无法播放，请检查网络或换站点")
                        ""
                    }
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                        // 引擎会换默认 UA 裸发重试一次；这里延迟确认，
                        // 重试成功（回到 READY/BUFFERING）就不弹错误，重试失败（IDLE）才提示
                        confirmLater("播放地址被服务器拒绝（403 等），该源可能需要特殊请求头")
                        ""
                    }
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                        // 把最后喂给播放器的地址片段直接显示出来，便于判断是哪一步解析歪了
                        val hint = vm.lastPlayedUrl.trim().takeLast(120)
                        confirmLater("播放失败：ERROR_CODE_IO_FILE_NOT_FOUND\n地址片段：$hint")
                        ""
                    }
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> "视频解码失败，格式不支持"
                    else -> {
                        // 解析类错误：引擎会自动换解析器重试一次；这里延迟确认，
                        // 重试成功（回到 READY/BUFFERING）就不弹错误，重试失败（IDLE）才提示
                        confirmLater("播放失败：${error.errorCodeName}")
                        ""
                    }
                }.takeIf { it.isNotBlank() } ?: playError
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && skipIntroEnabled && !skipIntroDone) {
                    if (player.currentPosition < 90_000 && player.duration > 100_000) {
                        player.seekTo(90_000)
                        gestureTip = "已跳过片头"
                    }
                    skipIntroDone = true
                }
                // 播放结束：自动连播开着就直接切下一集，不弹任何菜单
                if (state == Player.STATE_ENDED && autoNextEnabled) vm.switchEpisode(true)
            }
        }
        player.addListener(listener)
        // 断点续播：从历史进入时带上了上次的位置，播放器就绪后跳过去（用完即清零）
        if (LivePlayBus.pendingSeek > 0) {
            val seekTo = LivePlayBus.pendingSeek
            LivePlayBus.pendingSeek = 0L
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        runCatching { player.seekTo(seekTo) }
                        player.removeListener(this)
                    }
                }
            })
        }
        // 记录视频解码器帧计数器：看门狗据此判断"音频在走但视频帧不前进"的卡帧
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                videoCounters = decoderCounters
            }
        })
        // 进度落库：暂停或退出播放页时把 视频ID/集数/进度 写进本地数据库。
        // 直播（无 item）不记；读位置必须在 release 之前，已释放的播放器读状态会抛异常
        fun persistProgress() {
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            val dur = runCatching { player.duration }.getOrDefault(0L)
            val ep = item?.let { LivePlayBus.pendingTitle.removePrefix(it.displayTitle).trim() } ?: ""
            // episodeId 记这集的剧集 id（详情页 playableUrl）：续播时拿它重新解析新直链，
            // 播放地址多带时效 token，存下来的直链过会儿就失效
            vm.saveProgress(item, currentUrl, pos, dur, ep, episodeId = item?.playUrl.orEmpty())
        }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) persistProgress()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            persistProgress()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            vm.engine.release()
        }
    }

    // 手势辅助：亮度 / 音量
    fun adjustBrightness(delta: Float) {
        val window = activity?.window ?: return
        val attr = window.attributes
        val base = attr.screenBrightness.takeIf { it in 0.01f..1f } ?: 0.5f
        attr.screenBrightness = (base + delta).coerceIn(0.01f, 1f)
        window.attributes = attr
        gestureTip = "亮度 ${(attr.screenBrightness * 100).toInt()}%"
    }

    fun adjustVolume(delta: Float) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newV = (cur + (delta * max).toInt()).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
        gestureTip = "音量 ${(newV * 100 / max)}%"
    }

    // 提示自动消失
    LaunchedEffect(gestureTip) {
        if (gestureTip.isNotBlank()) {
            kotlinx.coroutines.delay(1200)
            gestureTip = ""
        }
    }

    // 画面比例 -> PlayerView resizeMode
    val resizeMode = when (settings.scaleMode) {
        "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM      // 铺满裁切
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL   // 拉伸填满
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT         // 适应
    }

    // 重新播放当前剧集（刷新）：走与选集相同的解析通道重新解析，
    // py/jar 的临时播放地址过期后能拿到新地址，而不是拿旧地址硬重播报错
    fun reload() {
        val raw = LivePlayBus.pendingUrl.ifBlank { url }
        if (raw.isBlank()) return
        playError = ""
        vm.playEpisodeResolved(item, raw, currentSpeed)
        gestureTip = "已刷新"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 竖屏：播放器贴顶 + 下方剧集信息/选集滚动区；横屏：纯播放器铺满
        Column(modifier = Modifier.fillMaxSize()) {
        // ── 播放器区域：竖屏固定 16:9 贴顶；横屏全屏铺满整屏，功能条贴屏幕底部 ──
        Box(
            modifier = if (isFullscreen) Modifier.fillMaxSize()
            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // 自定义控制条已覆盖进度/上下集/播放暂停，关闭内置控制器避免两套叠着
                        useController = false
                        player = vm.engine.get()
                    }.also { playerViewRef = it }
                },
                update = {
                    // 内置控制器保持关闭：自定义控制条已覆盖全部功能，
                    // 这里不能再打开 useController，否则两套进度条叠着显示
                    it.setResizeMode(resizeMode)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 视频画面尺寸：探测过且宽为 0 说明是纯音频（音乐封面大图只在纯音频铺满）
            var videoW by remember { mutableStateOf(0) }
            var videoH by remember { mutableStateOf(0) }
            var videoProbed by remember { mutableStateOf(false) }

            // 播放状态：进度/时长/在播/集索引/拖动暂存（控制条隐藏时也照常轮询）
            var posMs by remember { mutableStateOf(0L) }
            var durMs by remember { mutableStateOf(0L) }
            var playingNow by remember { mutableStateOf(true) }
            var epIndex by remember { mutableStateOf(-1) }
            var scrubMs by remember { mutableStateOf<Long?>(null) }

            // 控制条显隐：点画面切换；播放中 4 秒无操作自动隐藏，暂停时常驻
            var controlsVisible by remember { mutableStateOf(true) }
            LaunchedEffect(controlsVisible, scrubMs) {
                if (!controlsVisible) return@LaunchedEffect
                delay(4000)
                // 暂停时不收；正在拖进度条时不收（scrubMs 变化会重启计时）
                if (vm.engine.player?.isPlaying == true && scrubMs == null) controlsVisible = false
            }

            // 手势层（未锁定时生效）：左右竖滑亮度/音量，中间竖滑切换剧集，长按 2 倍速
            if (!locked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            var zone = 0
                            var accum = 0f
                            detectVerticalDragGestures(
                                onDragStart = { off ->
                                    val w = size.width.toFloat()
                                    zone = when {
                                        off.x < w / 3f -> 1
                                        off.x > w * 2f / 3f -> 2
                                        else -> 3
                                    }
                                    accum = 0f
                                },
                                onVerticalDrag = { change, drag ->
                                    change.consume()
                                    when (zone) {
                                        1 -> adjustBrightness(-drag / 600f)
                                        2 -> adjustVolume(-drag / 500f)
                                        else -> {
                                            accum += drag
                                            if (kotlin.math.abs(accum) > 50f) {
                                                vm.switchEpisode(accum < 0)
                                                accum = 0f
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onLongPress = {
                                    currentSpeed = settings.speed
                                    vm.engine.setSpeed(2f)
                                    gestureTip = "2x 快进中…"
                                },
                                onPress = { down ->
                                    // 长按结束（手指抬起）恢复原速：awaitPointerEventScope 手动等 release，
                                    // 兼容所有 Compose 版本（不依赖 PressGestureScope.tryAwaitRelease）
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.none { it.pressed }) break
                                        }
                                    }
                                    if (gestureTip == "2x 快进中…") {
                                        vm.engine.setSpeed(currentSpeed)
                                        gestureTip = ""
                                    }
                                }
                            )
                        }
                )
            } else {
                // 锁定层：吞掉画面区域的触摸，防误触
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { }
                )
            }

            // 弹幕层：画面上部滚动评论，不响应触摸、不挡手势
            if (danmakuOn && danmakuBullets.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    danmakuBullets.forEach { b ->
                        DanmakuBulletView(text = b.text, lane = b.lane) {
                            danmakuBullets = danmakuBullets.filterNot { it.id == b.id }
                        }
                    }
                }
            }

            // 音乐大图：纯音频（探测不到视频画面）时封面铺满整个播放画面，像 MV 一样
            if (posterUrl.isNotBlank() && videoProbed && videoW <= 0) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 音乐歌词：当字幕叠在画面上（纯音频时叠在封面大图上），进度条上方居中
            if (lyricLines.isNotEmpty()) {
                val curIdx = if (lyricIdx < 0) 0 else lyricIdx
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 132.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        lyricLines.getOrNull(curIdx)?.text ?: "",
                        color = com.fanhuatv.ui.theme.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 25.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.8f),
                                blurRadius = 10f
                            )
                        )
                    )
                    val nextLine = lyricLines.getOrNull(curIdx + 1)
                    if (nextLine != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            nextLine.text,
                            color = com.fanhuatv.ui.theme.TextPrimary.copy(alpha = 0.75f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = TextStyle(
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.8f),
                                    blurRadius = 8f
                                )
                            )
                        )
                    }
                }
            }

            // ── 底部控制区：快进快退进度条 + 上一集/播放/下一集 + OSD 功能条 ──
            // 播放状态轮询：进度、时长、是否在播、当前集索引（控制条收起也照常跑）
            if (!locked) {
                LaunchedEffect(currentUrl) {
                    while (isActive) {
                        vm.engine.player?.let { p ->
                            posMs = p.currentPosition.coerceAtLeast(0L)
                            durMs = p.duration.takeIf { it > 0 } ?: 0L
                            playingNow = p.isPlaying
                            val vs = p.videoSize
                            videoW = vs.width
                            videoH = vs.height
                            videoProbed = true
                        }
                        LivePlayBus.pendingItem?.episodes?.let { eps ->
                            epIndex = eps.indexOfFirst { it.playableUrl == LivePlayBus.pendingUrl }
                        } ?: run { epIndex = -1 }
                        delay(500)
                    }
                }
            }

            // 控制面板：点画面切换显隐，播放中 4 秒无操作自动收起
            if (!locked && controlsVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // 快退 / 播放暂停 / 快进：最上面一行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            vm.engine.get().seekTo((posMs - 15_000L).coerceAtLeast(0L))
                        }) {
                            Icon(
                                Icons.Filled.FastRewind,
                                contentDescription = "快退15秒",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = {
                            vm.engine.get().let { if (it.isPlaying) it.pause() else it.play() }
                        }) {
                            Icon(
                                if (playingNow) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playingNow) "暂停" else "播放",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = {
                            vm.engine.get().let { p ->
                                val limit = if (durMs > 0) durMs else posMs + 15_000L
                                p.seekTo((posMs + 15_000L).coerceAtMost(limit))
                            }
                        }) {
                            Icon(
                                Icons.Filled.FastForward,
                                contentDescription = "快进15秒",
                                tint = Color.White
                            )
                        }
                    }
                    // 当前分辨率：起播后自动显示画面尺寸，取不到就不占位
                    if (videoW > 0 && videoH > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                "$videoW x $videoH",
                                color = com.fanhuatv.ui.theme.TextPrimary.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(end = 8.dp, bottom = 2.dp)
                            )
                        }
                    }
                    // 进度条：拖动快进快退，两端显示时间（中间一行）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTime(posMs),
                            color = com.fanhuatv.ui.theme.TextPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Slider(
                            value = (scrubMs ?: posMs).toFloat()
                                .coerceIn(0f, (if (durMs > 0) durMs else 1L).toFloat()),
                            // 不设 valueRange 时默认 0..1，毫秒值会被钳到 1f 导致进度条未播就满格
                            valueRange = 0f..durMs.toFloat().coerceAtLeast(1f),
                            onValueChange = { scrubMs = it.toLong() },
                            onValueChangeFinished = {
                                scrubMs?.let { vm.engine.get().seekTo(it) }
                                scrubMs = null
                            },
                            enabled = durMs > 0,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                                .height(26.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF9B6BFF),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Text(
                            formatTime(durMs),
                            color = com.fanhuatv.ui.theme.TextPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    // OSD 功能条：左右两组贴边排布，参考主流播放器布局
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            osdButton("上一集") {
                                vm.switchEpisode(false)
                            }
                            osdButton("下一集") {
                                vm.switchEpisode(true)
                            }
                            osdButton("倍速") { showSpeedDialog = true }
                            osdButton("比例·${when (settings.scaleMode) { "fill" -> "铺满"; "stretch" -> "拉伸"; else -> "适应" }}") {
                                val next = when (settings.scaleMode) { "fit" -> "fill"; "fill" -> "stretch"; else -> "fit" }
                                vm.applyScaleMode(next)
                            }
                            osdButton(if (loopOn) "循环·开" else "循环·关") {
                                loopOn = !loopOn
                                vm.engine.get().repeatMode =
                                    if (loopOn) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                                gestureTip = if (loopOn) "单集循环已开启" else "单集循环已关闭"
                            }
                            osdButton("刷新") { reload() }
                            osdButton(if (settings.skipIntro) "片头·开" else "片头·关") {
                                vm.applySkipIntro(!settings.skipIntro)
                                gestureTip = if (!settings.skipIntro) "跳过片头 90 秒已开启" else "跳过片头已关闭"
                            }
                            osdButton(if (settings.autoNext) "片尾·开" else "片尾·关") {
                                vm.applyAutoNext(!settings.autoNext)
                                gestureTip = if (!settings.autoNext) "播完自动下一集已开启" else "播完自动下一集已关闭"
                            }
                            osdButton(if (danmakuOn) "弹幕·开" else "弹幕·关") {
                                danmakuOn = !danmakuOn
                                if (!danmakuOn) danmakuBullets = emptyList()
                                gestureTip = when {
                                    danmakuUrl.isBlank() -> "当前源没有弹幕"
                                    danmakuOn -> "弹幕已开启"
                                    else -> "弹幕已关闭"
                                }
                            }
                        }
                        osdButton("选集") { showEpisodeDialog = true }
                    }
                }
            }
        }

        // ── 竖屏内容区：照参考图复刻——渐变背景 + 两栏信息区 + 简介 + 选集网格 ──
        if (!isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFA9A3C8), Color(0xFF9FB4D8), Color(0xFF4FB3C2))
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                // 两栏信息区：左海报（圆角+淡紫描边）+ 右文字。
                // 海报高度跟随右侧文字块实测高度：顶边天然齐平，底边正好落在
                // 「评分」那一行的下沿（用户指定对齐方式），不再固定死 124dp
                var infoHeight by remember { mutableStateOf(124.dp) }
                val density = LocalDensity.current
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .width(84.dp)
                            .height(infoHeight)
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.5.dp, Color(0xFFC9B8FF), RoundedCornerShape(9.dp))
                    ) {
                        AsyncImage(
                            model = item?.coverUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .onSizeChanged { size ->
                                infoHeight = with(density) { size.height.toDp() }
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                color = com.fanhuatv.ui.theme.TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (item?.episodes.orEmpty().isNotEmpty()) {
                                Text(
                                    " [全${item?.episodes?.size ?: 0}集]",
                                    color = com.fanhuatv.ui.theme.TextPrimary.copy(alpha = 0.85f),
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        InfoLine("年份", item?.year.orEmpty())
                        if (!item?.director.isNullOrBlank()) InfoLine("导演", item?.director.orEmpty())
                        if (!item?.actor.isNullOrBlank()) {
                            Text(
                                "演员：${item?.actor.orEmpty()}",
                                color = com.fanhuatv.ui.theme.TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        InfoLine("评分", item?.displayRating?.takeIf { it.isNotBlank() } ?: "暂无")
                    }
                }

                // 简介段落（可展开）
                if (!item?.desc.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        item?.desc.orEmpty(),
                        color = com.fanhuatv.ui.theme.TextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }

                // 歌词/海报已移到播放画面内（进度条上方），详情区不再重复显示

                // 多线路分组：与详情页同款——jar 详情的线路按 $$$ 对应，整条失效的线路必须能切；
                // 单线路不显示切换行。切线路只过滤选集，不自动开播。
                // 线路行放在「选集」标题上方（用户指定位置）
                val allEps = item?.episodes.orEmpty()
                val lines = allEps.map { it.playFrom.ifBlank { "默认线路" } }.distinct()
                var selectedLine by remember(item?.id, lines.size) { mutableStateOf(lines.firstOrNull()) }
                val eps = if (lines.size > 1) {
                    allEps.filter { it.playFrom.ifBlank { "默认线路" } == selectedLine }
                } else allEps

                if (lines.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    // 与「选集」同款分区标题：上面「线路」、下面具体线路
                    Text("线路", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    // 一排三个等宽胶囊：每行重量均分，来多少条线路都整齐；
                    // 单条名字再长也只在本格内省略，不挤压别人、不折成 2+1
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lines.chunked(3).forEach { rowLines ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowLines.forEach { line ->
                                    // 线路名常带"（点击换线）（勿信广告）"这类括号废话，
                                    // 全角/半角括号一并剥掉只留主干，切换仍按完整名匹配
                                    val shortLine = line.substringBefore("（")
                                        .substringBefore("(").trim().ifBlank { line }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (line == selectedLine) AccentDeep else Accent)
                                            .clickable { selectedLine = line }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            shortLine,
                                            color = Color.Black,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 选集标题行
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "共${item?.episodes.orEmpty().size}集",
                        color = com.fanhuatv.ui.theme.TextPrimary.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))

                // 选集网格：4 列，全局胶囊样式——紫色填充，选中加深一档
                if (eps.isEmpty()) {
                    Text("当前影片没有剧集列表", color = com.fanhuatv.ui.theme.TextPrimary.copy(alpha = 0.6f), fontSize = 13.sp)
                } else {
                    // 只显示前四排（4×42 + 3×10 = 198dp），最后一排贴边；更多集数在区域内下滑查看
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(198.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        eps.chunked(4).forEach { rowEps ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowEps.forEach { ep ->
                                    val selected = currentUrl == ep.playableUrl
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (selected) AccentDeep else Accent)
                                            .clickable {
                                                currentUrl = ep.playableUrl
                                                LivePlayBus.play(ep.playableUrl, "${title} ${ep.name}", item)
                                                // py/jar 的剧集地址必须先解析成真实视频流，直接播会报 FILE_NOT_FOUND
                                                vm.playEpisodeResolved(item, ep.playableUrl, currentSpeed, ep.playFrom)
                                                playError = ""
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            ep.name.ifBlank { "播放" },
                                            color = com.fanhuatv.ui.theme.TextPrimary,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                                repeat(4 - rowEps.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        } // Column 结束

        LaunchedEffect(url) {
            if (url.isNotBlank()) {
                playError = ""
                currentSpeed = if (settings.rememberSpeed) settings.speed else 1f
                // 先经 UrlRules 校验再播（playDirect 内部统一拦截）；
                // 返回非 null = 地址不合法或探测发现网页内容等播放前错误，直接提示
                vm.playDirect(url, currentSpeed)?.let { playError = it }
            }
        }

        // 顶部标题栏（锁定时只显示解锁按钮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!locked) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(title, color = com.fanhuatv.ui.theme.TextPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSpeedDialog = true }) {
                    Text("${currentSpeed}x", color = com.fanhuatv.ui.theme.TextPrimary, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = {
                    isFullscreen = !isFullscreen
                    activity?.requestedOrientation = if (isFullscreen)
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }) {
                    Icon(Icons.Filled.Fullscreen, contentDescription = "全屏", tint = Color.White)
                }
                IconButton(onClick = { locked = true }) {
                    Icon(Icons.Filled.LockOpen, contentDescription = "锁定", tint = Color.White)
                }
            } else {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    locked = false
                    playerViewRef?.useController = false
                }) {
                    Icon(Icons.Filled.Lock, contentDescription = "解锁", tint = Color.White)
                }
            }
        }

        // 手势提示浮层
        if (gestureTip.isNotBlank() && !locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(gestureTip, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 14.sp)
            }
        }

        // 播放错误浮层
        if (playError.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.85f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(playError, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 14.sp)
            }
        }

        // 选集解析中 / 解析失败提示（与播放错误互斥展示，避免叠字）
        val resolveErrMsg by vm.resolveError.collectAsStateWithLifecycle()
        val resolvingNow by vm.resolving.collectAsStateWithLifecycle()
        if (playError.isBlank() && resolvingNow) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text("正在解析播放地址…", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 14.sp)
            }
        } else if (playError.isBlank() && resolveErrMsg.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.85f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(resolveErrMsg, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 14.sp)
            }
        }
    }

    // 倍速选择弹窗
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("播放倍速") },
            text = {
                Column {
                    speeds.forEach { s ->
                        TextButton(
                            onClick = {
                                currentSpeed = s
                                vm.applySpeed(s)
                                showSpeedDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${s}x" + if (s == currentSpeed) "  ✓" else "",
                                color = if (s == currentSpeed) MaterialTheme.colorScheme.primary else com.fanhuatv.ui.theme.TextPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // 选集弹窗：播放器内直接切集（与页面选集网格同款胶囊样式）
    if (showEpisodeDialog) {
        val dialogAll = item?.episodes.orEmpty()
        val dialogLines = dialogAll.map { it.playFrom.ifBlank { "默认线路" } }.distinct()
        var dialogLine by remember(item?.id, dialogLines.size) { mutableStateOf(dialogLines.firstOrNull()) }
        val dialogEps = if (dialogLines.size > 1) {
            dialogAll.filter { it.playFrom.ifBlank { "默认线路" } == dialogLine }
        } else dialogAll
        AlertDialog(
            onDismissRequest = { showEpisodeDialog = false },
            title = { Text("选集") },
            text = {
                if (dialogAll.isEmpty()) {
                    Text("当前影片没有剧集列表")
                } else {
                    Column {
                        // 多线路时先选线路，再选集：与页面线路区同款——一排三个等宽
                        if (dialogLines.size > 1) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                dialogLines.chunked(3).forEach { rowLines ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        rowLines.forEach { line ->
                                            val shortLine = line.substringBefore("（")
                                                .substringBefore("(").trim().ifBlank { line }
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(if (line == dialogLine) AccentDeep else Accent)
                                                    .clickable { dialogLine = line }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    shortLine,
                                                    color = Color.Black,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        // 与选集网格一致：4 列紫色胶囊，选中加深加粗，可滚动
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 正好容纳 4 整排（4×42 + 3×10 = 198），最后一排贴底边，不露半排
                                .height(198.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            dialogEps.chunked(4).forEach { rowEps ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowEps.forEach { ep ->
                                    val selected = currentUrl == ep.playableUrl
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (selected) AccentDeep else Accent)
                                            .clickable {
                                                // 与选集网格完全一致：更新 currentUrl + LivePlayBus + 解析播放
                                                currentUrl = ep.playableUrl
                                                LivePlayBus.play(ep.playableUrl, "${title} ${ep.name}", item)
                                                vm.playEpisodeResolved(item, ep.playableUrl, currentSpeed, ep.playFrom)
                                                playError = ""
                                                showEpisodeDialog = false
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            ep.name.ifBlank { "播放" },
                                            color = com.fanhuatv.ui.theme.TextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                repeat(4 - rowEps.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    } // 包裹 Column 结束
                }
            },
            confirmButton = {}
        )
    }
}

/** 信息区行：年份：xx / 导演：xx / 评分：xx，值为空整行不显示 */
@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Text(
        "$label：$value",
        color = com.fanhuatv.ui.theme.TextPrimary,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    )
}

/** OSD 功能条按钮：紧凑文字按钮 */
@Composable
private fun osdButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(label, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 12.sp)
    }
}

/** 毫秒 → mm:ss（超过 1 小时用 h:mm:ss） */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 弹幕单条：id 用于动画结束移除，lane 决定滚动行 */
private data class DanmakuBullet(val id: Long, val text: String, val lane: Int)

/** 解析 Bilibili 格式弹幕 XML：<d p="时间,模式,字号,颜色,...">内容</d>，按出现时间排序 */
private fun parseDanmakuXml(xml: String): List<Pair<Long, String>> {
    return runCatching {
        Regex("<d\\s+p=\"([^\"]+)\"[^>]*>(.*?)</d>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .mapNotNull { m ->
                val sec = m.groupValues[1].substringBefore(',').toDoubleOrNull() ?: return@mapNotNull null
                val text = m.groupValues[2]
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&#39;", "'").replace("&amp;", "&")
                    .replace(Regex("<[^>]*>"), "")
                    .trim()
                if (text.isEmpty()) null else ((sec * 1000).toLong() to text)
            }
            .sortedBy { it.first }
            .toList()
    }.getOrDefault(emptyList())
}

/** 单句歌词：time 为出现时间（毫秒） */
private data class LrcLine(val time: Long, val text: String)

/**
 * 解析 LRC 文本：[mm:ss.xx] 时间标签开头的一句；同一句带多个时间只留最早那条，
 * 按时间排序去重。没有时间轴的纯文本歌词无法跟播，直接返回空列表（不显示）。
 */
private fun parseLrc(text: String): List<LrcLine> {
    return runCatching {
        val lines = mutableListOf<LrcLine>()
        Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
            .findAll(text)
            .forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].ifBlank { "0" }.padEnd(3, '0').take(3).toLong()
                val time = (min * 60 + sec) * 1000 + frac
                val rest = text.substring(m.range.last + 1)
                val end = rest.indexOf('\n').let { if (it < 0) rest.length else it }
                val line = rest.substring(0, end).trim().trimEnd('\r')
                if (line.isNotEmpty()) lines.add(LrcLine(time, line))
            }
        lines.sortedBy { it.time }.distinctBy { it.time to it.text }
    }.getOrDefault(emptyList())
}

/** 单条滚动弹幕：从右侧飘到左侧约 9 秒，结束后回调移除 */
@Composable
private fun DanmakuBulletView(text: String, lane: Int, onDone: () -> Unit) {
    val density = LocalDensity.current
    val screenWPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val textWPx = with(density) { (text.length * 15).dp.toPx() }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        progress.animateTo(0f, tween(durationMillis = 9000, easing = LinearEasing))
        onDone()
    }
    Text(
        text = text,
        color = com.fanhuatv.ui.theme.TextPrimary,
        fontSize = 14.sp,
        maxLines = 1,
        style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, blurRadius = 8f)),
        modifier = Modifier.offset {
            val x = screenWPx * progress.value - textWPx * (1 - progress.value)
            IntOffset(x.toInt(), with(density) { (4 + lane * 26).dp.roundToPx() })
        }
    )
}
