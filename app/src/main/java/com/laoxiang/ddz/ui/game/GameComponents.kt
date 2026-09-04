package com.laoxiang.ddz.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.Card
import com.laoxiang.ddz.data.Deck
import com.laoxiang.ddz.data.LastActionType
import com.laoxiang.ddz.data.SeatView
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.common.AvatarImageRes
import com.laoxiang.ddz.ui.theme.*

// ------------------------------------------------ 牌面资源

/** 卡牌图宽高比（500x726） */
const val CARD_RATIO = 0.689f

private val cardResCache = HashMap<String, Int>()

fun cardRes(card: Card, context: android.content.Context): Int {
    val name = Deck.resName(card)
    return cardResCache.getOrPut(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}

/** 单张牌（badge=左上角标如「级」；warm=主牌暖色高亮+星标） */
@Composable
fun PokerCard(
    card: Card,
    width: Dp,
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    badge: String? = null,
    badgeColor: Color = Color(0xFF2E7D32),
    warm: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val res = remember(card.id) { cardRes(card, ctx) }
    val lift by animateFloatAsState(
        targetValue = if (raised) 1f else 0f,
        animationSpec = tween(120),
        label = "lift"
    )
    val shape = RoundedCornerShape(width / 8)
    Box(
        modifier
            .width(width)
            .aspectRatio(CARD_RATIO)
            .graphicsLayer {
                translationY = -lift * width.toPx() * 0.28f
                shadowElevation = if (raised) 12f else 4f
            }
            .clip(shape)
            .background(Color.White)
            .border(0.5.dp, Color(0x338D6E63), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        if (res != 0) {
            Image(
                painter = painterResource(res),
                contentDescription = card.displayLabel,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    card.displayLabel,
                    color = if (card.suit.isRed) ChineseRed else Ink,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }
        }
        // 主牌暖色高亮 + 星标（升级等游戏用）
        if (warm) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x30FFD54F))
            )
            Text(
                "★",
                fontSize = (width.value * 0.16f).coerceAtMost(13f).sp,
                color = Color(0xFFFFB300),
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 2.dp)
            )
        }
        // 角标（掼蛋/升级级牌「级」等）
        badge?.let { b ->
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-2).dp, y = (-2).dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(badgeColor)
                    .border(0.5.dp, Color(0x66FFFFFF), RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp, vertical = 0.5.dp)
            ) {
                Text(b, fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** 牌背 */
@Composable
fun CardBack(width: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.card_back),
        contentDescription = "牌背",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(width / 8))
            .border(0.5.dp, Color(0x33600000), RoundedCornerShape(width / 8))
    )
}

/** 出牌区：一排小牌（宽度自适应，牌再多也不超出可用宽度） */
@Composable
fun PlayedCards(
    cards: List<Card>,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    overlap: Boolean = true
) {
    if (cards.isEmpty()) return
    BoxWithConstraints(modifier) {
        val n = cards.size
        val w: Dp
        val spacing: Dp
        if (overlap) {
            // 每张露出 58%，相邻重叠 42%（spacedBy 负间距语义：step = w*(1-frac)）
            val visible = 0.58f
            val k = 1f + (n - 1) * visible
            w = cardWidth.coerceAtMost(maxWidth / k)
            spacing = -(w * (1f - visible))
        } else {
            w = cardWidth.coerceAtMost((maxWidth - 3.dp * (n - 1)) / n)
            spacing = 3.dp
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            cards.forEach { c ->
                PokerCard(c, w)
            }
        }
    }
}

// ------------------------------------------------ 座位块

/** 对手信息块（对标欢乐斗地主：头像 + 剩牌数蓝块并排，名字在下，含地主帽/聊天气泡） */
@Composable
fun OpponentBlock(
    seat: SeatView,
    showCards: Boolean,
    chatText: String?,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 26.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 聊天气泡
        chatText?.let { t ->
            Bubble(t)
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AvatarImageRes(
                    // 非地主=默认老乡头像；当地主后自动换成富翁地主头像
                    tableAvatarRes(seat), 50.dp,
                    Modifier
                        .border(
                            2.5.dp,
                            when {
                                seat.isTurn -> Color(0xFFFFC107)
                                seat.isLandlord -> Gold
                                else -> Color(0x66FFFFFF)
                            }, CircleShape
                        )
                )
                if (seat.isLandlord) {
                    // 地主小帽标
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-9).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFD4A24E))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("地主", fontSize = 9.sp, color = Color(0xFF4E2600), fontWeight = FontWeight.Black)
                    }
                }
            }
            if (showCards) {
                Spacer(Modifier.width(7.dp))
                CountTile(seat.handCount)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            seat.name,
            fontSize = 11.sp,
            color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
            maxLines = 1
        )
    }
}

