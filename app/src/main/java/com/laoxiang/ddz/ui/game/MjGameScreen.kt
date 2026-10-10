package com.laoxiang.ddz.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.MjClaimOpt
import com.laoxiang.ddz.data.MjPhase
import com.laoxiang.ddz.data.MjSeatView
import com.laoxiang.ddz.data.MjSnapshot
import com.laoxiang.ddz.data.MjSuit
import com.laoxiang.ddz.data.MjTile
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.theme.Gold
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 麻将牌局 —— v1.5.8 视觉层完全按用户参考视频（天凤方城对局）重做中央区：
 * 桌面 = 深黑呢面 + 牌河区灰描线 + 左右金色装饰柱（mj_table_dark，PIL 实测复刻）；
 * 牌墙 = 四边各 17 摞双层方城（黑背顶面 + 白端面朝桌心，Canvas 直绘）；
 * 中央 = 黑色八角骰台（点击掷骰 → LED 七段剩余牌数 + 红场风块 + 灰风位块）；
 * 四家牌河围绕骰台 6 列网格（下/上=小立牌，左/右=横躺小牌，FlyIn 保留）；
 * 他家手牌 = cemian 背面（右/左竖列 22 间距重叠、上家横排相邻）；
 * 我方手牌 = psmj 大牌紧贴、右对齐 13+1 布局、选中上浮 25；
 * 副露 = 白胚+0.573/0.4584 刻字合成，碰/吃来的牌按 APK 规则转向；
 * 发牌动画保留（一摞一摞从牌墙飞向四家）。
 */

/** 金色数字素材 */
private val NUM_RES = intArrayOf(
    R.drawable.mj_num0, R.drawable.mj_num1, R.drawable.mj_num2, R.drawable.mj_num3,
    R.drawable.mj_num4, R.drawable.mj_num5, R.drawable.mj_num6, R.drawable.mj_num7,
    R.drawable.mj_num8, R.drawable.mj_num9
)

/** 开局掷骰子素材（麻将素材包 sezi1-6_800，78x80） */
private val DICE_RES = intArrayOf(
    R.drawable.mj_dice1, R.drawable.mj_dice2, R.drawable.mj_dice3,
    R.drawable.mj_dice4, R.drawable.mj_dice5, R.drawable.mj_dice6
)

/** 单颗骰子：掷出中（[rolling]=true）快速变面+旋转，定格后显示 [value] 点 */
@Composable
private fun DiceDie(value: Int, rolling: Boolean, p: Float, size: Dp) {
    val face = if (rolling) (p * 24f).toInt() % 6 else (value - 1).coerceIn(0, 5)
    Image(
        painter = painterResource(DICE_RES[face]),
        contentDescription = "骰子",
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .graphicsLayer {
                rotationZ = if (rolling) p * 900f else 0f
                val bounce = if (rolling) abs(sin(p * 9.4f)) * 0.16f else 0f
                val s = (if (rolling) 0.86f + 0.14f * p else 1f) - bounce
                scaleX = s; scaleY = s
            }
            .size(size, size * 80f / 78f)
    )
}

