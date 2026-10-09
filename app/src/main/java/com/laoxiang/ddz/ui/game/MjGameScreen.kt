package com.laoxiang.ddz.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.laoxiang.ddz.data.MjTile
import com.laoxiang.ddz.ui.common.AvatarImage
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 麻将牌局 —— 按用户视频（天凤风格）重制 v2（r2 版式修订）：
 * 圈层自内向外 = 中央骰盒（LED+四边彩带记分+四角风位）→ 四家牌河（紧贴骰盒，
 * 6 张一行；自己立牌正读 / 对家立牌倒读 / 左右横躺；最新一张立起）→
 * 牌墙（深色東刻背+白色牌身双层，沿桌方框三边：上/左/右）→ 侧家白色牌背列（最外）。
 * 牌面渲染 = 原 APK 素材方案（MjTileView v7：psmj 白胚叠面+字节码刻字对中），详见 MjTileView.kt。
 * 左上 HUD（友人戦/模式金匾/点棒/東1局）+ 右上 chain + 左右金色玩家栏 + 底部金框玩家条
 * + 黑金操作按钮居中手牌上方 + 金色读秒。4:3 视频版面 → u = H/480 竖向定标横向铺满。
 */

private val WIND_KANJI = arrayOf("東", "南", "西", "北")

/** 开局掷骰子素材 */
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
                val s = 1f + 0.18f * (if (rolling) kotlin.math.sin(p * Math.PI * 3).toFloat() else 0f)
                scaleX = s; scaleY = s
            }
            .size(size)
    )
}

// ================================================================ LED 七段数码管

private val SEG_MAP = arrayOf(
    "abcdef",  // 0
    "bc",      // 1
    "abged",   // 2
    "abgcd",   // 3
    "fgbc",    // 4
    "afgcd",   // 5
    "afgedc",  // 6
    "abc",     // 7
    "abcdefg", // 8
    "abfgcd"   // 9
)

/** 一位七段数码（天凤 LED 青蓝辉光） */
@Composable
private fun LedDigit(ch: Char, w: Dp, h: Dp) {
    val cyan = Color(0xFF38C4F2)
    Canvas(Modifier.size(w, h)) {
        val t = size.width * 0.16f              // 笔画粗
        val segs = if (ch == '-') "" else SEG_MAP[ch - '0']
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float, on: Boolean) {
            if (!on) return
            val c = Offset(x1, y1); val e = Offset(x2, y2)
            // 辉光
            drawLine(cyan.copy(alpha = 0.28f), c, e, strokeWidth = t * 2.1f, cap = StrokeCap.Round)
            drawLine(cyan, c, e, strokeWidth = t, cap = StrokeCap.Round)
        }
        val m = t * 0.9f                         // 端点缩进
        val L = m; val R = size.width - m
        val T = m; val B = size.height - m
        val Mx = size.height / 2f
        val on = { c: Char -> segs.contains(c) }
        seg(L, T, R, T, on('a'))                 // 上
        seg(R, T, R, Mx, on('b'))                // 右上
        seg(R, Mx, R, B, on('c'))                // 右下
        seg(L, B, R, B, on('d'))                 // 下
        seg(L, Mx, L, B, on('e'))                // 左下
        seg(L, T, L, Mx, on('f'))                // 左上
        seg(L, Mx, R, Mx, on('g'))               // 中
    }
}

/** LED 数字串（居中） */
@Composable
private fun LedNumber(value: Int, h: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(h * 0.12f), verticalAlignment = Alignment.CenterVertically) {
        value.toString().forEach { c -> LedDigit(c, h * 0.62f, h) }
    }
}

// ================================================================ 小部件

/** 竖排文字（视频侧栏样式） */
@Composable
private fun VText(
    text: String, color: Color, sizeSp: androidx.compose.ui.unit.TextUnit,
    maxChars: Int = 6, fontWeight: FontWeight = FontWeight.Bold
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        text.take(maxChars).forEach { ch ->
            Text(ch.toString(), color = color, fontSize = sizeSp, fontWeight = fontWeight, lineHeight = sizeSp * 1.12f)
        }
    }
}

/** 点棒小图（kind: 0=赤点棒 1=八孔点棒） */
@Composable
private fun PointStick(kind: Int, w: Dp) {
    Canvas(Modifier.size(w, w * 0.34f)) {
        val r = size.height / 2f
        drawRoundRect(Color(0xFFF2F0E8), Offset.Zero, size, CornerRadius(r, r))
        drawRoundRect(Color(0xFF9A968C), Offset.Zero, size, CornerRadius(r, r), style = Stroke(1.5f))
        if (kind == 0) {
            drawCircle(Color(0xFFC22A1F), size.height * 0.30f, Offset(size.width * 0.5f, size.height * 0.5f))
        } else {
            val dot = size.height * 0.14f
            for (i in 0 until 4) for (j in 0 until 2) {
                drawCircle(
                    Color(0xFF3A4668), dot,
                    Offset(size.width * (0.30f + 0.13f * i), size.height * (0.36f + 0.28f * j))
                )
            }
        }
    }
}

/** 日本国旗小图（底部玩家条） */
@Composable
private fun JapanFlag(w: Dp) {
    Canvas(Modifier.size(w, w * 0.68f)) {
        drawRoundRect(Color(0xFFF5F4EF), Offset.Zero, size, CornerRadius(1.5f, 1.5f))
        drawCircle(Color(0xFFBC002D), size.height * 0.30f, Offset(size.width / 2f, size.height / 2f))
    }
}