/** 剩牌数蓝块（对标欢乐斗地主的「17」方块） */
@Composable
private fun CountTile(n: Int) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .shadow(3.dp, shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF4E7CD0), Color(0xFF2E55A0)))
            )
            .border(1.dp, Color(0x88FFFFFF), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            "$n",
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
    }
}

/** 聊天气泡 */
@Composable
fun Bubble(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2FFFFFF))
            .border(1.dp, Gold, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 12.sp, color = Ink, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** "要不起" / "不叫" 状态 */
@Composable
fun PassTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x995D4037))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = Color(0xFFFFE0B2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------ 记牌器

private val RANK_LABELS = mapOf(
    3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8", 9 to "9", 10 to "10",
    11 to "J", 12 to "Q", 13 to "K", 14 to "A", 15 to "2", 16 to "王", 17 to "王"
)

/** 记牌器面板 */
@Composable
fun CardCounterPanel(
    counts: Map<Int, Int>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB3163A6E))
            .border(1.dp, Color(0x66A8C8F0), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Text("记牌器（外界剩余）", fontSize = 10.sp, color = Color(0xCCD7E7FA))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (3..15).forEach { r ->
                val left = counts[r] ?: 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        RANK_LABELS[r] ?: "",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (left > 0) Color(0xFFFFE082) else Color(0x55FFE082)
                    )
                    Text(
                        "$left",
                        fontSize = 9.sp,
                        color = if (left > 0) Color.White else Color(0x55FFFFFF)
                    )
                }
            }
            val jokers = (counts[16] ?: 0) + (counts[17] ?: 0)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("王", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFE082))
                Text("$jokers", fontSize = 9.sp, color = Color.White)
            }
        }
    }
}

// ------------------------------------------------ 快捷聊天

val CHAT_PHRASES = listOf(
    "快点吧，我等到花儿都谢了！",
    "大你！",
    "你的牌打得太好了！",
    "不要走，决战到天亮！",
    "大家好，很高兴见到各位老乡~",
    "炸得漂亮！",
    "顶住，农民兄弟！",
    "咋又是我当地主…"
)