/** 五星红旗（国旗一律使用中国国旗） */
@Composable
private fun ChinaFlag(w: Dp) {
    Canvas(Modifier.size(w, w * 2f / 3f)) {
        drawRoundRect(Color(0xFFDE2910), Offset.Zero, size, CornerRadius(1.dp.toPx() / 2f, 1.dp.toPx() / 2f))
        fun star(cx: Float, cy: Float, r: Float, aimDeg: Float = -90f): androidx.compose.ui.graphics.Path {
            val p = androidx.compose.ui.graphics.Path()
            for (i in 0 until 10) {
                val ang = Math.toRadians((aimDeg + i * 36.0))
                val rad = if (i % 2 == 0) r else r * 0.382f
                val px = cx + (rad * kotlin.math.cos(ang)).toFloat()
                val py = cy + (rad * kotlin.math.sin(ang)).toFloat()
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            p.close()
            return p
        }
        val gold = Color(0xFFFFDE00)
        val bigCx = size.width * 0.20f; val bigCy = size.height * 0.40f
        drawPath(star(bigCx, bigCy, size.height * 0.17f), gold)
        // 四颗小星（右侧弧位，一角指向大星心）
        listOf(
            size.width * 0.40f to size.height * 0.14f,
            size.width * 0.50f to size.height * 0.28f,
            size.width * 0.50f to size.height * 0.52f,
            size.width * 0.40f to size.height * 0.66f
        ).forEach { (sx, sy) ->
            val aim = Math.toDegrees(
                kotlin.math.atan2((bigCy - sy).toDouble(), (bigCx - sx).toDouble())
            ).toFloat()
            drawPath(star(sx, sy, size.height * 0.062f, aim), gold)
        }
    }
}

/** 横躺牌河蛇形列位：列内 6 张，列满向墙方向另起一列，返回 (列号, 列内序号)（天凤 6 列网格） */
private fun lieRiverCell6(i: Int): Pair<Int, Int> = (i / 6) to (i % 6)

/** code(0..33) → 任一副本牌（宣告预览用） */
private fun codeTile(code: Int): MjTile {
    val suit = when {
        code < 9 -> MjSuit.WAN
        code < 18 -> MjSuit.TONG
        code < 27 -> MjSuit.TIAO
        else -> MjSuit.ZI
    }
    val num = code % 9 + 1
    return MjTile(MjTile.idOf(suit, num, 0), suit, num)
}

@Composable
fun MjGameScreen(vm: MjViewModel, onExit: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
    ) {
        // ===== v1.5.8 桌面：天凤参考视频深黑桌面（炭黑呢面 + 牌河区灰描线 + 左右金色装饰柱） =====
        Image(
            painter = painterResource(R.drawable.mj_table_dark),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )

        // 项目专属背景刻字水印（与其它玩法一致：老乡大众麻将/老乡红中癞子/老乡四川血战）
        // 必须传 fillMaxSize 让内层 Box 撑满全屏并居中，否则会缩成内容大小贴在左上角
        snapshot?.let { TableEngraving("老乡" + it.mode.label, Modifier.fillMaxSize()) }

        val W = maxWidth
        val H = maxHeight
        // APK 双坐标系：800x480（changePix_X/Y）+ 1280x720（changePix_*_1280，含宽高比钳制）
        val u = if (H / W <= 0.5625f) H / 720f else W / 1280f
        val density = LocalDensity.current
        fun fx(v: Float) = W * (v / 800f)
        fun fy(v: Float) = H * (v / 480f)
        fun g(v: Float) = u * v

        /** 绝对定位放置（等价 JoyDraw 左上角坐标） */
        @Composable
        fun Place(x: Dp, y: Dp, content: @Composable () -> Unit) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = x, y = y)
            ) { content() }
        }

        /** 新牌飞入：牌河每张新牌从出牌者方向飞入落位（key=牌 id，物理牌唯一 → 每张只飞一次） */
        @Composable
        fun FlyIn(dir: Int, animKey: Any, content: @Composable () -> Unit) {
            val p = remember(animKey) { Animatable(0f) }
            LaunchedEffect(animKey) { p.animateTo(1f, tween(230, easing = LinearEasing)) }
            Box(
                Modifier.graphicsLayer {
                    val inv = 1f - p.value
                    translationX = when (dir) {
                        1 -> W.toPx() * 0.14f * inv
                        3 -> -W.toPx() * 0.14f * inv
                        else -> 0f
                    }
                    translationY = when (dir) {
                        0 -> H.toPx() * 0.16f * inv
                        2 -> -H.toPx() * 0.16f * inv
                        else -> 0f
                    }
                    alpha = 0.35f + 0.65f * p.value
                }
            ) { content() }
        }

        /**
         * 牌墙摞（未抓的牌）—— 严格按参考视频（天凤方城）样式：
         * 每摞 = 黑色牌背顶面（带淡淡菱形暗纹）+ 双层白色端面条（中间细缝）。
         * 横墙摞（上/下墙）= 白端面朝桌心（上墙白条在下缘、下墙白条在上缘）；
         * 竖墙摞（左/右墙）= 透视压扁的横躺牌（黑块 + 朝心侧白端条）。
         * [appear] 开局淡入系数（0 隐 → 1 全显）。
         */
        @Composable
        fun WallStackH(x: Dp, y: Dp, w: Dp, h: Dp, topSide: Boolean, appear: Float) {
            if (appear <= 0.01f) return
            Place(x, y) {
                Canvas(Modifier.size(w, h)) {
                    val bw = size.width
                    val bh = size.height
                    val bodyH = bh * 0.52f
                    val barH = bh * 0.21f
                    val gap = (bh - bodyH - barH * 2f) / 3f
                    val bodyYc = if (topSide) 0f else bh - bodyH
                    // 黑色牌背顶面（微渐变）
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFF23262C), Color(0xFF15171B))),
                        Offset(0f, bodyYc), Size(bw, bodyH), CornerRadius(bw * 0.14f, bw * 0.14f)
                    )
                    drawRoundRect(
                        Color(0x66000000), Offset(0f, bodyYc), Size(bw, bodyH),
                        CornerRadius(bw * 0.14f, bw * 0.14f),
                        style = Stroke(0.8.dp.toPx())
                    )
                    // 淡菱形暗纹（天凤牌背"眞"纹的抽象化）
                    val cxm = bw / 2f
                    val cym = bodyYc + bodyH / 2f
                    val r = bw * 0.24f
                    val lc = Color(0x30AAAAAAAA)
                    drawLine(lc, Offset(cxm - r, cym), Offset(cxm, cym - r * 0.9f), strokeWidth = 1.dp.toPx())
                    drawLine(lc, Offset(cxm, cym - r * 0.9f), Offset(cxm + r, cym), strokeWidth = 1.dp.toPx())
                    drawLine(lc, Offset(cxm + r, cym), Offset(cxm, cym + r * 0.9f), strokeWidth = 1.dp.toPx())
                    drawLine(lc, Offset(cxm, cym + r * 0.9f), Offset(cxm - r, cym), strokeWidth = 1.dp.toPx())
                    // 双层白色端面（端面朝桌心：上墙白条在下缘 / 下墙白条在上缘）
                    val bar1Y = if (topSide) bodyH + gap else gap
                    val bar2Y = if (topSide) bodyH + gap * 2f + barH else gap * 2f + barH
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFFF2EFE4), Color(0xFFD8D4C6))),
                        Offset(0f, bar1Y), Size(bw, barH), CornerRadius(bw * 0.1f, bw * 0.1f)
                    )
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFFEAE7DC), Color(0xFFCCC8B9))),
                        Offset(0f, bar2Y), Size(bw, barH), CornerRadius(bw * 0.1f, bw * 0.1f)
                    )
                }.graphicsLayer { alpha = appear }
            }
        }

        @Composable
        fun WallStackV(x: Dp, y: Dp, w: Dp, h: Dp, leftSide: Boolean, appear: Float) {
            if (appear <= 0.01f) return
            Place(x, y) {
                Canvas(Modifier.size(w, h)) {
                    val bw = size.width
                    val bh = size.height
                    // 透视压扁的横躺牌：黑块 + 朝心侧白端条（左右墙端面均朝桌心）
                    val bodyW = bw * 0.62f
                    val barW = bw - bodyW - bw * 0.04f
                    val bodyX = if (leftSide) 0f else barW + bw * 0.04f
                    val barX = if (leftSide) bodyW + bw * 0.04f else 0f
                    drawRoundRect(
                        Brush.horizontalGradient(
                            if (leftSide) listOf(Color(0xFF1D2025), Color(0xFF101216))
                            else listOf(Color(0xFF101216), Color(0xFF1D2025))
                        ),
                        Offset(bodyX, 0f), Size(bodyW, bh), CornerRadius(bh * 0.3f, bh * 0.3f)
                    )
                    drawRoundRect(
                        Brush.horizontalGradient(
                            if (leftSide) listOf(Color(0xFFEDEADF), Color(0xFFCFCBBB))
                            else listOf(Color(0xFFCFCBBB), Color(0xFFEDEADF))
                        ),
                        Offset(barX, bh * 0.08f), Size(barW, bh * 0.84f), CornerRadius(bh * 0.3f, bh * 0.3f)
                    )
                    // 双层缝
                    drawLine(
                        Color(0x88000000),
                        Offset(barX + barW / 2f, bh * 0.16f),
                        Offset(barX + barW / 2f, bh * 0.84f),
                        strokeWidth = 0.8.dp.toPx()
                    )
                }.graphicsLayer { alpha = appear }
            }
        }

        /** APK DrawFlatAvatar 1:1 玩家信息：头像98x97+庄标39x38右下+名字 g(26)白字+徽章 */
        @Composable
        fun MjApkCard(s: MjSeatView, pos: Int) {
            val nameFs = with(density) { g(26f).toSp() }
            val nameRow: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChinaFlag(g(30f))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        s.name,
                        fontSize = nameFs, fontWeight = FontWeight.Bold,
                        color = if (s.isTurn) Color(0xFFFFE082) else Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 96.dp)
                    )
                    if (s.huRank > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("胡", fontSize = 11.sp, color = Gold, fontWeight = FontWeight.Black)
                    }
                    if (s.dingque >= 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "缺${suitLabel(s.dingque)}", fontSize = 9.sp,
                            color = Color(0xFF80DEEA),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x59002438))
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                    if (s.ting) {
                        Spacer(Modifier.width(3.dp))
                        Image(
                            painter = painterResource(R.drawable.mj_mark_ting),
                            contentDescription = "听牌",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (s.isAi) {
                        Spacer(Modifier.width(3.dp))
                        Text("AI", fontSize = 8.sp, color = Color(0x99FFFFFF))
                    }
                }
            }
            val avatarBox: @Composable () -> Unit = {
                Box {
                    AvatarImage(
                        s.avatar, g(97f),
                        Modifier.border(
                            2.dp,
                            if (s.isTurn) Color(0xFFFFC107) else Color(0x66FFFFFF),
                            CircleShape
                        )
                    )
                    if (s.isDealer) {
                        Image(
                            painter = painterResource(R.drawable.mj_mark_zhuangxiao),
                            contentDescription = "庄家",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(g(39f), g(38f))
                        )
                    }
                }
            }
            when (pos) {
                // 右家：头像(740,110)，名字在下方偏左 (725,170)
                1 -> Place(fx(740f), fy(110f)) {
                    Column {
                        avatarBox()
                        Spacer(Modifier.height(fy(4f)))
                        Row(Modifier.offset(x = -fx(15f))) { nameRow() }
                    }
                }
                // 对家：头像(542,0)，名字在右侧 (600,0)
                2 -> Place(fx(542f), fy(0f)) {
                    Row {
                        avatarBox()
                        Spacer(Modifier.width(g(4f)))
                        nameRow()
                    }
                }
                // 左家：头像(0,110)，名字在下方 (0,170)
                3 -> Place(fx(0f), fy(110f)) {
                    Column {
                        avatarBox()
                        Spacer(Modifier.height(fy(4f)))
                        nameRow()
                    }
                }
                // 我：头像(0,329)，名字在右侧 (55,339)
                else -> Place(fx(0f), fy(329f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        avatarBox()
                        Spacer(Modifier.width(g(4f)))
                        Column { nameRow() }
                    }
                }
            }
        }

        val snap = snapshot ?: run {
            Text(
                "准备开局…",
                color = Color(0xCCD7E7FA), fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center)
            )
            return@BoxWithConstraints
        }

        val mySeat by vm.mySeat.collectAsState()
        val turnWindow by vm.turnWindow.collectAsState()
        val seats = snap.seats
        val me = seats[mySeat.coerceIn(0, 3)]
        val right = seats[(mySeat + 1) % 4]
        val top = seats[(mySeat + 2) % 4]
        val left = seats[(mySeat + 3) % 4]
        val selected by vm.selected.collectAsState()

        // ---- 开局仪式 v1.5.7：严格按参考视频时序 —— 方城先立 → 点击掷骰（3.2s 未点自动掷）
        //      → 摇骰 1s（变面+旋转+弹跳）→ 定格 0.8s 亮点数 → 一摞一摞从牌墙发牌 → 开局 ----
        // 旧 v1.5.6 把发牌与骰子并行、进局即飞牌，与视频「先骰后发」不符；本版改回视频顺序，
        // 但方城 300ms 内先淡入立好、骰盒+呼吸提示常驻罗盘中心，桌面从第一帧起就是活的，
        // 不会重现「全桌静止卡到掷骰」的观感；自动掷兜底保证永不卡死。
        val deal = remember { Animatable(0f) }
        val wallIn = remember { Animatable(0f) }
        val diceRoll = remember { Animatable(0f) }
        val diceFade = remember { Animatable(1f) }
        var dicePair by remember { mutableStateOf(intArrayOf(5, 3)) }
        var diceShown by remember { mutableStateOf(intArrayOf(5, 3)) }
        var dealDone by remember { mutableStateOf(false) }
        var diceDone by remember { mutableStateOf(false) }
        var diceTapped by remember { mutableStateOf(false) }
        LaunchedEffect(snap.round) {
            dealDone = false
            diceDone = false
            diceTapped = false
            deal.snapTo(0f)
            wallIn.snapTo(0f)
            diceRoll.snapTo(0f)
            diceFade.snapTo(1f)
            wallIn.animateTo(1f, tween(320, easing = LinearEasing))   // 方城先立（视频 5.0-5.4s）
            // 待掷展示面（点击前不泄露引擎点数）
            diceShown = intArrayOf(Random.nextInt(1, 7), Random.nextInt(1, 7))
            // 骰子点数由引擎掷出（同时决定切墙位置，仪式与发牌一致）
            dicePair = if (snap.dice1 in 1..6 && snap.dice2 in 1..6) intArrayOf(snap.dice1, snap.dice2)
            else intArrayOf(Random.nextInt(1, 7), Random.nextInt(1, 7))
        }
        // 掷骰 → 亮点 → 发牌（视频 6.5-11s：手摇骰盒 → LED 亮相 → 牌从墙飞向四家）
        LaunchedEffect(snap.round, diceTapped) {
            if (!diceTapped) return@LaunchedEffect
            diceRoll.animateTo(1f, tween(1000, easing = LinearEasing))
            delay(800)                       // 定格展示点数
            diceDone = true
            deal.animateTo(1f, tween(1200, easing = LinearEasing))   // 一摞一摞发牌
            dealDone = true
            vm.ceremonyFinished()
        }
        // 3.2 秒未点击自动掷（兜底，保证流程永不卡死）
        LaunchedEffect(snap.round) {
            delay(3200)
            diceTapped = true
        }

        var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
        var showChat by remember { mutableStateOf(false) }
        var myBubble by remember { mutableStateOf<Pair<Long, String>?>(null) }
        var bigFx by remember { mutableStateOf<Triple<Long, Int, String>?>(null) }
        val bigScale = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(myBubble?.first) { if (myBubble != null) { delay(2600); myBubble = null } }
        LaunchedEffect(toast?.first) { if (toast != null) { delay(2200); toast = null } }
        LaunchedEffect(Unit) {
            vm.fx.collect { f ->
                when (f) {
                    is MjFx.Claim -> if (f.seat != mySeat) {
                        bigFx = Triple(
                            System.nanoTime(),
                            when (f.kind) {
                                "CHI" -> R.drawable.mj_fx_chi
                                "PENG" -> R.drawable.mj_fx_peng
                                else -> R.drawable.mj_fx_gang
                            },
                            ""
                        )
                        flashBig(bigScale)
                    }
                    is MjFx.Hu -> {
                        bigFx = Triple(
                            System.nanoTime(), R.drawable.mj_fx_hu,
                            if (f.selfDraw) "自摸" else ""
                        )
                        flashBig(bigScale)
                    }
                    is MjFx.LiuJu -> {
                        bigFx = Triple(System.nanoTime(), R.drawable.mj_fx_liuju, "")
                        flashBig(bigScale)
                    }
                    else -> {}
                }
            }
        }
        LaunchedEffect(Unit) {
            vm.opNotice.collect { t -> if (t != null) { toast = System.nanoTime() to t; vm.clearOpNotice() } }
        }

        // ================= 牌墙方城（未抓的牌，画在最底层，严格按参考视频天凤方城） =================
        // 参考视频样式：四条边各 17 摞（每摞双层）围成矩形方城；
        // 上/下墙白端面朝桌心、左/右墙透视压扁端面朝桌心。
        // 从下墙右端（骰点所定切墙口）逆时针消耗：shown = wallCount 等比映射，
        // 缺口自下墙右端向左生长。开局随 wallIn 淡入立城，是发牌动画的起飞源。
        val wallPitch = g(33f)
        val wallStackW = g(30f)
        val wallStackH = g(40f)
        val wallRowW = wallPitch * 16f + wallStackW      // 17 摞横墙总宽
        val wallXStart = (W - wallRowW) / 2              // 上/下墙左端
        val wallYTop = fy(122f)                          // 上墙顶（在上家手牌背下方）
        val wallYBot = fy(330f)                          // 下墙顶
        val wallColL = fx(176f)                          // 左墙黑块外缘
        val wallColR = fx(584f)                          // 右墙黑块外缘
        val wallVPitch = (wallYBot - wallYTop - wallStackH) / 16f   // 左右墙 17 摞纵向 pitch
        run {
            val appear = wallIn.value
            if (appear > 0.01f) {
                // 摞位（消耗序）：下排右→左 17 → 左列下→上 17 → 上排左→右 17 → 右列上→下 17
                val shown = (snap.wallCount * 68 / 84).coerceIn(0, 68)
                for (idx in (68 - shown).coerceAtLeast(0) until 68) {
                    when {
                        // 下排（右→左），白端面朝上（朝桌心）
                        idx < 17 -> WallStackH(
                            wallXStart + wallPitch * (16 - idx), wallYBot,
                            wallStackW, wallStackH, false, appear
                        )
                        // 左列（下→上），白端面朝右（朝桌心）
                        idx < 34 -> WallStackV(
                            wallColL, wallYBot - wallStackH - wallVPitch * (idx - 17),
                            g(40f), g(11f), true, appear
                        )
                        // 上排（左→右），白端面朝下（朝桌心）
                        idx < 51 -> WallStackH(
                            wallXStart + wallPitch * (idx - 34), wallYTop,
                            wallStackW, wallStackH, true, appear
                        )
                        // 右列（上→下），白端面朝左（朝桌心）
                        else -> WallStackV(
                            wallColR, wallYTop + wallStackH + wallVPitch * (idx - 51),
                            g(40f), g(11f), false, appear
                        )
                    }
                }
            }
        }

        // ================= 顶部工具条（模式/癞子/余牌/离桌） =================
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x96121A18))
                    .border(1.dp, Color(0x4DFFD9A0), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    snap.mode.label,
                    color = Color(0xFFEBCB8C), fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Image(
                    painter = painterResource(R.drawable.mj_tile_icon),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(9.dp, 13.dp)
                )
                Spacer(Modifier.width(3.dp))
                NumDigits(snap.wallCount, 13.dp)
                if (snap.laiziCode >= 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("癞子", color = Color(0xFFFFD54F), fontSize = 11.sp)
                    MjTileView(
                        tile = MjTile.byId(tileIdOfCode(snap.laiziCode)),
                        w = 17.dp, laiziMark = true,
                        modifier = Modifier.padding(start = 3.dp)
                    )
                }
                MjToolButton("离桌", danger = true) { onExit() }
            }
        }

        // ================= 中央骰台（参考视频天凤凸台样式） =================
        // 黑色八角斜面凸台：圆角方座 + 八角斜面 + 中央凹井；
        // 凹井内：开局掷骰阶段显示双骰（点击凸台即掷），发牌后显示 LED 蓝色七段剩余牌数；
        // 左下红色场风块（庄家方位）+ 灰色风位小块，凸台四角金色小钉装饰。
        val plateSz = g(126f)
        val dealerWind = charWind((snap.dealer - mySeat + 4) % 4)
        val oppWind = charWind((mySeat + 2 - snap.dealer + 4) % 4)
        val leftWind = charWind((mySeat + 3 - snap.dealer + 4) % 4)
        Box(
            Modifier
                .align(Alignment.Center)
                .size(plateSz)
        ) {
            Canvas(Modifier.matchParentSize()) {
                val s = size.width
                // 外座（深黑圆角方 + 金细描边）
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFF17191E), Color(0xFF0B0D10))),
                    Offset.Zero, Size(s, s), CornerRadius(s * 0.09f, s * 0.09f)
                )
                drawRoundRect(
                    Color(0xFF6E5618), Offset.Zero, Size(s, s),
                    CornerRadius(s * 0.09f, s * 0.09f), style = Stroke(1.2.dp.toPx())
                )
                // 八角斜面（切角八边形，亮面反光）
                val cut = s * 0.16f
                val octPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cut, s * 0.10f)
                    lineTo(s - cut, s * 0.10f)
                    lineTo(s * 0.90f, cut)
                    lineTo(s * 0.90f, s - cut)
                    lineTo(s - cut, s * 0.90f)
                    lineTo(cut, s * 0.90f)
                    lineTo(s * 0.10f, s - cut)
                    lineTo(s * 0.10f, cut)
                    close()
                }
                drawPath(octPath, Brush.linearGradient(listOf(Color(0xFF23262C), Color(0xFF0F1114))))
                drawPath(octPath, Color(0xFF3A3E46), style = Stroke(1.dp.toPx()))
                // 中央凹井
                val well = s * 0.44f
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFF070809), Color(0xFF0E1013))),
                    Offset((s - well) / 2f, (s - well) / 2f), Size(well, well),
                    CornerRadius(s * 0.05f, s * 0.05f)
                )
                drawRoundRect(
                    Color(0xFF2A2D33), Offset((s - well) / 2f, (s - well) / 2f), Size(well, well),
                    CornerRadius(s * 0.05f, s * 0.05f), style = Stroke(0.8.dp.toPx())
                )
                // 四角金色小钉
                val pin = s * 0.035f
                listOf(
                    Offset(s * 0.055f, s * 0.055f), Offset(s - s * 0.055f, s * 0.055f),
                    Offset(s * 0.055f, s - s * 0.055f), Offset(s - s * 0.055f, s - s * 0.055f)
                ).forEach { c ->
                    drawCircle(Color(0xFFC9A227), pin, c)
                    drawCircle(Color(0xFFFFE082), pin * 0.45f, c)
                }
            }
            // 凹井内容：掷骰阶段 = 双骰；其余 = LED 剩余牌数（天凤蓝色七段管）
            if (diceFade.value > 0.01f) {
                val rolling = diceTapped && diceRoll.value < 1f
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer { alpha = diceFade.value }
                ) {
                    DiceDie(if (diceTapped) dicePair[0] else diceShown[0], rolling, diceRoll.value, g(42f))
                    Spacer(Modifier.width(g(12f)))
                    DiceDie(if (diceTapped) dicePair[1] else diceShown[1], rolling, diceRoll.value, g(42f))
                }
            } else if (!diceTapped || deal.value > 0f) {
                Box(Modifier.align(Alignment.Center)) { SevenSegNum(snap.wallCount.coerceIn(0, 99), g(32f)) }
            }
            // 左下红色场风块（庄家方位）+ 灰色风位块（对家/左家），天凤凸台风位标记样式
            WindCornerChip(dealerWind, true, g(19f), Alignment.BottomStart, g(3f), g(3f))
            WindCornerChip(oppWind, false, g(15f), Alignment.TopEnd, g(3f), g(3f))
            WindCornerChip(leftWind, false, g(15f), Alignment.TopStart, g(3f), g(3f))
            // 点击掷骰（开局仪式：覆盖凸台的透明点击层）
            if (!diceTapped) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable { diceTapped = true }
                )
            }
        }
        // 「点击掷骰子」呼吸提示（凸台正下方，不遮 LED）
        if (!diceTapped && diceFade.value > 0.01f) {
            val hintAlpha = rememberInfiniteTransition().animateFloat(
                initialValue = 0.45f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse)
            ).value
            Text(
                "点击掷骰子",
                color = Color(0xFFFFE082), fontSize = with(density) { g(17f).toSp() },
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = plateSz / 2 + g(12f))
                    .graphicsLayer { alpha = hintAlpha * diceFade.value }
            )
        }
        // 「第 X 局」金色大字（参考视频「東一局」开场字样，掷骰开始后淡出）
        if (!diceTapped && wallIn.value > 0.9f) {
            Text(
                "第 ${snap.round} 局",
                color = Color(0xFFE8C860), fontSize = 40.sp, fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -plateSz / 2 - g(46f))
                    .shadow(4.dp)
            )
        }

        // ================= 中间计时器（我的决策倒计时，压在罗盘中心） =================
        val win = turnWindow
        if (win != null && deal.value >= 1f && snap.result == null) {
            var nowMs by remember(win.endAt) { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(win.endAt) {
                while (System.currentTimeMillis() < win.endAt) {
                    nowMs = System.currentTimeMillis(); delay(200)
                }
                nowMs = win.endAt
            }
            val remainMs = (win.endAt - nowMs).coerceAtLeast(0L)
            if (remainMs > 0L) {
                val urgent = remainMs <= 5000L
                val pulse = rememberInfiniteTransition().animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse)
                ).value
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(g(58f))
                        .graphicsLayer {
                            val s = if (urgent) 1f + 0.05f * pulse else 1f
                            scaleX = s; scaleY = s
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color(0xE60D201B))
                        drawArc(
                            color = if (urgent) Color(0xFFFF5A48) else Color(0xFFFFD54F),
                            startAngle = -90f,
                            sweepAngle = 360f * (remainMs.toFloat() / win.totalMs.toFloat()),
                            useCenter = false,
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        "${(remainMs + 999) / 1000}",
                        color = if (urgent) Color(0xFFFF8A80) else Color(0xFFFFE082),
                        fontSize = with(density) { g(26f).toSp() },
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }

        // 掷骰定格展示 → 发牌开始后骰子淡出、LED 亮相（diceFade 已在凸台内消费）
        LaunchedEffect(dealDone) { if (dealDone) diceFade.animateTo(0f, tween(260, easing = LinearEasing)) }

        // ================= 他家手牌背与副露（APK onDraw 顺序：PE 右 → PN 上 → PW 左） =================
        // ⚠ APK onDraw: DrawFlatPE>PN>PW>DrawFlatGived(牌河)>DrawFlatPS —— 他家副露先画、牌河后画，
        //   牌河盖住副露下缘；旧版副露后画导致对家碰吃杠牌面压住牌河（用户反馈），按 APK 顺序重排。
        // 右家手牌背：cemian2 竖列，x=702，步距 22 重叠
        // 掷骰阶段 deal=0 不渲染（牌都在牌墙摞里，由方城表现）；发牌时从右列墙摞起飞
        if (deal.value > 0f) repeat(right.handCount.coerceIn(1, 14)) { idx ->
            Place(fx(702f), fy(80f) + g(22f) * idx) {
                Box(
                    Modifier.graphicsLayer {
                        // 一摞 4 张绕桌逆时针发（我→右→上→左）：右家落后我一摞错位 60
                        val p = ((deal.value * 1200f - 60f - (idx / 4) * 150f - (idx % 4) * 35f) / 280f).coerceIn(0f, 1f)
                        alpha = 0.25f + 0.75f * p
                        val s = 0.7f + 0.3f * p
                        scaleX = s; scaleY = s
                        // 从右列牌墙摞飞入落位
                        translationX = (wallColR + g(5f) - fx(702f)).toPx() * (1f - p)
                        translationY = (fy(210f) - fy(80f) - g(22f) * idx).toPx() * (1f - p)
                    }
                ) { MjBackTile(1, u) }
            }
        }
        // 右家副露：手牌列下方竖排（APK PE 字节码实测：全部牌横躺 dir1 步距30；
        // 仅明杠第4张直立 dir0 并盖 cc1 背（0/30/60+叠15 为暗杠4背）——
        // 不可把吃碰的供牌画成直立（1.4.10 行为），真机观感为牌列里突兀一块立牌）
        run {
            var myY = fy(80f) + g(22f) * (right.handCount.coerceIn(1, 14) - 1) + fy(31f)
            right.melds.forEach { m ->
                val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                if (claimed < 0 && m.tiles.size >= 4) {
                    Place(fx(702f), myY) { MjMeldBack(false, u) }
                    Place(fx(702f), myY + g(30f)) { MjMeldBack(false, u) }
                    Place(fx(702f), myY + g(60f)) { MjMeldBack(false, u) }
                    Place(fx(702f), myY + g(15f)) { MjMeldBack(false, u) }
                    myY += g(95f)
                } else {
                    var ty = myY
                    m.tiles.forEachIndexed { ti, t ->
                        if (m.tiles.size >= 4 && ti == 3) {
                            Place(fx(702f), ty) { MjSmallTile(t.code, 0, u) }
                            Place(fx(702f), ty) { MjMeldBack(false, u) }
                            ty += g(30f)
                        } else {
                            Place(fx(702f), ty) { MjSmallTile(t.code, 1, u) }; ty += g(30f)
                        }
                    }
                    myY = ty + fy(5f)
                }
            }
        }
        // 上家手牌背：cemian3 横排相邻，x=200 起步，步距 32
        if (deal.value > 0f) repeat(top.handCount.coerceIn(1, 14)) { idx ->
            Place(fx(200f) + g(32f) * idx, fy(78f)) {
                Box(
                    Modifier.graphicsLayer {
                        // 对家落后两摞错位 120
                        val p = ((deal.value * 1200f - 120f - (idx / 4) * 150f - (idx % 4) * 35f) / 280f).coerceIn(0f, 1f)
                        alpha = 0.25f + 0.75f * p
                        val s = 0.7f + 0.3f * p
                        scaleX = s; scaleY = s
                        // 从上排牌墙摞飞入落位
                        translationX = (wallXStart + wallRowW / 2f - fx(200f) - g(32f) * idx).toPx() * (1f - p)
                        translationY = (wallYTop + g(20f) - fy(78f)).toPx() * (1f - p)
                    }
                ) { MjBackTile(2, u) }
            }
        }
        // 上家副露：手牌排右侧横排（APK PN 字节码实测：全部牌直立 dir2 步距51——
        // 供牌不做横躺特判（1.4.10 把供牌画成横躺 dir3，真机上与牌河混成一条怪列）；
        // 暗杠=4张 cc2 背（第4张叠第2位上移12）；明杠=3张 dir2 + cc2 背盖在第2槽上移12，第4张不画）
        run {
            var mx2 = fx(200f) + g(32f) * top.handCount.coerceIn(1, 14) + fx(10f)
            top.melds.forEach { m ->
                val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                if (claimed < 0 && m.tiles.size >= 4) {
                    Place(mx2, fy(78f)) { MjMeldBack(true, u) }
                    Place(mx2 + g(48f), fy(78f)) { MjMeldBack(true, u) }
                    Place(mx2 + g(96f), fy(78f)) { MjMeldBack(true, u) }
                    Place(mx2 + g(48f), fy(78f) - g(12f)) { MjMeldBack(true, u) }
                    mx2 += g(144f) + fx(10f)
                } else {
                    var tx = mx2
                    m.tiles.forEachIndexed { ti, t ->
                        when {
                            // 明杠第4张不画面（APK PN：cc2 背盖第2槽）
                            m.tiles.size >= 4 && ti == 3 -> {}
                            else -> { Place(tx, fy(78f)) { MjTableTile(t.code, 2, u) }; tx += g(51f) }
                        }
                    }
                    if (m.tiles.size >= 4) {
                        // APK PN：cc2 背盖在从右数第2槽（tx 已进3格 → 回退2格），上移12设计px
                        Place(tx - g(51f) * 2, fy(78f) - g(12f)) { MjGangCover(u) }
                    }
                    mx2 = tx + fx(10f)
                }
            }
        }
        // 左家副露+手牌背（APK PW 字节码实测：全部牌横躺 dir3 步距30——
        // 仅明杠第4张直立 dir0 并盖 cc1 背；暗杠=4张 cc1 背0.9——
        // 不可把吃碰的供牌画成直立（1.4.10 行为），真机观感为牌列里突兀一块立牌）
        run {
            var myY = fy(78f)
            left.melds.forEach { m ->
                val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                if (claimed < 0 && m.tiles.size >= 4) {
                    Place(fx(86f), myY) { MjMeldBack(false, u) }
                    Place(fx(86f), myY + g(30f)) { MjMeldBack(false, u) }
                    Place(fx(86f), myY + g(60f)) { MjMeldBack(false, u) }
                    Place(fx(86f), myY + g(15f)) { MjMeldBack(false, u) }
                    myY += g(95f)
                } else {
                    var ty = myY
                    m.tiles.forEachIndexed { ti, t ->
                        if (m.tiles.size >= 4 && ti == 3) {
                            Place(fx(86f), ty) { MjSmallTile(t.code, 0, u) }
                            Place(fx(86f), ty) { MjMeldBack(false, u) }
                            ty += g(30f)
                        } else {
                            Place(fx(86f), ty) { MjSmallTile(t.code, 3, u) }; ty += g(30f)
                        }
                    }
                    myY = ty + fy(5f)
                }
            }
            // 手牌背：cemian4 竖列，副露之下，步距 22 重叠
            val handTop = myY + fy(5f)
            if (deal.value > 0f) repeat(left.handCount.coerceIn(1, 14)) { idx ->
                Place(fx(86f), handTop + g(22f) * idx) {
                    Box(
                        Modifier.graphicsLayer {
                            // 左家落后三摞错位 180
                            val p = ((deal.value * 1200f - 180f - (idx / 4) * 150f - (idx % 4) * 35f) / 280f).coerceIn(0f, 1f)
                            alpha = 0.25f + 0.75f * p
                            val s = 0.7f + 0.3f * p
                            scaleX = s; scaleY = s
                            // 从左列牌墙摞飞入落位
                            translationX = (wallColL + g(5f) - fx(86f)).toPx() * (1f - p)
                            translationY = (fy(200f) - handTop - g(22f) * idx).toPx() * (1f - p)
                        }
                    ) { MjBackTile(3, u) }
                }
            }
        }

        // ================= 四家牌河（参考视频天凤 6 列网格，围绕中央凸台） =================
        // 下家方向语义：pos0=我(下) dir0 / pos1=右 dir1 / pos2=上 dir2 / pos3=左 dir3
        // 牌河小牌（参考视频牌河牌约为副露一半）：立牌 23x35u / 横牌 29x29u
        // 我方：凸台正下方 6 列，行满向墙方向生长；对家镜像（右→左）；左右家列贴凸台侧缘
        val rivW = g(23.5f)                       // 牌河立牌宽
        val rivColStep = g(34f)
        val rivRowStep = fy(36f)
        val rivLatStep = fy(29f)
        val rivLatColStep = fx(20.5f)
        val rivC0 = (W - rivColStep * 5f - rivW) / 2f   // 6 列首列 x
        run {
            me.river.forEachIndexed { i, t ->
                val col = i % 6
                val row = i / 6
                Place(
                    rivC0 + rivColStep * col,
                    fy(285f) + rivRowStep * row
                ) { FlyIn(0, t.id) { MjTableTile(t.code, 0, u, scale = 0.62f) } }
            }
        }
        // 对家牌河：镜像，最右起步向左，行向上生长
        run {
            top.river.forEachIndexed { i, t ->
                val col = i % 6
                val row = i / 6
                Place(
                    rivC0 + rivColStep * (5 - col),
                    fy(163f) - rivRowStep * row
                ) { FlyIn(2, t.id) { MjTableTile(t.code, 2, u, scale = 0.62f) } }
            }
        }
        // 左家牌河：列贴凸台左缘，列内竖排向下、新列向左（朝墙）生长
        left.river.forEachIndexed { i, t ->
            val (col, r) = lieRiverCell6(i)
            Place(
                fx(345f) - rivLatColStep * col,
                fy(154f) + rivLatStep * r
            ) { FlyIn(3, t.id) { MjTableTile(t.code, 3, u, scale = 0.62f) } }
        }
        // 右家牌河：列贴凸台右缘，列内竖排向下、新列向右（朝墙）生长
        // ⚠ 保留 APK 右家倒序绘制：旧牌(下)盖新牌(上)下缘，刻字不被遮挡
        val rRiverCount = right.river.size
        for (ri in rRiverCount - 1 downTo 0) {
            val (rc, rr) = lieRiverCell6(ri)
            Place(
                fx(448f) + rivLatColStep * rc,
                fy(154f) + rivLatStep * rr
            ) { FlyIn(1, right.river[ri].id) { MjTableTile(right.river[ri].code, 1, u, scale = 0.62f) } }
        }

        // ================= 我方副露（DrawFlatPS，APK 中晚于牌河绘制） =================
        /** 供牌者给的牌 = tiles.last()（引擎规则），横躺/转向摆放；暗杠(from<0)加压杆 */
        // 我方：左边缘向右横排；立牌 y=H-76、供牌横躺 y=H-64；步距 51/64
        run {
            val yNorm = H - g(76f)
            val yClaim = H - g(64f)
            var mx = 0.dp
            me.melds.forEach { m ->
                val meldStart = mx
                val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                m.tiles.forEachIndexed { ti, t ->
                    if (ti == claimed) { Place(mx, yClaim) { MjTableTile(t.code, 3, u) }; mx += g(64f) }
                    else { Place(mx, yNorm) { MjTableTile(t.code, 0, u) }; mx += g(51f) }
                }
                if (claimed < 0 && m.tiles.size >= 4) Place(meldStart, yNorm - g(10f)) { MjGangCover(u) }
                mx += fx(10f)
            }
        }

        // ================= 我方手牌（整行含摸牌槽整体居中） =================
        // 摸到的牌（引擎 lastDrawnId）不参与排序，固定在第 14 墩（隔 34）单独展示；
        // ⚠旧版右锚公式（xStart 基于满 14 坡宽度右移）在副露后（普通牌≤10）会把摸牌排到屏幕外，
        //   表现为"吃碰杠后看不见摸牌、像先打牌再摸牌"——改为整行（普通牌+间隙+摸牌）居中：
        val hand = me.hand
        val drawnId = snap.drawnTileId
        val normalHand = hand.filter { it.id != drawnId }
        val drawnTile = hand.firstOrNull { it.id == drawnId }
        val bigW = g(89f)
        val bigH = g(128f)
        val drawnGap = g(34f)
        val handRowW = bigW * normalHand.size +
            (if (drawnTile != null) drawnGap + bigW else 0.dp)
        val xStart = (W - handRowW) / 2
        val yHand = H - bigH

        @Composable
        fun HandTile(idx: Int, x: Dp, t: MjTile) {
            val raised = t.id in selected
            Place(x, if (raised) yHand - fy(25f) else yHand) {
                MjBigTile(
                    t.code, u,
                    laiziMark = snap.laiziCode == t.code,
                    modifier = Modifier
                        .graphicsLayer {
                            val p = deal.value
                            val e = if (p >= 1f) 1f else {
                                // 一摞 4 张逐摞飞入（每摞 150，摞内间隔 35）
                                val xx = ((p * 1200f - (idx / 4) * 150f - (idx % 4) * 35f) / 320f).coerceIn(0f, 1f)
                                1f - (1f - xx) * (1f - xx) * (1f - xx)
                            }
                            if (e < 1f) {
                                // 从下排牌墙摞起飞（与牌墙方城同源，一摞一摞发到手）
                                val srcX = wallXStart + wallPitch * (if ((idx / 4) % 2 == 0) 4f else 12f)
                                val srcY = wallYBot + g(10f)
                                translationX = (srcX - xStart - bigW * idx).toPx() * (1f - e)
                                translationY = (srcY - yHand).toPx() * (1f - e)
                                val s = 0.5f + 0.5f * e
                                scaleX = s; scaleY = s
                                alpha = e
                            } else {
                                translationX = 0f; translationY = 0f
                                scaleX = 1f; scaleY = 1f; alpha = 1f
                            }
                        }
                        .clickable {
                            if (!dealDone) {
                                toast = System.nanoTime() to "发牌中，请稍候"
                            } else if (t.id in selected) {
                                val err = vm.discardSelected()
                                if (err != null) toast = System.nanoTime() to err
                            } else vm.toggleSelect(t.id)
                        },
                    // 发牌盖牌：飞入到位后逐张翻开（绿背淡出上飘，APK gaipai 样式）
                    content = {
                        Image(
                            painter = painterResource(R.drawable.mjdeal_cover),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .size(u * 89f, u * 123f)
                                .graphicsLayer {
                                    val fp = ((deal.value * 1200f - (520f + (idx / 4) * 150f + (idx % 4) * 35f)) / 200f).coerceIn(0f, 1f)
                                    alpha = 1f - fp
                                    translationY = -u.toPx() * 14f * fp
                                }
                        )
                    }
                )
            }
        }
        normalHand.forEachIndexed { i, t -> HandTile(i, xStart + bigW * i, t) }
        drawnTile?.let { t -> HandTile(13, xStart + bigW * normalHand.size + drawnGap, t) }

        // ================= 中央提示 =================
        val myTurn = snap.phase == MjPhase.PLAYING && snap.turn == mySeat
        val centerHint = when {
            snap.phase == MjPhase.DINGQUE ->
                if (me.dingque >= 0) "等待其他玩家定缺…" else "请选择要缺的花色"
            snap.phase == MjPhase.SWAP3 ->
                if (snap.swapPicked) "等待其他玩家换三张…" else "选 3 张同花色牌与${swapDirLabel(snap.swapDir)}交换"
            myTurn && snap.awaitingDiscard -> "轮到你出牌"
            snap.phase == MjPhase.PLAYING -> "等「${seats.getOrNull(snap.turn)?.name ?: "?"}」…"
            else -> ""
        }
        if (centerHint.isNotEmpty()) {
            Text(
                centerHint,
                color = Color(0xFFE6C36A), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    // 牌墙方城环已占罗盘四周，提示移到顶部空带（工具条与上家手牌之间）
                    .align(Alignment.TopCenter)
                    .offset(y = g(52f))
            )
        }

        // ================= 操作区（手牌上方居中：横条按钮 / 宣告按钮） =================
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bigH + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (snap.myHu && dealDone) {
                    // 自摸胡：摸牌后已能胡时直接亮出胡按钮（用户反馈"已胡无提示"）
                    MjImageButton(R.drawable.mj_btn_hu, 46.dp) {
                        val err = vm.selfHu()
                        if (err != null) toast = System.nanoTime() to err
                    }
                }
                if (snap.anGangCodes.isNotEmpty()) {
                    MjPillButton("暗杠", mjOrangeBrush()) {
                        val err = vm.declareGang(snap.anGangCodes.first(), bu = false, tileId = -1)
                        if (err != null) toast = System.nanoTime() to err
                    }
                }
                if (snap.buGangIds.isNotEmpty()) {
                    MjPillButton("补杠", mjOrangeBrush()) {
                        val err = vm.declareGang(-1, bu = true, tileId = snap.buGangIds.first())
                        if (err != null) toast = System.nanoTime() to err
                    }
                }
                if (myTurn && snap.awaitingDiscard && dealDone) {
                    MjRectButton(R.drawable.mj_btn_hint, 30.dp) {
                        val err = vm.hint()
                        if (err != null) toast = System.nanoTime() to err
                    }
                    MjRectButton(R.drawable.mj_btn_discard, 30.dp) {
                        val err = vm.discardSelected()
                        if (err != null) toast = System.nanoTime() to err
                    }
                }
                if (snap.phase == MjPhase.SWAP3 && !snap.swapPicked) {
                    MjRectButton(R.drawable.mj_btn_swap3, 30.dp) {
                        val err = vm.confirmSwap()
                        if (err != null) toast = System.nanoTime() to err
                    }
                }
            }
            if (snap.myClaims.isNotEmpty()) {
                // 宣告按钮：胡>杠>碰>吃排序；多种吃法在按钮上方展示所吃三张便于区分；每行最多 4 个防溢出
                val order = mapOf("HU" to 0, "GANG" to 1, "PENG" to 2, "CHI" to 3)
                val claimRows = snap.myClaims.sortedBy { order[it.kind] ?: 9 }.chunked(4)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    claimRows.forEachIndexed { ri, chunk ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunk.forEach { opt ->
                                when (opt.kind) {
                                    "CHI" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        // 该吃法实际吃成的顺子三张（含打出的那张），区分多种吃
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(Color(0xB30E2B26))
                                                .padding(horizontal = 3.dp, vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            (opt.chiMid - 1..opt.chiMid + 1).forEach { c ->
                                                MjTileView(tile = codeTile(c), w = 17.dp)
                                            }
                                        }
                                        MjImageButton(R.drawable.mj_btn_chi, 46.dp) { vm.doClaim(opt) }
                                    }
                                    "HU" -> MjImageButton(R.drawable.mj_btn_hu, 46.dp) { vm.doClaim(opt) }
                                    "GANG" -> MjImageButton(R.drawable.mj_btn_gang, 46.dp) { vm.doClaim(opt) }
                                    "PENG" -> MjImageButton(R.drawable.mj_btn_peng, 46.dp) { vm.doClaim(opt) }
                                }
                            }
                            if (ri == claimRows.lastIndex) {
                                MjImageButton(R.drawable.mj_btn_pass, 42.dp) { vm.passClaim() }
                            }
                        }
                    }
                }
            }
        }

        // ================= 定缺（三色圆钮） =================
        if (snap.phase == MjPhase.DINGQUE && me.dingque < 0) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bigH + g(40f)),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                MjSuitCircle(0, "万", listOf(Color(0xFFEF6A5A), Color(0xFFB3271D)), 46.dp) { vm.dingque(it) }
                MjSuitCircle(2, "条", listOf(Color(0xFF63D8B8), Color(0xFF1D8574)), 46.dp) { vm.dingque(it) }
                MjSuitCircle(1, "筒", listOf(Color(0xFFFFB84D), Color(0xFFD97A16)), 46.dp) { vm.dingque(it) }
            }
        }

        // ================= 玩家信息（APK DrawFlatAvatar 1:1） =================
        // 座位0(我): 头像(0,329) 名字(55,339)；座位1(右): (740,110)+(725,170)；
        // 座位2(上): (542,0)+(600,0)；座位3(左): (0,110)+(0,170)
        MjApkCard(me, 0)
        MjApkCard(right, 1)
        MjApkCard(top, 2)
        MjApkCard(left, 3)

        // ================= 快捷喊话 =================
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 8.dp)
        ) { StripChatButton { showChat = true } }

        androidx.compose.animation.AnimatedVisibility(
            visible = showChat,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            QuickChatPanel(
                onSend = { p ->
                    vm.localChat(p)
                    myBubble = System.nanoTime() to p
                },
                onDismiss = { showChat = false }
            )
        }

        // ================= 结算浮层 =================
        if (snap.result != null) {
            MjResultOverlay(
                snap = snap,
                mySeat = mySeat,
                onAgain = { vm.again() },
                onExit = onExit
            )
        }

        // ================= 特效层 =================
        if (bigScale.value > 0.01f) {
            bigFx?.let { (_, res, label) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer {
                            scaleX = bigScale.value
                            scaleY = bigScale.value
                            alpha = bigScale.value
                        }
                    ) {
                        Image(
                            painter = painterResource(res),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(132.dp, 112.dp)
                        )
                        if (label.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                label,
                                fontSize = 20.sp, fontWeight = FontWeight.Black,
                                color = Color(0xFFFFD54F)
                            )
                        }
                    }
                }
            }
        }
        toast?.let { (_, t) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    t,
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = bigH + 26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
        if (myBubble != null) {
            myBubble?.let { (_, t) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    Text(
                        t,
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier
                            .padding(start = 60.dp, bottom = 70.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x99122A44))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

// ================================================================ 通用小部件

/** 座位相对庄家的风字（0=東 庄 / 1=南 / 2=西 / 3=北） */
private fun charWind(rel: Int) = when (rel) { 0 -> "東"; 1 -> "南"; 2 -> "西"; else -> "北" }

/** 凸台风位小方块（参考视频天凤样式：场风=红底白字，其余灰底暗字） */
@Composable
internal fun BoxScope.WindCornerChip(
    ch: String, red: Boolean, sz: Dp,
    align: Alignment, padX: Dp, padY: Dp
) {
    Box(
        Modifier
            .align(align)
            .offset(x = padX, y = padY)
            .size(sz)
            .clip(RoundedCornerShape(sz / 5))
            .background(if (red) Color(0xFFC62828) else Color(0xFF3A3E46))
            .border(
                0.8.dp,
                if (red) Color(0xFFE57373) else Color(0xFF565B64),
                RoundedCornerShape(sz / 5)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            ch,
            color = if (red) Color.White else Color(0xFFB9BEC7),
            fontSize = with(LocalDensity.current) { (sz * 0.62f).toSp() },
            fontWeight = FontWeight.Bold
        )
    }
}

/** 七段 LED 数字串（天凤凸台剩余牌数样式：亮段青蓝、暗段深蓝灰） */
@Composable
internal fun SevenSegNum(value: Int, segH: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(segH * 0.16f)) {
        value.toString().forEach { c -> SevenSegDigit(c - '0', segH) }
    }
}

@Composable
private fun SevenSegDigit(d: Int, segH: Dp) {
    Canvas(Modifier.size(segH * 0.62f, segH)) {
        val w = size.width
        val h = size.height
        val t = h * 0.13f                       // 段厚
        val gapT = h * 0.045f                   // 段间缝
        val seg = { on: Boolean, l: Float, tp: Float, r: Float, b: Float, hor: Boolean ->
            val col = if (on) Color(0xFF52C7F2) else Color(0x24123A4E)
            if (hor) {
                drawRoundRect(
                    col, Offset(l, tp),
                    Size(r - l, t), CornerRadius(t / 2f, t / 2f)
                )
            } else {
                drawRoundRect(
                    col, Offset(if (l < w / 2) l else l, tp),
                    Size(t, b - tp), CornerRadius(t / 2f, t / 2f)
                )
            }
        }
        val top = 0f
        val midY = h / 2f - t / 2f
        val botY = h - t
        val leftX = 0f
        val rightX = w - t
        val segH2 = h / 2f - gapT               // 竖段长度
        // a 顶 / g 中 / d 底
        seg(d in setOf(0, 2, 3, 5, 6, 7, 8, 9), t * 0.6f, top, w - t * 0.6f, 0f, true)
        seg(d in setOf(2, 3, 4, 5, 6, 8, 9), t * 0.6f, midY, w - t * 0.6f, 0f, true)
        seg(d in setOf(0, 2, 3, 5, 6, 8, 9), t * 0.6f, botY, w - t * 0.6f, 0f, true)
        // f 左上 / b 右上 / e 左下 / c 右下
        seg(d in setOf(0, 4, 5, 6, 7, 8, 9), leftX, top + t * 0.6f, 0f, top + segH2, false)
        seg(d in setOf(0, 1, 2, 3, 4, 7, 8, 9), rightX, top + t * 0.6f, 0f, top + segH2, false)
        seg(d in setOf(0, 2, 6, 8), leftX, top + segH2 + t * 0.6f, 0f, botY, false)
        seg(d in setOf(0, 1, 3, 4, 5, 6, 7, 8, 9), rightX, top + segH2 + t * 0.6f, 0f, botY, false)
    }
}

@Composable
internal fun MjToolButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (danger) Color(0xFFFFB3AB) else Color(0xFFFFE082)
        )
    }
}

