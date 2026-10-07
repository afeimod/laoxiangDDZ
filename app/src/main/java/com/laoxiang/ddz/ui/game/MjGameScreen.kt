package com.laoxiang.ddz.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * 麻将牌局 —— 视觉层完全按参考 APK（net.joygames.chinamj GameView）1:1 移植：
 * 桌面 = APK table.jpg 全屏；中央罗盘 = centerbanner+center1+风位(104)；
 * 四家牌河围绕罗盘（下/上=11/9/7 金字塔立牌，左/右=横躺牌列）；
 * 他家手牌 = cemian 背面（右/左竖列 22 间距重叠、上家横排相邻）；
 * 我方手牌 = psmj 大牌紧贴、右对齐 13+1 布局、选中上浮 25；
 * 副露 = 白胚+0.573/0.4584 刻字合成，碰/吃来的牌按 APK 规则转向；
 * 发牌动画保留（手牌从中央飞入 + 他家牌背依次亮起）。
 */

/** APK 罗盘风位素材：东/南/西/北（庄家=东） */
private val WIND_APK = intArrayOf(
    R.drawable.mjwind_e, R.drawable.mjwind_s, R.drawable.mjwind_w, R.drawable.mjwind_n
)

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

/** 横躺牌河蛇形列位：列宽 9/7/5/3/1 递减，返回 (列号, 列内序号)（与 APK 折行逻辑一致） */
private fun lieRiverCell(i: Int): Pair<Int, Int> {
    var col = 0; var start = 0; var acc = 9; var sz = 9
    while (i >= acc) { col++; start = acc; sz -= 2; acc += if (sz > 0) sz else 1 }
    return col to (i - start)
}

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
            .background(Color(0xFF0E2B26))
    ) {
        // ===== APK 桌面：table.jpg 全屏拉伸（From1280strech） =====
        Image(
            painter = painterResource(R.drawable.mj_table_flat),
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

        /** APK DrawFlatAvatar 1:1 玩家信息：头像98x97+庄标39x38右下+名字 g(26)白字+徽章 */
        @Composable
        fun MjApkCard(s: MjSeatView, pos: Int) {
            val nameFs = with(density) { g(26f).toSp() }
            val nameRow: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
        val seats = snap.seats
        val me = seats[mySeat.coerceIn(0, 3)]
        val right = seats[(mySeat + 1) % 4]
        val top = seats[(mySeat + 2) % 4]
        val left = seats[(mySeat + 3) % 4]
        val selected by vm.selected.collectAsState()

        // ---- 开局仪式：先掷骰子（素材 sezi1-6）再发牌 ----
        // 以局号为键每局必重启；掷骰 1s（翻滚变面+旋转）→ 定格展示点数 0.75s → 发牌飞入 1.2s
        val deal = remember { Animatable(0f) }
        val diceRoll = remember { Animatable(0f) }
        var dicePair by remember { mutableStateOf(intArrayOf(5, 3)) }
        var dealDone by remember { mutableStateOf(false) }
        LaunchedEffect(snap.round) {
            dealDone = false
            deal.snapTo(0f)
            diceRoll.snapTo(0f)
            // 骰子点数由引擎掷出（同时决定切墙位置，仪式与发牌一致）
            dicePair = if (snap.dice1 in 1..6 && snap.dice2 in 1..6) intArrayOf(snap.dice1, snap.dice2)
            else intArrayOf(Random.nextInt(1, 7), Random.nextInt(1, 7))
            diceRoll.animateTo(1f, tween(1000, easing = LinearEasing))
            delay(750)
            deal.animateTo(1f, tween(1200, easing = LinearEasing))
            dealDone = true
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

        // ================= 中央罗盘（APK：centerbanner + center1 + 风位，104 居中） =================
        val plateSz = g(104f)
        Box(
            Modifier
                .align(Alignment.Center)
                .size(plateSz)
        ) {
            Image(
                painter = painterResource(R.drawable.mjplate_bg),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
            Image(
                painter = painterResource(R.drawable.mjplate_center1),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
            // 当前回合玩家的座风图（庄=东），按其方位旋转（APK drawRotateBitmap）
            val posOfTurn = (snap.turn - mySeat + 4) % 4
            Image(
                painter = painterResource(WIND_APK[(snap.turn - snap.dealer + 4) % 4]),
                contentDescription = "风位",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        rotationZ = when (posOfTurn) { 1 -> 270f; 2 -> 180f; 3 -> 90f; else -> 0f }
                    }
            )
        }
        // 剩余张数（APK：罗盘下方深绿粗体，W/2-85, H/2+75）
        Text(
            "剩余张数:${snap.wallCount}",
            color = Color(0xFF083209),
            fontSize = with(density) { g(30f).toSp() },
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = -g(85f), y = g(75f))
        )

        // ================= 开局掷骰子（发牌前仪式，罗盘上方居中） =================
        // 掷骰 1s（变面+旋转+弹跳）→ 定格 0.75s 展示点数 → 发牌动画启动后淡出
        if (diceRoll.value > 0f && deal.value < 1f) {
            val diceAlpha = (1f - deal.value * 3f).coerceIn(0f, 1f)
            if (diceAlpha > 0.01f) {
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = -plateSz / 2 - g(58f))
                        .graphicsLayer { alpha = diceAlpha }
                ) {
                    DiceDie(dicePair[0], diceRoll.value < 1f, diceRoll.value, g(52f))
                    Spacer(Modifier.width(g(18f)))
                    DiceDie(dicePair[1], diceRoll.value < 1f, diceRoll.value, g(52f))
                }
            }
        }

        // ================= 他家手牌背与副露（APK onDraw 顺序：PE 右 → PN 上 → PW 左） =================
        // ⚠ APK onDraw: DrawFlatPE>PN>PW>DrawFlatGived(牌河)>DrawFlatPS —— 他家副露先画、牌河后画，
        //   牌河盖住副露下缘；旧版副露后画导致对家碰吃杠牌面压住牌河（用户反馈），按 APK 顺序重排。
        // 右家手牌背：cemian2 竖列，x=702，步距 22 重叠
        repeat(right.handCount.coerceIn(1, 14)) { idx ->
            Place(fx(702f), fy(80f) + g(22f) * idx) {
                Box(
                    Modifier.graphicsLayer {
                        val p = ((deal.value * 1200f - idx * 40f) / 280f).coerceIn(0f, 1f)
                        alpha = 0.25f + 0.75f * p
                        val s = 0.7f + 0.3f * p
                        scaleX = s; scaleY = s
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
        repeat(top.handCount.coerceIn(1, 14)) { idx ->
            Place(fx(200f) + g(32f) * idx, fy(78f)) {
                Box(
                    Modifier.graphicsLayer {
                        val p = ((deal.value * 1200f - idx * 40f) / 280f).coerceIn(0f, 1f)
                        alpha = 0.25f + 0.75f * p
                        val s = 0.7f + 0.3f * p
                        scaleX = s; scaleY = s
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
            repeat(left.handCount.coerceIn(1, 14)) { idx ->
                Place(fx(86f), handTop + g(22f) * idx) {
                    Box(
                        Modifier.graphicsLayer {
                            val p = ((deal.value * 1200f - idx * 40f) / 280f).coerceIn(0f, 1f)
                            alpha = 0.25f + 0.75f * p
                            val s = 0.7f + 0.3f * p
                            scaleX = s; scaleY = s
                        }
                    ) { MjBackTile(3, u) }
                }
            }
        }

        // ================= 四家牌河（DrawFlatGived 1:1，晚于他家副露绘制） =================
        // 下家方向语义：pos0=我(下) dir0 / pos1=右 dir1 / pos2=上 dir2 / pos3=左 dir3
        // 我方牌河：11/9/7 金字塔，x 居中起步，行向右移 51、行距上移 (76-15)
        run {
            val stepX = g(51f)
            val rowStep = g(76f) - fy(10f)
            var wrapIdx = 11
            var rowSz = 11
            var rowStart = (W - stepX * 11) / 2
            var x = rowStart
            var y = fy(333f)
            me.river.forEachIndexed { i, t ->
                if (i == wrapIdx) {
                    rowSz -= 2
                    wrapIdx = if (rowSz <= 0) wrapIdx + 1 else wrapIdx + rowSz
                    y -= rowStep
                    rowStart += stepX
                    x = rowStart
                } else if (i != 0) {
                    x += stepX
                }
                Place(x, y) { MjTableTile(t.code, 0, u) }
            }
        }
        // 对家牌河：11/9/7 金字塔（镜像），最右起步向左，行距下移
        run {
            val stepX = g(51f)
            val rowStep = g(76f) - fy(10f)
            var wrapIdx = 11
            var rowSz = 11
            var rowStart = (W - stepX * 11) / 2 + stepX * 10
            var x = rowStart
            var y = fy(121f)
            top.river.forEachIndexed { i, t ->
                if (i == wrapIdx) {
                    rowSz -= 2
                    wrapIdx = if (rowSz <= 0) wrapIdx + 1 else wrapIdx + rowSz
                    y += rowStep
                    rowStart -= stepX
                    x = rowStart
                } else if (i != 0) {
                    x -= stepX
                }
                Place(x, y) { MjTableTile(t.code, 2, u) }
            }
        }
        // 左家牌河：竖列横躺牌（APK：步距40向下，列宽 9/7/5/3/1 递减，列距64右移）
        left.river.forEachIndexed { i, t ->
            val (col, r) = lieRiverCell(i)
            Place(
                fx(133f) + g(64f) * col,
                fy(110f) + g(40f) * (col + r)
            ) { MjTableTile(t.code, 3, u) }
        }
        // 右家牌河：竖列横躺牌（步距40向上，列宽 9/7/5/3/1，列距64左移）
        // ⚠ APK DrawFlatGived 对右家是【倒序绘制】（从最后一张往前画）：
        // 旧牌(下方)盖住新牌(上方)的下缘绿边，每张的刻字区域都不被遮挡；正序画则新牌盖住旧牌刻字
        val rRiverCount = right.river.size
        for (ri in rRiverCount - 1 downTo 0) {
            val (rc, rr) = lieRiverCell(ri)
            Place(
                fx(628f) - g(64f) * rc,
                fy(315f) - g(40f) * (rc + rr)
            ) { MjTableTile(right.river[ri].code, 1, u) }
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
                                val xx = ((p * 1200f - idx * 40f) / 320f).coerceIn(0f, 1f)
                                1f - (1f - xx) * (1f - xx) * (1f - xx)
                            }
                            if (e < 1f) {
                                translationX = -(idx - 6.5f) * bigW.toPx() * (1f - e)
                                translationY = -(H.toPx() * 0.30f) * (1f - e)
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
                                    val fp = ((deal.value * 1200f - (520f + idx * 30f)) / 200f).coerceIn(0f, 1f)
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
                    .align(Alignment.Center)
                    // 罗盘+剩余张数之下，避免重叠（剩余张数占 center+75..105）
                    .offset(y = plateSz / 2 + g(62f))
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