/** 快捷聊天面板 */
@Composable
fun QuickChatPanel(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(Color(0xF2FFF3E0))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("快捷喊话", fontWeight = FontWeight.Bold, color = DeepRed)
            TextButton(onClick = onDismiss) { Text("收起", color = DeepRed) }
        }
        CHAT_PHRASES.chunked(2).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { phrase ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFEDD9B8))
                            .border(1.dp, Color(0xFFC9A25E), RoundedCornerShape(10.dp))
                            .clickable {
                                onSend(phrase)
                                onDismiss()
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(phrase, fontSize = 13.sp, color = Ink, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ------------------------------------------------ 手牌行（斗地主/跑得快共用）

/**
 * 手牌行：居中紧凑（不铺满，两侧留白），高度驱动大卡 + 自适应重叠。
 * 选牌手势 = 单击选/取消 + 按住横向滑动多选（扫过的牌全部加入选中）。
 */
@Composable
internal fun HandRow(
    hand: List<com.laoxiang.ddz.data.Card>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onSweep: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val n = hand.size.coerceAtLeast(1)
        val screenH = config.screenHeightDp.dp
        // 目标卡宽：横屏 = 30% 屏高（按卡牌比例换算）
        val w0 = screenH * 0.30f * CARD_RATIO
        // 紧凑居中：可用宽度先收 18%，牌少时也不会摊满整行
        val avail = (maxWidth - 2.dp) * 0.82f

        // 自适应露出比例：牌少时每张最多露出 66%，放不下时压缩到最低 22%
        var visible = 0.66f
        var w = minOf(w0, avail / (1f + (n - 1) * visible))
        if (w < w0) {
            visible = ((avail / w0 - 1f) / (n - 1).coerceAtLeast(1))
                .coerceIn(0.22f, 0.66f)
            w = minOf(w0, avail / (1f + (n - 1) * visible))
        }
        val step = w * visible                  // 相邻牌间距（露出部分）
        val overlap = w - step
        val total = w + step * (n - 1)
        val startX = (maxWidth - total) / 2     // 居中起点（两侧留白）

        // 手势闭包用最新几何/手牌（旋转、发牌、出牌后不失效）
        val density = LocalDensity.current
        val geo = rememberUpdatedState(
            HandGeo(
                n = hand.size,
                startXpx = with(density) { startX.toPx() },
                stepPx = with(density) { step.toPx() },
                cardWpx = with(density) { w.toPx() }
            )
        )
        val handState = rememberUpdatedState(hand)

        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        var lastX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!dragging) {
                                val moved = kotlin.math.abs(change.position.x - down.position.x) >
                                        viewConfiguration.touchSlop ||
                                        kotlin.math.abs(change.position.y - down.position.y) >
                                        viewConfiguration.touchSlop
                                if (moved) {
                                    dragging = true
                                    // 起点上的牌先纳入多选
                                    idxAtX(geo.value, lastX)?.let { i ->
                                        handState.value.getOrNull(i)?.let { c -> onSweep(listOf(c.id)) }
                                    }
                                }
                            }
                            if (dragging) {
                                val a = minOf(lastX, change.position.x)
                                val b = maxOf(lastX, change.position.x)
                                val i0 = idxAtX(geo.value, a)
                                val i1 = idxAtX(geo.value, b)
                                if (i0 != null || i1 != null) {
                                    val lo = minOf(i0 ?: i1!!, i1 ?: i0!!)
                                    val hi = maxOf(i0 ?: i1!!, i1 ?: i0!!)
                                    val ids = (lo..hi).mapNotNull { handState.value.getOrNull(it)?.id }
                                    if (ids.isNotEmpty()) onSweep(ids)
                                }
                                lastX = change.position.x
                                change.consume()    // 吃掉移动事件，避免误触发单击
                            }
                            if (!change.pressed) break
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(-overlap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom
        ) {
            hand.forEach { c ->
                // 每张新出现的牌从上方滑入（发牌逐张触发；出牌后余牌槽位复用不重播）
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(100)) + slideInVertically(tween(130)) { -it / 3 }
                ) {
                    PokerCard(c, w, raised = c.id in selected, onClick = { onToggle(c.id) })
                }
            }
        }
    }
}

/** 手牌几何快照（像素），供滑动手势把 x 坐标映射到牌序号 */
internal data class HandGeo(val n: Int, val startXpx: Float, val stepPx: Float, val cardWpx: Float)

/** x 坐标 → 牌序号；落在牌堆两侧留白区时返回 null（不误选） */
internal fun idxAtX(g: HandGeo, x: Float): Int? {
    if (g.n <= 0 || g.stepPx <= 0f) return null
    val rel = x - g.startXpx
    val end = (g.n - 1) * g.stepPx + g.cardWpx
    if (rel < -0.25f * g.cardWpx || rel > end + 0.25f * g.cardWpx) return null
    if (rel <= 0f) return 0
    return (rel / g.stepPx).toInt().coerceIn(0, g.n - 1)
}

// ------------------------------------------------ 多排手牌（掼蛋/升级 25~27 张大 hand）

/** 单排几何 + 该排每张牌中心的 x 像素（含组间隙，供手势映射） */
internal data class RowGeo(
    val ids: List<Int>,
    val startXpx: Float,
    val cardWpx: Float,
    val centersPx: List<Float>,
    val topPx: Float,
    val botPx: Float
)

internal data class HandGeo2(val rows: List<RowGeo>)

/** (x, y) → 牌 id：先按 y 找排，再取该排中心最近的牌 */
internal fun cardIdAt(g: HandGeo2, x: Float, y: Float): Int? {
    if (g.rows.isEmpty()) return null
    val row = g.rows.firstOrNull { y >= it.topPx && y < it.botPx }
        ?: if (y < g.rows.first().topPx) g.rows.first()
        else g.rows.last()
    if (row.ids.isEmpty()) return null
    val last = row.centersPx.last()
    val first = row.centersPx.first()
    if (x < first - row.cardWpx * 0.45f || x > last + row.cardWpx * 0.45f) return null
    var best = 0
    var bestD = Float.MAX_VALUE
    row.centersPx.forEachIndexed { i, cx ->
        val d = kotlin.math.abs(x - cx)
        if (d < bestD) { bestD = d; best = i }
    }
    return row.ids[best]
}