/** 素材圆形按钮（胡/碰/杠/吃/过） */
@Composable
internal fun MjImageButton(res: Int, size: Dp, onClick: () -> Unit) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .size(size)
            .shadow(5.dp, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    )
}

/** 素材横条按钮（提示/出牌/换三张，原始比例 126:72） */
@Composable
internal fun MjRectButton(res: Int, h: Dp, onClick: () -> Unit) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .height(h)
            .width(h * 126f / 72f)
            .shadow(4.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    )
}

@Composable
internal fun MjPillButton(text: String, container: Brush, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .shadow(if (enabled) 5.dp else 0.dp, shape)
            .clip(shape)
            .then(if (enabled) Modifier.background(container) else Modifier.background(Color(0x55FFFFFF)))
            .border(1.5.dp, Color(0x80FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Color.White else Color(0x88FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

internal fun mjOrangeBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFFFFC24D), Color(0xFFF07E1E)))

internal fun mjBlueBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFF5B8BE8), Color(0xFF3A63C0)))

internal fun mjGoldBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFFFFD54F), Color(0xFFF9A825)))

/** 素材金色数字串（余牌） */
@Composable
internal fun NumDigits(value: Int, h: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
        value.toString().forEach { c ->
            Image(
                painter = painterResource(NUM_RES[c - '0']),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.size(h * 25f / 32f, h)
            )
        }
    }
}