/** 卷轴小图（右上 chain 计数） */
@Composable
private fun ScrollIcon(iconSz: Dp) {
    Canvas(Modifier.size(iconSz, iconSz)) {
        val w = size.width; val h = size.height   // DrawScope.size（像素）
        drawRoundRect(Color(0xFFE8C15C), Offset(w * 0.18f, h * 0.10f), Size(w * 0.64f, h * 0.80f), CornerRadius(w * 0.08f))
        drawLine(Color(0xFF8A6A1E), Offset(w * 0.30f, h * 0.32f), Offset(w * 0.70f, h * 0.32f), 2f)
        drawLine(Color(0xFF8A6A1E), Offset(w * 0.30f, h * 0.50f), Offset(w * 0.70f, h * 0.50f), 2f)
        drawLine(Color(0xFF8A6A1E), Offset(w * 0.30f, h * 0.68f), Offset(w * 0.70f, h * 0.68f), 2f)
        drawCircle(Color(0xFFB8933C), w * 0.14f, Offset(w * 0.18f, h * 0.5f))
        drawCircle(Color(0xFFB8933C), w * 0.14f, Offset(w * 0.82f, h * 0.5f))
    }
}

/** code(0..33) → 任一副本牌（宣告预览用） */
private fun codeTile(code: Int): MjTile {
    val suit = when {
        code < 9 -> com.laoxiang.ddz.data.MjSuit.WAN
        code < 18 -> com.laoxiang.ddz.data.MjSuit.TONG
        code < 27 -> com.laoxiang.ddz.data.MjSuit.TIAO
        else -> com.laoxiang.ddz.data.MjSuit.ZI
    }
    val num = code % 9 + 1
    return MjTile(MjTile.idOf(suit, num, 0), suit, num)
}

private fun tileIdOfCode(code: Int): Int {
    val suit = code / 9
    val num = code % 9 + 1
    return MjTile.idOf(
        when (suit) { 0 -> com.laoxiang.ddz.data.MjSuit.WAN; 1 -> com.laoxiang.ddz.data.MjSuit.TONG; 2 -> com.laoxiang.ddz.data.MjSuit.TIAO; else -> com.laoxiang.ddz.data.MjSuit.ZI },
        num, 0
    )
}

private fun suitLabel(suit: Int) = when (suit) { 0 -> "万"; 1 -> "筒"; else -> "条" }

private fun swapDirLabel(dir: Int) = when (dir) { 1 -> "下家"; 2 -> "对家"; 3 -> "上家"; else -> "隔壁" }

// ================================================================ 主界面