/**
 * 多排手牌（1~3 排自动）：大 hand（掼蛋 27 / 升级 25~33）分排显示，支持
 * 单击选牌 + 按住横向滑动跨排多选 + 上下拖动整叠牌面；[gapAfterIds] 在指定牌后插入组间隙（自动理牌分组），
 * [badges]/[warmIds] 为级牌角标与主牌暖色高亮；[availH] 为可用高度（超出时自动缩小牌面，
 * 底排始终贴底不再被剪切），[onHeight] 回调实际内容高度（供界面定位按钮）。
 */
@Composable
internal fun HandRows(
    hand: List<Card>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onSweep: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    forceRows: Int = 0,
    gapAfterIds: Set<Int> = emptySet(),
    badges: Map<Int, String> = emptyMap(),
    warmIds: Set<Int> = emptySet(),
    cardScale: Float = 0.80f,
    availH: Dp = Dp.Unspecified,
    onHeight: (Dp) -> Unit = {}
) {
    val config = LocalConfiguration.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val n = hand.size
        if (n == 0) return@BoxWithConstraints
        val screenH = config.screenHeightDp.dp
        val w0 = screenH * 0.24f * CARD_RATIO * cardScale
        val avail = (maxWidth - 2.dp) * 0.90f

        val rowCount = when {
            forceRows > 0 -> forceRows
            n <= 14 -> 1
            n <= 30 -> 2
            else -> 3
        }
        // 分排：顶排少、底排多（底排更靠近拇指）
        val rowSizes = splitRowSizes(n, rowCount)   // 自顶向底
        val rowsCards: List<List<Card>> = run {
            var s = 0
            rowSizes.map { c -> hand.subList(s, (s + c).coerceAtMost(n)).also { s += c } }.filter { it.isNotEmpty() }
        }

        // 统一卡宽：按最大排张数计算（各排视觉一致）
        val maxM = rowsCards.maxOf { it.size }
        val rowGapRef = 3.dp
        var visible = 0.60f
        var w = minOf(w0, avail / (1f + (maxM - 1) * visible))
        if (w < w0) {
            visible = ((avail / w0 - 1f) / (maxM - 1).coerceAtLeast(1)).coerceIn(0.18f, 0.60f)
            w = minOf(w0, avail / (1f + (maxM - 1) * visible))
        }
        // 可用高度约束：超出时缩小牌面（保证底排完整贴底、不被底边剪切）
        if (availH != Dp.Unspecified && rowsCards.isNotEmpty()) {
            val maxCardH = (availH - rowGapRef * (rowsCards.size - 1)) / rowsCards.size
            if (w / CARD_RATIO > maxCardH) w = maxCardH * CARD_RATIO
        }
        val step = w * visible
        val gapW = w * 0.16f                  // 组间隙宽度
        val cardH = w / CARD_RATIO
        val rowGap = rowGapRef

        val density = LocalDensity.current
        // 预计算每排几何（像素），绘制与手势共用同一份数据
        val rowsGeo = rowsCards.map { rowCards ->
            // x 偏移序列：每张牌的起点
            val offsets = ArrayList<Float>(rowCards.size)
            var x = 0f
            rowCards.forEachIndexed { i, c ->
                offsets += x
                x += step.value
                if (c.id in gapAfterIds) x += gapW.value
            }
            val totalDp = x + w.value - step.value   // 末张占满
            val startX = (maxWidth - totalDp.dp) / 2
            RowGeoDraft(offsets, startX.value)
        }
        val containerH = cardH * rowsCards.size + rowGap * (rowsCards.size - 1)

        // 拖动牌面：默认贴底（底排完整可见），可上提查看桌面，永不越过初始贴底位置
        var panPx by remember { mutableStateOf(0f) }
        LaunchedEffect(hand.size, rowsCards.size) { panPx = 0f }
        val maxUpPx = with(density) {
            (containerH - 48.dp).coerceAtLeast(0.dp).toPx()
        }
        SideEffect { onHeight(containerH) }

        val geo = rememberUpdatedState(
            HandGeo2(
                rowsCards.mapIndexed { ri, rowCards ->
                    val draft = rowsGeo[ri]
                    val topPx = with(density) { ((cardH + rowGap) * ri).toPx() }
                    val botPx = with(density) { ((cardH + rowGap) * ri + cardH).toPx() }
                    RowGeo(
                        ids = rowCards.map { it.id },
                        startXpx = with(density) { draft.startX.dp.toPx() },
                        cardWpx = with(density) { w.toPx() },
                        centersPx = draft.offsets.map { off ->
                            with(density) { (draft.startX + off + w.value / 2f).dp.toPx() }
                        },
                        topPx = topPx,
                        botPx = botPx
                    )
                }
            )
        )
        Column(
            Modifier
                .fillMaxWidth()
                .height(containerH)
                .offset { IntOffset(0, panPx.roundToInt()) }
                .pointerInput(maxUpPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0                       // 0=未定，1=横向滑选，2=纵向拖牌
                        var lastX = down.position.x
                        var panStart = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (mode == 0) {
                                val dx = kotlin.math.abs(change.position.x - down.position.x)
                                val dy = kotlin.math.abs(change.position.y - down.position.y)
                                if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                                    mode = if (dx >= dy) 1 else 2
                                    if (mode == 1) {
                                        // 起点上的牌先纳入多选
                                        cardIdAt(geo.value, lastX, down.position.y)?.let { id ->
                                            onSweep(listOf(id))
                                        }
                                    } else {
                                        panStart = panPx
                                    }
                                }
                            }
                            when (mode) {
                                1 -> {
                                    // 逐点采样映射（跨排时 y 变化自动换排）
                                    val a = minOf(lastX, change.position.x)
                                    val b = maxOf(lastX, change.position.x)
                                    val y = change.position.y
                                    val ids = sampledIds(geo.value, a, b, y)
                                    if (ids.isNotEmpty()) onSweep(ids)
                                    lastX = change.position.x
                                    change.consume()
                                }
                                2 -> {
                                    panPx = (panStart + change.position.y - down.position.y)
                                        .coerceIn(-maxUpPx, 0f)
                                    change.consume()
                                }
                            }
                            if (!change.pressed) break
                        }
                    }
                }
        ) {
            rowsGeo.forEachIndexed { ri, draft ->
                val rowCards = rowsCards[ri]
                // 手动绝对布局（含组间隙），Row 负间距无法表达逐项间隙
                Box(Modifier.fillMaxWidth().height(cardH)) {
                    rowCards.forEachIndexed { i, c ->
                        val xDp = draft.startX.dp + draft.offsets[i].dp
                        val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = appear,
                            enter = fadeIn(tween(100)) + slideInVertically(tween(130)) { -it / 3 },
                            modifier = Modifier.offset(x = xDp)
                        ) {
                            PokerCard(
                                c, w,
                                raised = c.id in selected,
                                badge = badges[c.id],
                                warm = c.id in warmIds,
                                onClick = { onToggle(c.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分排张数：底排最多、顶排均摊（自顶向底返回） */
private fun splitRowSizes(n: Int, rows: Int): List<Int> {
    if (rows <= 1 || n <= 0) return listOf(n)
    val base = n / rows
    var extra = n % rows
    val sizes = ArrayList<Int>(rows)
    // 自底向上分配：底排 base(+extra)，顶排少
    val tmp = IntArray(rows)
    for (i in rows - 1 downTo 0) {
        tmp[i] = base + if (extra > 0) { extra--; 1 } else 0
    }
    // 顶排最多比底排少 1~2 张的均衡已由 base/extra 保证；把顶排多出的挪到底排
    for (i in 0 until rows) sizes += tmp[i]
    return sizes
}

/** 滑动采样：在 [a,b] x 区间与当前 y 附近收集牌 id（跨排连续多选） */
private fun sampledIds(g: HandGeo2, a: Float, b: Float, y: Float): List<Int> {
    val out = ArrayList<Int>()
    val stepPx = g.rows.firstOrNull()?.cardWpx?.coerceAtLeast(1f) ?: return out
    var x = a
    while (x <= b) {
        cardIdAt(g, x, y)?.let { id -> if (id !in out) out += id }
        x += stepPx * 0.4f
    }
    cardIdAt(g, b, y)?.let { id -> if (id !in out) out += id }
    return out
}

private data class RowGeoDraft(val offsets: List<Float>, val startX: Float)