/** 定缺三色圆钮 */
@Composable
internal fun MjSuitCircle(suit: Int, label: String, colors: List<Color>, size: Dp, onPick: (Int) -> Unit) {
    Box(
        Modifier
            .shadow(6.dp, CircleShape)
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(colors))
            .border(2.5.dp, Color(0xE6FFFFFF), CircleShape)
            .clickable { onPick(suit) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White, fontSize = 20.sp,
            fontWeight = FontWeight.Black
        )
    }
}

// ================================================================ 结算浮层

@Composable
private fun MjResultOverlay(
    snap: MjSnapshot,
    mySeat: Int,
    onAgain: () -> Unit,
    onExit: () -> Unit
) {
    val result = snap.result!!
    val myDelta = result.scoreDelta[mySeat] ?: 0
    val iWon = myDelta > 0
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6102028)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Text(
                when {
                    result.liuju -> "流 局"
                    iWon -> "赢 了 ！"
                    else -> "输 了 …"
                },
                fontSize = 36.sp, fontWeight = FontWeight.Black,
                color = if (iWon) Color(0xFFFFD54F) else if (result.liuju) Color(0xFFB0BEC5) else Color(0xFFFF8A80)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (result.note.isNotEmpty()) "${snap.mode.label} · ${result.note}"
                else snap.mode.label,
                fontSize = 12.sp, color = Color(0x99FFE0B2)
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x3D000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                snap.seats.sortedByDescending { result.scoreDelta[it.seat] ?: 0 }
                    .forEach { s ->
                        val delta = result.scoreDelta[s.seat] ?: 0
                        val detail = result.details.firstOrNull { it.seat == s.seat }
                        Row(
                            Modifier.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarImage(s.avatar, 34.dp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f, fill = true)) {
                                Text(
                                    s.name + if (s.huRank > 0) " · 胡牌" else "",
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = if (delta > 0) Gold else Color(0xE6FFFFFF)
                                )
                                Text(
                                    detail?.let { d ->
                                        d.fans.joinToString(" ").ifBlank { "平胡" } + " · 共${d.total}番"
                                    } ?: "未胡",
                                    fontSize = 10.sp, color = Color(0x99FFE0B2)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (delta > 0) {
                                    Image(
                                        painter = painterResource(R.drawable.mj_coin),
                                        contentDescription = "金币",
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                }
                                Text(
                                    if (delta > 0) "+$delta" else "$delta",
                                    fontSize = 16.sp, fontWeight = FontWeight.Black,
                                    color = if (delta > 0) Color(0xFFFFD54F) else Color(0xFFFF8A80)
                                )
                            }
                        }
                    }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MjPillButton("返回大厅", mjBlueBrush()) { onExit() }
                MjPillButton("再来一局", mjGoldBrush()) { onAgain() }
            }
        }
    }
}

private suspend fun flashBig(a: Animatable<Float, *>) {
    a.snapTo(0.2f)
    a.animateTo(1.1f, tween(200))
    delay(650)
    a.animateTo(0f, tween(280))
}

private fun tileIdOfCode(code: Int): Int {
    val suit = code / 9
    val num = code % 9 + 1
    return com.laoxiang.ddz.data.MjTile.idOf(
        when (suit) { 0 -> com.laoxiang.ddz.data.MjSuit.WAN; 1 -> com.laoxiang.ddz.data.MjSuit.TONG; 2 -> com.laoxiang.ddz.data.MjSuit.TIAO; else -> com.laoxiang.ddz.data.MjSuit.ZI },
        num, 0
    )
}

private fun suitLabel(suit: Int) = when (suit) { 0 -> "万"; 1 -> "筒"; else -> "条" }

private fun swapDirLabel(dir: Int) = when (dir) { 1 -> "下家"; 2 -> "对家"; 3 -> "上家"; else -> "隔壁" }