@Composable
fun MjGameScreen(vm: MjViewModel, onExit: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF232327))
    ) {
        val W = maxWidth
        val H = maxHeight
        val density = LocalDensity.current
        // 设计空间 800x480（视频 4:3 版面），u = 每设计单位的 Dp；横向自适应铺满（全屏无黑边）
        val u = H / 480f
        @Composable fun gDp(v: Float) = u * v
        fun gF(v: Float) = u.value * v
        // 牌墙方块宽（设计单位；背景 Canvas 与几何块共用）
        val sqWu = (W.value - 150f).coerceIn(430f, 660f)

        // ---------------- 牌桌背景（视频：深灰桌面+中央方框线+四角斜线+底部凹槽） ----------------
        Canvas(Modifier.fillMaxSize()) {
            fun gx(v: Float) = size.width * v / 800f
            fun gy(v: Float) = size.height * v / 480f
            // 桌面底色 + 轻微中央提亮
            drawRect(Color(0xFF26262A))
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF2D2D32), Color(0xFF232327)),
                    Offset(size.width / 2f, size.height * 0.44f), size.width * 0.62f
                )
            )
            // 外缘暗边（桌沿）
            drawRect(Brush.verticalGradient(listOf(Color(0x66101014), Color(0x00101014), Color(0x55101014))))
            // 方框区（与牌墙方块一致，由外面传入比例换算）
            val cx = size.width / 2f
            val sqL = gx((maxWidth.value - sqWu) / 2f)
            val sqR = gx((maxWidth.value + sqWu) / 2f)
            val sqT = gy(58f)
            val sqB = gy(480f - 90f)   // handY(480-66-10) - 14
            val line = Color(0xFF1B1B1F)
            drawRect(line, Offset(sqL, sqT), Size(sqR - sqL, sqB - sqT), style = Stroke(2f))
            drawRect(
                line.copy(alpha = 0.7f),
                Offset(sqL + (sqR - sqL) * 0.06f, sqT + (sqB - sqT) * 0.06f),
                Size((sqR - sqL) * 0.88f, (sqB - sqT) * 0.88f), style = Stroke(1.5f)
            )
            // 四角斜线
            val d = (sqR - sqL) * 0.09f
            for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                drawLine(
                    line,
                    Offset(if (sx < 0) sqL + (sqR - sqL) * 0.06f else sqR - (sqR - sqL) * 0.06f,
                           if (sy < 0) sqT + (sqB - sqT) * 0.06f else sqB - (sqB - sqT) * 0.06f),
                    Offset(if (sx < 0) sqL + (sqR - sqL) * 0.06f - d else sqR - (sqR - sqL) * 0.06f + d,
                           if (sy < 0) sqT + (sqB - sqT) * 0.06f - d * 0.9f else sqB - (sqB - sqT) * 0.06f + d * 0.9f),
                    2f
                )
            }
            // 底部凹槽（手牌上方长条）
            val grooveY = size.height - gy(96f)
            drawRoundRect(
                Color(0xFF151518),
                Offset(cx - gx(330f), grooveY),
                Size(gx(660f), gy(12f)), CornerRadius(gy(6f))
            )
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
        val me = snap.seats[mySeat.coerceIn(0, 3)]
        val right = snap.seats[(mySeat + 1) % 4]
        val top = snap.seats[(mySeat + 2) % 4]
        val left = snap.seats[(mySeat + 3) % 4]
        val scores = snap.scores
        val selected by vm.selected.collectAsState()

        // ---------------- 开局仪式：掷骰（骰盒内）→ 牌墙/手牌发牌动画 ----------------
        val deal = remember { Animatable(0f) }
        val diceRoll = remember { Animatable(0f) }
        var dicePair by remember { mutableStateOf(intArrayOf(5, 3)) }
        LaunchedEffect(snap.round) {
            deal.snapTo(0f)
            diceRoll.snapTo(0f)
            dicePair = if (snap.dice1 in 1..6 && snap.dice2 in 1..6) intArrayOf(snap.dice1, snap.dice2)
            else intArrayOf(Random.nextInt(1, 7), Random.nextInt(1, 7))
            diceRoll.animateTo(1f, tween(1000, easing = LinearEasing))
            delay(750)
            deal.animateTo(1f, tween(1200, easing = LinearEasing))
        }
        val dealDone = deal.value >= 1f

        // ---------------- 回合读秒（中央 LED + 我方回合右下金数） ----------------
        var turnSec by remember { mutableStateOf(30) }
        LaunchedEffect(snap.turn, snap.round, snap.awaitingDiscard, snap.phase) {
            turnSec = 30
            while (snap.phase == MjPhase.PLAYING && turnSec > 0) {
                delay(1000); turnSec--
            }
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

        // ---------------- 几何基准（圈层：骰盒 → 牌河 → 牌墙 → 侧家手牌） ----------------
        val bigW = u * 46f                          // 手牌宽（视频比例）
        val bigH = bigW * 128f / 89f
        val handY = H - bigH - u * 10f
        val tu = u * 0.52f                          // 桌牌基准（河牌）：立 26.5x39.5 / 躺 33.3 方
        val mu = u * 0.5f                           // 副露小牌基准：立 20.4x30.4 / 躺 25.6 方
        // 牌墙方块（墙沿此方框三边）
        val sqW = sqWu * u
        val sqL = (W - sqW) / 2
        val sqR = sqL + sqW
        val sqT = u * 58f
        val sqB = handY - u * 14f
        // 中央骰盒
        val boxW = u * 150f
        val boxH = u * 126f
        val boxCx = W / 2
        val boxCy = (sqT + sqB) / 2 - u * 6f
        val boxL = boxCx - boxW / 2
        val boxT = boxCy - boxH / 2
        val boxR = boxL + boxW
        val boxB = boxT + boxH
        // 牌河步进（6 张一行）
        val rvUpW = tu * 51f; val rvUpH = tu * 76f          // 立牌盒
        val rvLy = tu * 64f                                  // 躺牌方盒
        val rvStepX = rvUpW + u * 2.5f
        val rvStepY = rvUpH + u * 2.5f
        val rvStepLy = rvLy + u * 2.5f

        /** 绝对定位（BoxScope 接收者，允许 content 内使用 align） */
        @Composable
        fun Place(x: Dp, y: Dp, content: @Composable BoxScope.() -> Unit) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = x, y = y)
            ) { content() }
        }

        // ---------------- 牌墙（深背+白身双层，沿方框上/左/右三边，随发牌进度出现） ----------------
        @Composable
        fun Walls() {
            val wallN = 17
            val wsTop = u * 34f                       // 上墙墩宽（横向）
            val wsSide = (sqB - sqT - u * 10f) / wallN // 侧墙墩距（纵向）
            // 上墙（双层墩：深背在上、白身在下）
            val x0 = boxCx - wsTop * wallN / 2
            repeat(wallN) { i ->
                val p = ((deal.value * 17f - i) / 2f).coerceIn(0f, 1f)
                if (p > 0f) {
                    Place(x0 + wsTop * i, sqT) { TenWallTile(wsTop, 0f, alpha = p) }
                }
            }
            // 左墙（深面朝右=朝中央）
            repeat(wallN) { i ->
                val p = ((deal.value * 17f - i) / 2f).coerceIn(0f, 1f)
                if (p > 0f) {
                    Place(sqL + u * 3f, sqT + u * 5f + wsSide * i) { TenWallTile(wsSide, 270f, alpha = p) }
                }
            }
            // 右墙（深面朝左=朝中央）
            repeat(wallN) { i ->
                val p = ((deal.value * 17f - i) / 2f).coerceIn(0f, 1f)
                if (p > 0f) {
                    Place(sqR - u * 3f - wsSide * 0.62f, sqT + u * 5f + wsSide * i) { TenWallTile(wsSide, 90f, alpha = p) }
                }
            }
            // 王牌/宝牌指示区（右墙上端外侧）：2 张深背 + 癞子指示牌面朝上
            val dy = sqT + u * 2f
            Place(sqR + u * 6f, dy) { TenWallTile(u * 30f, 90f) }
            Place(sqR + u * 6f, dy + u * 30f) { TenWallTile(u * 30f, 90f) }
            if (snap.laiziCode >= 0) {
                Place(sqR + u * 8f, dy + u * 62f) {
                    TenRiverTile(snap.laiziCode, 2, tu, alpha = ((deal.value - 0.6f) * 3f).coerceIn(0f, 1f), scale = 0.55f)
                }
            }
        }
        Walls()

        // ---------------- 侧家/上家手牌（最外层：白色素背列，视频样式） ----------------
        @Composable
        fun SideHands() {
            // 左家：竖列（横躺白背 26x17u）
            val lw = u * 26f; val lh = u * 17f; val lPitch = lh + u * 1.2f
            val lCount = left.handCount.coerceIn(0, 14)
            val lDrawn = lCount % 3 == 2 && lCount > 0       // 14 张 → 摸牌分离
            val colH = lPitch * lCount + if (lDrawn) u * 7f else 0f
            var ly = boxCy - colH / 2
            repeat(lCount) { i ->
                val gap = if (lDrawn && i == lCount - 1) u * 7f else 0.dp
                Place(sqL - lw - u * 5f, ly + gap) {
                    SideHandBack(lw, lh, alpha = if (dealDone) 1f else deal.value)
                }
                ly += lPitch
            }
            // 右家：竖列
            val rCount = right.handCount.coerceIn(0, 14)
            val rDrawn = rCount % 3 == 2 && rCount > 0
            val colH2 = lPitch * rCount + if (rDrawn) u * 7f else 0f
            var ry = boxCy - colH2 / 2
            repeat(rCount) { i ->
                val gap = if (rDrawn && i == rCount - 1) u * 7f else 0.dp
                Place(sqR + u * 5f, ry + gap) {
                    SideHandBack(lw, lh, alpha = if (dealDone) 1f else deal.value)
                }
                ry += lPitch
            }
            // 上家：横排（白背 22x15u，居中于骰盒上方、墙外）
            val tw = u * 22f; val th = u * 15f; val tPitch = tw + u * 1.5f
            val tCount = top.handCount.coerceIn(0, 14)
            val tDrawn = tCount % 3 == 2 && tCount > 0
            val rowW = tPitch * tCount + if (tDrawn) u * 7f else 0f
            var tx = boxCx - rowW / 2
            repeat(tCount) { i ->
                val gap = if (tDrawn && i == tCount - 1) u * 7f else 0.dp
                Place(tx + gap, sqT - u * 24f) {
                    SideHandBack(tw, th, alpha = if (dealDone) 1f else deal.value)
                }
                tx += tPitch
            }
        }
        SideHands()

        // ---------------- 中央骰盒（黑垫+四角风位+LED+四边彩带记分+骰子仪式） ----------------
        @Composable
        fun CenterBox() {
            Place(boxL, boxT) {
                Box(Modifier.size(boxW, boxH)) {
                    // 黑垫
                    Canvas(Modifier.matchParentSize()) {
                        val r = size.width * 0.09f
                        drawRoundRect(Color(0xFF0A0C11), Offset(0f, 0f), size, CornerRadius(r, r))
                        drawRoundRect(
                            Brush.verticalGradient(listOf(Color(0xFF181C26), Color(0xFF07080C))),
                            Offset(size.width * 0.03f, size.height * 0.03f),
                            Size(size.width * 0.94f, size.height * 0.94f), CornerRadius(r, r)
                        )
                    }
                    // 四角风位牌（圆风=东→红）
                    val roundWind = (snap.round / 4) % 4
                    val plaque = gDp(24f)
                    val posWind = arrayOf(
                        (0 - (snap.dealer - mySeat) + 4) % 4,     // 我（左下）
                        (1 - (snap.dealer - mySeat) + 4) % 4,     // 右（右下）
                        (2 - (snap.dealer - mySeat) + 4) % 4,     // 上（右上）
                        (3 - (snap.dealer - mySeat) + 4) % 4      // 左（左上）
                    )
                    val corners = listOf(
                        Triple(0f, 1f, 0),      // 左下=我
                        Triple(1f, 1f, 1),      // 右下=右
                        Triple(1f, 0f, 2),      // 右上=上
                        Triple(0f, 0f, 3)       // 左上=左
                    )
                    corners.forEach { (fx, fy, seatK) ->
                        val windIdx = posWind[seatK]
                        val isRed = windIdx == roundWind
                        val px = boxW * fx + (if (fx == 0f) gDp(5f) else -plaque - gDp(5f))
                        val py = boxH * fy + (if (fy == 0f) gDp(5f) else -plaque - gDp(5f))
                        Box(
                            Modifier
                                .offset(x = px, y = py)
                                .size(plaque, plaque)
                                .clip(RoundedCornerShape(gDp(4f)))
                                .background(if (isRed) Color(0xFFC22A1F) else Color(0xFF191D27))
                                .border(1.dp, Color(0xFF3A4150), RoundedCornerShape(gDp(4f))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                WIND_KANJI[windIdx],
                                color = if (isRed) Color(0xFF14100E) else Color(0xFF707888),
                                fontSize = with(density) { (plaque.value * 0.56f).sp },
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                    // LED 八角屏读秒
                    if (diceRoll.value == 0f || deal.value > 0f) {
                        Box(Modifier.align(Alignment.Center).offset(y = -gDp(8f))) {
                            Canvas(Modifier.matchParentSize()) {
                                val w = size.width; val h = size.height
                                val c = w * 0.26f
                                val oct = Path().apply {
                                    moveTo(c, 0f); lineTo(w - c, 0f)
                                    lineTo(w, h * 0.28f); lineTo(w, h * 0.72f)
                                    lineTo(w - c, h); lineTo(c, h)
                                    lineTo(0f, h * 0.72f); lineTo(0f, h * 0.28f)
                                    close()
                                }
                                drawPath(oct, Color(0xFF04050A))
                                drawPath(oct, Color(0xFF232B3A), style = Stroke(1.5f))
                            }
                            Box(Modifier.size(gDp(86f), gDp(54f)), contentAlignment = Alignment.Center) {
                                LedNumber(turnSec.coerceIn(0, 99), gDp(34f))
                            }
                        }
                    }
                    // 四边彩带记分：下(我)/右/上/左 —— 行动家槽位蓝彩带（视频样式）
                    val active = (snap.turn - mySeat + 4) % 4   // 0=我 1=右 2=上 3=左
                    val scoreFs = with(density) { gDp(13f).toSp() }
                    @Composable
                    fun ScoreRibbon(value: Int, isActive: Boolean, side: Int) {
                        val rot = when (side) { 1 -> 90f; 3 -> -90f; else -> 0f }
                        val align = when (side) {
                            0 -> Alignment.BottomCenter; 2 -> Alignment.TopCenter
                            1 -> Alignment.CenterEnd; else -> Alignment.CenterStart
                        }
                        val off = when (side) {
                            0 -> Dp(0f) to -gDp(13f); 2 -> Dp(0f) to gDp(13f)
                            1 -> -gDp(15f) to Dp(0f); else -> gDp(15f) to Dp(0f)
                        }
                        Box(
                            Modifier
                                .align(align)
                                .offset(x = off.first, y = off.second)
                                .graphicsLayer { rotationZ = rot }
                        ) {
                            if (isActive) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(gDp(4f)))
                                        .background(Brush.horizontalGradient(listOf(Color(0xFF1857C4), Color(0xFF3D8DF0))))
                                        .padding(horizontal = gDp(6f), vertical = gDp(1f))
                                ) {
                                    Text("$value", color = Color.White, fontSize = scoreFs, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text("$value", color = Color(0xFFE8B33B), fontSize = scoreFs, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    ScoreRibbon(scores.getOrElse(mySeat.coerceIn(0, 3)) { 0 }, active == 0, 0)
                    ScoreRibbon(scores.getOrElse(right.seat.coerceIn(0, 3)) { 0 }, active == 1, 1)
                    ScoreRibbon(scores.getOrElse(top.seat.coerceIn(0, 3)) { 0 }, active == 2, 2)
                    ScoreRibbon(scores.getOrElse(left.seat.coerceIn(0, 3)) { 0 }, active == 3, 3)
                    // 掷骰仪式：骰子出现在盒中央
                    if (diceRoll.value > 0f && deal.value < 1f) {
                        val diceAlpha = (1f - deal.value * 3f).coerceIn(0f, 1f)
                        if (diceAlpha > 0.01f) {
                            Row(
                                Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer { alpha = diceAlpha },
                                horizontalArrangement = Arrangement.spacedBy(gDp(12f))
                            ) {
                                DiceDie(dicePair[0], diceRoll.value < 1f, diceRoll.value, gDp(34f))
                                DiceDie(dicePair[1], diceRoll.value < 1f, diceRoll.value, gDp(34f))
                            }
                        }
                    }
                }
            }
        }
        CenterBox()

        // ---------------- 四家牌河（内圈紧贴骰盒；6张一行；最新一张立起；可鸣青蓝高亮） ----------------
        val canClaim = snap.myClaims.isNotEmpty()
        val lastRiverSeat = snap.lastDiscardSeat

        @Composable
        fun River(seatIdx: Int, tiles: List<MjTile>) {
            if (tiles.isEmpty()) return
            val dir = seatIdx               // 0=我 1=右 2=上 3=左
            val horizontal = dir == 0 || dir == 2
            val perRow = 6
            tiles.forEachIndexed { i, t ->
                val isLast = i == tiles.lastIndex
                if (isLast && !dealDone) return@forEachIndexed   // 发牌中不提前亮牌
                val highlight = isLast && canClaim && lastRiverSeat == (mySeat + seatIdx) % 4
                val standing = isLast && !horizontal         // 左右两家最新张立起
                val row = i / perRow
                val k = i % perRow
                if (dir == 0) {
                    // 我：行贴盒下缘，从盒左缘向右；换行向手牌方向
                    val x = boxL + rvStepX * k
                    val y = boxB + u * 8f + rvStepY * row
                    Place(x, y) { TenRiverTile(t.code, 0, tu, highlight = highlight) }
                } else if (dir == 2) {
                    // 上家：行贴盒上缘，从盒右缘向左；换行向墙方向
                    val x = boxR - rvUpW - rvStepX * k
                    val y = boxT - u * 8f - rvUpH - rvStepY * row
                    Place(x, y) { TenRiverTile(t.code, 2, tu, highlight = highlight) }
                } else if (dir == 1) {
                    // 右家：列贴盒右缘，从盒底向上升；换列向右（靠墙）
                    val x = boxR + u * 8f + (rvLy + u * 5f) * row
                    var y = boxB
                    for (j in 0 until i) {
                        val hJ = if (j == tiles.lastIndex) rvUpH else rvLy
                        y -= hJ + u * 2.5f
                    }
                    y -= (if (standing) rvUpH else rvLy)
                    Place(x, y) { TenRiverTile(t.code, 1, tu, highlight = highlight, standing = standing) }
                } else {
                    // 左家：列贴盒左缘，从盒顶向下伸；换列向左（靠墙）
                    val x = boxL - u * 8f - rvLy - (rvLy + u * 5f) * row
                    var y = boxT
                    for (j in 0 until i) {
                        val hJ = if (j == tiles.lastIndex) rvUpH else rvLy
                        y += hJ + u * 2.5f
                    }
                    Place(x, y) { TenRiverTile(t.code, 3, tu, highlight = highlight, standing = standing) }
                }
            }
        }
        River(0, me.river)
        River(1, right.river)
        River(2, top.river)
        River(3, left.river)

        // ---------------- 我方副露占宽（供手牌行让位计算） ----------------
        val meldsWu = run {   // 设计单位宽
            var w = 0f
            me.melds.forEach { m ->
                val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                m.tiles.forEachIndexed { ti, _ ->
                    w += (if (ti == claimed) 60.8f else 40.8f) * 0.5f + 1.5f
                }
                w += 5f
            }
            if (w > 0f) w + 4f else 0f
        }
        val meldsW = meldsWu * u

        // ---------------- 四家副露（靠各家手牌位；供牌侧翻） ----------------
        @Composable
        fun Melds(seatIdx: Int, s: MjSeatView) {
            if (s.melds.isEmpty()) return
            val dir = seatIdx
            when (dir) {
                0 -> {   // 我：手牌行左端向左排（行内从左往右铺）
                    val slotW = mu * 40.8f
                    val slotH = mu * 60.8f
                    val y = handY + (bigH - slotH) / 2
                    var mx = u * 6f
                    s.melds.forEach { m ->
                        val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                        m.tiles.forEachIndexed { ti, t ->
                            val isClaimed = ti == claimed
                            Place(mx, y + (if (isClaimed) (slotH - slotW) / 2 else 0.dp)) {
                                TenMeldTile(t.code, 0, mu, claimed = isClaimed)
                            }
                            mx += (if (isClaimed) slotH else slotW) + u * 1.5f
                        }
                        mx += u * 5f
                    }
                }
                1 -> {   // 右家：盒右内侧竖列向下
                    val x = boxR + u * 50f
                    var my2 = boxCy - u * 40f
                    s.melds.forEach { m ->
                        val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                        m.tiles.forEachIndexed { ti, t ->
                            val isClaimed = ti == claimed
                            Place(x, my2) { TenMeldTile(t.code, 1, mu, claimed = isClaimed) }
                            my2 += mu * 51.2f + u * 1.5f
                        }
                        my2 += u * 5f
                    }
                }
                2 -> {   // 上家：墙上方横排向右（上家左手侧）
                    val y = sqT - u * 40f
                    var mx2 = boxCx + u * 80f
                    s.melds.forEach { m ->
                        val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                        m.tiles.forEachIndexed { ti, t ->
                            val isClaimed = ti == claimed
                            Place(mx2, y + (if (isClaimed) (mu * 60.8f - mu * 40.8f) / 2 else 0.dp)) {
                                TenMeldTile(t.code, 2, mu, claimed = isClaimed)
                            }
                            mx2 += (if (isClaimed) mu * 60.8f else mu * 40.8f) + u * 1.5f
                        }
                        mx2 += u * 5f
                    }
                }
                else -> { // 左家：盒左内侧竖列向下
                    val x = boxL - u * 50f - mu * 51.2f
                    var my3 = boxCy - u * 40f
                    s.melds.forEach { m ->
                        val claimed = if (m.from >= 0) m.tiles.lastIndex else -1
                        m.tiles.forEachIndexed { ti, t ->
                            val isClaimed = ti == claimed
                            Place(x, my3) { TenMeldTile(t.code, 3, mu, claimed = isClaimed) }
                            my3 += mu * 51.2f + u * 1.5f
                        }
                        my3 += u * 5f
                    }
                }
            }
        }
        Melds(0, me)
        Melds(1, right)
        Melds(2, top)
        Melds(3, left)

        // ---------------- 左右竖排玩家栏（视频金色支架+黑底：対局中/名字/位次/数） ----------------
        @Composable
        fun SidePanel(s: MjSeatView, alignRight: Boolean) {
            val rank = 1 + scores.count { it > (scores.getOrElse(s.seat) { 0 }) }
            val panelW = gDp(34f)
            val panelH = gDp(252f)
            val labelFs = with(density) { gDp(10f).toSp() }
            val nameFs = with(density) { gDp(13f).toSp() }
            Place(
                if (alignRight) W - panelW - gDp(3f) else gDp(3f),
                boxCy - panelH / 2
            ) {
                Box(Modifier.size(panelW, panelH)) {
                    Canvas(Modifier.matchParentSize()) {
                        val r = size.width * 0.3f
                        drawRoundRect(
                            Brush.verticalGradient(listOf(Color(0xFF101319), Color(0xFF1A1E28))),
                            Offset.Zero, size, CornerRadius(r, r)
                        )
                        drawRoundRect(
                            Color(0xFFC9A227).copy(alpha = 0.85f),
                            Offset.Zero, size, CornerRadius(r, r), style = Stroke(2f)
                        )
                        // 金色上下钩
                        drawCircle(Color(0xFFD4AF37), size.width * 0.22f, Offset(size.width / 2f, -size.height * 0.01f))
                        drawCircle(Color(0xFF8A6E1C), size.width * 0.16f, Offset(size.width / 2f, size.height * 1.01f))
                    }
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .padding(vertical = gDp(10f)),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        VText("対局中", Color(0xFF8A8F9C), labelFs, maxChars = 3, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(gDp(8f)))
                        VText(s.name, Color(0xFFE3B94F), nameFs, maxChars = 5)
                        Spacer(Modifier.height(gDp(8f)))
                        Text("${rank}位", color = Color(0xFFE3B94F), fontSize = nameFs, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(gDp(4f)))
                        Text("${s.handCount}", color = Color(0xFF9CD667), fontSize = nameFs, fontWeight = FontWeight.Black)
                        if (s.huRank > 0) {
                            Spacer(Modifier.height(gDp(6f)))
                            Text("胡", color = Color(0xFFFF6B5A), fontSize = labelFs, fontWeight = FontWeight.Black)
                        }
                        if (s.isDealer) {
                            Spacer(Modifier.height(gDp(4f)))
                            Text("莊", color = Color(0xFFE88A4A), fontSize = labelFs, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        SidePanel(left, alignRight = false)
        SidePanel(right, alignRight = true)

        // ---------------- 左上 HUD（友人戦/模式金匾/点棒x0/東1局） ----------------
        Place(gDp(8f), gDp(5f)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (snap.seats.any { it.isAi }) "練習戦" else "友人戦",
                        color = Color(0xFFE3B94F), fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                Color(0xFF000000), Offset(1.5f, 1.5f), 2.5f
                            )
                        )
                    )
                    Spacer(Modifier.width(gDp(10f)))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xF20D0F14))
                            .border(2.dp, Brush.horizontalGradient(listOf(Color(0xFF8A6A1E), Color(0xFFE8C15C), Color(0xFF8A6A1E))), RoundedCornerShape(50))
                            .padding(horizontal = gDp(14f), vertical = gDp(2.5f))
                    ) {
                        Text(
                            snap.mode.label,
                            color = Color(0xFFE8C15C), fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(gDp(5f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PointStick(0, gDp(26f))
                    Spacer(Modifier.width(gDp(4f)))
                    Text("x 0", color = Color(0xFFE6E9F0), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(gDp(2f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PointStick(1, gDp(26f))
                    Spacer(Modifier.width(gDp(4f)))
                    Text("x 0", color = Color(0xFFE6E9F0), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(gDp(4f)))
                // 局数：银白斜体
                val wind = WIND_KANJI[(snap.round / 4) % 4]
                Text(
                    "${wind}${snap.round % 4 + 1}局",
                    color = Color(0xFFDFE3EC), fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(Color(0xFF000000), Offset(1.5f, 1.5f), 3f)
                    )
                )
            }
        }

        // ---------------- 右上 chain 计数（视频卷轴+0 chain） ----------------
        Place(W - gDp(64f), gDp(6f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScrollIcon(gDp(22f))
                Text("0", color = Color(0xFF9CD667), fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text("chain", color = Color(0xFFE3B94F), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---------------- 顶部中央：上家名牌（小黑牌，供联机辨认） ----------------
        Place(boxCx - gDp(70f), gDp(4f)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(gDp(6f)))
                    .background(Color(0xB30D0F14))
                    .border(1.dp, Color(0x55C9A227), RoundedCornerShape(gDp(6f)))
                    .padding(horizontal = gDp(10f), vertical = gDp(3f))
            ) {
                Text(
                    top.name,
                    color = Color(0xFFE6E9F0), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = gDp(120f))
                )
            }
        }

        // ---------------- 底部玩家条（金框：対局中/名字/位次/国旗/点数/余牌） ----------------
        Place(boxCx - gDp(240f), H - gDp(27f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xE60D0F14))
                    .border(2.dp, Brush.horizontalGradient(listOf(Color(0xFF8A6A1E), Color(0xFFE8C15C), Color(0xFF8A6A1E))), RoundedCornerShape(50))
                    .padding(horizontal = gDp(12f), vertical = gDp(3.5f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(gDp(3f)))
                        .background(Color(0xFF3A3E48))
                        .padding(horizontal = gDp(5f), vertical = gDp(1f))
                ) {
                    Text("対局中", color = Color(0xFFC9CDD6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(gDp(8f)))
                Text(
                    me.name,
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = gDp(110f))
                )
                Spacer(Modifier.width(gDp(10f)))
                val myRank = 1 + scores.count { it > (scores.getOrElse(me.seat) { 0 }) }
                Text("${myRank}位", color = Color(0xFFE3B94F), fontSize = 13.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(gDp(6f)))
                JapanFlag(gDp(17f))
                Spacer(Modifier.width(gDp(6f)))
                Text(
                    "${scores.getOrElse(me.seat) { 0 }}",
                    color = Color(0xFFE8C15C), fontSize = 14.sp, fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(gDp(10f)))
                Text("余 ${snap.wallCount}", color = Color(0xFF9CD667), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---------------- 我方回合读秒（右下金色大数 +30） ----------------
        val myTurn = snap.phase == MjPhase.PLAYING && snap.turn == mySeat && snap.awaitingDiscard
        if (myTurn && dealDone) {
            Place(W - gDp(120f), handY - gDp(56f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$turnSec",
                        color = Color(0xFFF5B63F), fontSize = 30.sp, fontWeight = FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(2f, 2f), 3f)
                        )
                    )
                    Spacer(Modifier.width(gDp(4f)))
                    Text(
                        "+30",
                        color = Color(0xFFAFCC3F), fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(1f, 1f), 2f)
                        )
                    )
                }
            }
        }

        // ================= 我方手牌（居中于副露右侧；摸牌左端立起隔开——视频样式） =================
        val hand = me.hand
        val drawnId = snap.drawnTileId
        val normalHand = hand.filter { it.id != drawnId }
        val drawnTile = hand.firstOrNull { it.id == drawnId }
        val drawnGap = u * 14f
        val handRowW = bigW * normalHand.size + (if (drawnTile != null) drawnGap + bigW else 0.dp)
        val zoneL = u * 6f + meldsW
        val zoneR = W - u * 6f
        val xStart = zoneL + (zoneR - zoneL - handRowW) / 2

        @Composable
        fun HandTile(idx: Int, x: Dp, t: MjTile, isDrawn: Boolean) {
            val raised = t.id in selected
            val baseY = if (isDrawn) handY - u * 10f else handY
            Place(x, if (raised) baseY - u * 14f else baseY) {
                TenStandTile(
                    t.code, bigW,
                    modifier = Modifier
                        .graphicsLayer {
                            val p = deal.value
                            val e = if (p >= 1f) 1f else {
                                val xx = ((p * 1200f - idx * 45f) / 320f).coerceIn(0f, 1f)
                                1f - (1f - xx) * (1f - xx) * (1f - xx)
                            }
                            if (e < 1f) {
                                translationY = (boxCy.toPx() - baseY.toPx()) * (1f - e)
                                translationX = (boxCx.toPx() - x.toPx()) * 0.4f * (1f - e)
                                val s = 0.55f + 0.45f * e
                                scaleX = s; scaleY = s
                                alpha = e
                            } else {
                                translationX = 0f; translationY = 0f
                                scaleX = 1f; scaleY = 1f; alpha = 1f
                            }
                        }
                        .clickable(enabled = dealDone) {
                            if (t.id in selected) {
                                val err = vm.discardSelected()
                                if (err != null) toast = System.nanoTime() to err
                            } else vm.toggleSelect(t.id)
                        },
                    highlight = false
                )
                if (snap.laiziCode == t.code) {
                    LaiziBadge(bigW, Modifier.align(Alignment.TopEnd))
                }
            }
        }
        // 摸牌在最左端（视频 f_028：立起+间隙）
        drawnTile?.let { t -> HandTile(0, xStart, t, isDrawn = true) }
        normalHand.forEachIndexed { i, t ->
            HandTile(
                i + (if (drawnTile != null) 1 else 0),
                xStart + (if (drawnTile != null) bigW + drawnGap else 0.dp) + bigW * i,
                t, isDrawn = false
            )
        }

        // ================= 中央提示 / 宣告按钮 / 定缺 / 换三张（手牌上方居中） =================
        val centerHint = when {
            snap.phase == MjPhase.DINGQUE ->
                if (me.dingque >= 0) "等待其他玩家定缺…" else "请选择要缺的花色"
            snap.phase == MjPhase.SWAP3 ->
                if (snap.swapPicked) "等待其他玩家换三张…" else "选 3 张同花色牌与${swapDirLabel(snap.swapDir)}交换"
            myTurn -> "轮到你出牌"
            snap.phase == MjPhase.PLAYING -> "等「${snap.seats.getOrNull(snap.turn)?.name ?: "?"}」…"
            else -> ""
        }
        if (centerHint.isNotEmpty() && snap.myClaims.isEmpty()) {
            Text(
                centerHint,
                color = Color(0xFFE3B94F), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = boxCx - gDp(150f), y = handY - gDp(26f))
                    .widthIn(max = gDp(300f)),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 宣告按钮：胡>杠>碰>吃，黑金药丸按钮，居中于手牌上方
        if (snap.myClaims.isNotEmpty() && dealDone) {
            val order = mapOf("HU" to 0, "GANG" to 1, "PENG" to 2, "CHI" to 3)
            val claims = snap.myClaims.sortedBy { order[it.kind] ?: 9 }
            Place(boxCx - gDp(250f), handY - gDp(84f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(gDp(500f))) {
                    Row(
                        Modifier.align(Alignment.CenterHorizontally),
                        horizontalArrangement = Arrangement.spacedBy(gDp(10f)),
                        verticalAlignment = Alignment.Top
                    ) {
                        claims.forEach { opt ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (opt.kind == "CHI") {
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(gDp(5f)))
                                            .background(Color(0xB30D0F14))
                                            .border(1.dp, Color(0x55C9A227), RoundedCornerShape(gDp(5f)))
                                            .padding(horizontal = gDp(4f), vertical = gDp(2f)),
                                        horizontalArrangement = Arrangement.spacedBy(gDp(2f))
                                    ) {
                                        (opt.chiMid - 1..opt.chiMid + 1).forEach { c ->
                                            TenStandTile(c, gDp(15f))
                                        }
                                    }
                                    Spacer(Modifier.height(gDp(3f)))
                                }
                                ClaimButton(
                                    label = when (opt.kind) {
                                        "HU" -> "胡"; "GANG" -> "杠"; "PENG" -> "碰"; else -> "吃"
                                    },
                                    gold = opt.kind == "HU"
                                ) { vm.doClaim(opt) }
                            }
                        }
                        ClaimButton("过", gold = false, dim = true) { vm.passClaim() }
                    }
                }
            }
        }

        // 定缺（三色圆钮，深色底金环）
        if (snap.phase == MjPhase.DINGQUE && me.dingque < 0 && dealDone) {
            Place(boxCx - gDp(100f), handY - gDp(64f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gDp(24f))) {
                    MjSuitCircle(0, "万", Color(0xFFD8503F), gDp(44f)) { vm.dingque(it) }
                    MjSuitCircle(2, "条", Color(0xFF2FA86E), gDp(44f)) { vm.dingque(it) }
                    MjSuitCircle(1, "筒", Color(0xFFE89B3C), gDp(44f)) { vm.dingque(it) }
                }
            }
        }

        // 换三张确认
        if (snap.phase == MjPhase.SWAP3 && !snap.swapPicked && dealDone) {
            Place(boxCx - gDp(60f), handY - gDp(58f)) {
                ClaimButton("换三张", gold = true) {
                    val err = vm.confirmSwap()
                    if (err != null) toast = System.nanoTime() to err
                }
            }
        }

        // 暗杠/补杠/提示（右下角小按钮组，手牌上方）
        if (dealDone && (snap.anGangCodes.isNotEmpty() || snap.buGangIds.isNotEmpty() || (myTurn && normalHand.isNotEmpty()))) {
            Place(W - gDp(300f), handY - gDp(34f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gDp(8f))) {
                    if (snap.anGangCodes.isNotEmpty()) {
                        ClaimButton("暗杠", gold = false) {
                            val err = vm.declareGang(snap.anGangCodes.first(), bu = false, tileId = -1)
                            if (err != null) toast = System.nanoTime() to err
                        }
                    }
                    if (snap.buGangIds.isNotEmpty()) {
                        ClaimButton("补杠", gold = false) {
                            val err = vm.declareGang(-1, bu = true, tileId = snap.buGangIds.first())
                            if (err != null) toast = System.nanoTime() to err
                        }
                    }
                    if (myTurn) {
                        ClaimButton("提示", gold = false, dim = true) {
                            val err = vm.hint()
                            if (err != null) toast = System.nanoTime() to err
                        }
                    }
                }
            }
        }

        // ================= 快捷喊话 =================
        Place(W - gDp(44f), H - gDp(36f)) { StripChatButton { showChat = true } }

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
                        .padding(bottom = bigH + 30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xDD0D0F14))
                        .border(1.dp, Color(0x66C9A227), RoundedCornerShape(8.dp))
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
                            .background(Color(0x990D0F14))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

// ================================================================ 通用小部件

/** 黑金宣告/操作药丸按钮 */
@Composable
internal fun ClaimButton(label: String, gold: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    gold -> Brush.verticalGradient(listOf(Color(0xFF3D2E08), Color(0xFF241A05)))
                    dim -> Brush.verticalGradient(listOf(Color(0xFF1A1D24), Color(0xFF111319)))
                    else -> Brush.verticalGradient(listOf(Color(0xFF232836), Color(0xFF14171F)))
                }
            )
            .border(
                1.5.dp,
                if (gold) Brush.verticalGradient(listOf(Color(0xFFF0C75E), Color(0xFF9A7A20)))
                else Brush.verticalGradient(listOf(Color(0xFF6A7284), Color(0xFF3A4150))),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (gold) Color(0xFFF5D77A) else if (dim) Color(0xFF9AA1B0) else Color(0xFFE6E9F0),
            fontSize = 15.sp, fontWeight = FontWeight.Black
        )
    }
}

/** 定缺三色圆钮（深色底+彩环） */
@Composable
internal fun MjSuitCircle(suit: Int, label: String, ring: Color, size: Dp, onPick: (Int) -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF14171E))
            .border(3.dp, ring, CircleShape)
            .clickable { onPick(suit) },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFFE6E9F0), fontSize = 17.sp, fontWeight = FontWeight.Black)
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
            .background(Color(0xEE0B0D12)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Text(
                when {
                    result.liuju -> "流 局"
                    iWon -> "和 了 ！"
                    else -> "惜 敗 …"
                },
                fontSize = 34.sp, fontWeight = FontWeight.Black,
                color = if (iWon) Color(0xFFF5D77A) else if (result.liuju) Color(0xFFB0BEC5) else Color(0xFFFF8A80)
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
                    .background(Color(0x26191D27))
                    .border(1.dp, Color(0x55C9A227), RoundedCornerShape(14.dp))
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
                                    color = if (delta > 0) Color(0xFFF5D77A) else Color(0xE6FFFFFF)
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
                                    color = if (delta > 0) Color(0xFFF5D77A) else Color(0xFFFF8A80)
                                )
                            }
                        }
                    }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ClaimButton("返回大厅", gold = false) { onExit() }
                ClaimButton("再来一局", gold = true) { onAgain() }
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
