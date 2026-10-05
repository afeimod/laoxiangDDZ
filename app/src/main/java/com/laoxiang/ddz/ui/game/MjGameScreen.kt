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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.laoxiang.ddz.data.MjMeld
import com.laoxiang.ddz.data.MjPhase
import com.laoxiang.ddz.data.MjSeatView
import com.laoxiang.ddz.data.MjSnapshot
import com.laoxiang.ddz.data.MjTile
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.theme.Gold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 麻将牌局 —— 完全对齐欢乐麻将参考图排版（参考 joygames chinamj APK 反编译布局规则）：
 * 深蓝灰环境+亮青梯形呢面（bg_mj_table 放大 1.22 倍让呢面铺满、金饰边贴屏幕边缘）；
 * 下=我（大牌贴底无间隙、轮到我时末张摸牌隔开 0.34 牌宽+发牌飞入动画+右侧副露）；
 * 上=对家（双层立牌墙：白顶盖+亮绿牌背，后排顶盖从前排上方露出）；
 * 左右家=沿梯形斜边透视缩放的立牌侧墙（白顶盖朝外侧）；
 * 四家牌河=横躺牌（白脸面+象牙侧壁+绿底线+投影，最新一张描金边）围绕中央方形罗盘；
 * 胡碰杠吃圆形素材按钮、定缺三色圆钮；结算/特效/喊话浮层。
 */

/** 金色数字素材 */
private val NUM_RES = intArrayOf(
    R.drawable.mj_num0, R.drawable.mj_num1, R.drawable.mj_num2, R.drawable.mj_num3,
    R.drawable.mj_num4, R.drawable.mj_num5, R.drawable.mj_num6, R.drawable.mj_num7,
    R.drawable.mj_num8, R.drawable.mj_num9
)


@Composable
fun MjGameScreen(vm: MjViewModel, onExit: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF274F48))
    ) {
        // 桌面：素材 table.jpg 放大居中，呢面铺满屏幕、金饰边贴边（参考图效果）
        Image(
            painter = painterResource(R.drawable.bg_mj_table),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.22f
                    scaleY = 1.22f
                }
        )

        val W = maxWidth
        val H = maxHeight
        val frame = W * 0.0285f                     // 环境留白基准
        val handW = (W * 0.056f).coerceAtMost(H * 0.125f)    // 大手牌（参考图 154px@2400，参考 APK 89px@1280）
        val handH = handW * 1.382f
        val meldW = handW * 0.74f                   // 我的副露（立牌小一号，参考 APK 0.573 比例）
        val meldTopW = W * 0.0235f                  // 对家副露（立牌）
        val riverW = W * 0.030f                     // 牌河横躺牌宽（白脸+象牙侧壁）
        val wallW = W * 0.0275f                     // 上方牌墙立牌（双层）
        val wallH = wallW * 1.30f
        val sideW0 = W * 0.0295f                    // 左右家侧墙立牌（透视缩放基准）
        val sideH0 = sideW0 * 1.42f
        val sideMeldW = W * 0.0255f                 // 左右家副露横躺牌
        val plateW = W * 0.129f                     // 罗盘面板
        val plateH = plateW * 472f / 616f

        val snap = snapshot ?: run {
            Text(
                "准备开局…",
                color = Color(0xCCD7E7FA), fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center)
            )
            return@BoxWithConstraints
        }

        val mySeat by vm.mySeat.collectAsState()
        val n = 4
        val seats = snap.seats
        val me = seats[mySeat.coerceIn(0, 3)]
        val right = seats[(mySeat + 1) % n]
        val top = seats[(mySeat + 2) % n]
        val left = seats[(mySeat + 3) % n]
        val selected by vm.selected.collectAsState()

        // ---- 发牌动画状态：手牌从空 -> 非空 = 新一局开始；先声明供四家手牌/牌背动画共用 ----
        var dealEpoch by remember { mutableIntStateOf(0) }
        var handWasEmpty by remember { mutableStateOf(true) }
        if (me.hand.isEmpty()) {
            handWasEmpty = true
        } else if (handWasEmpty) {
            handWasEmpty = false
            dealEpoch++
        }
        val deal = remember { Animatable(1f) }
        LaunchedEffect(dealEpoch) {
            if (dealEpoch > 0) {
                deal.snapTo(0f)
                deal.animateTo(1f, tween(840, easing = LinearEasing))
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

        // ================= 顶部工具条（模式/癞子/余牌/离桌） =================
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = frame * 0.6f, top = 6.dp),
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

        // ================= 对家：双层立牌墙（白顶盖+亮绿牌背，发牌时依次亮起） =================
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = H * 0.046f)
        ) {
            // 后排：顶盖从前排上方露出（参考图双层墙效果）
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.offset(x = wallW * 0.18f, y = -wallH * 0.27f)
            ) {
                repeat(top.handCount.coerceIn(1, 14)) { idx ->
                    Box(
                        Modifier.graphicsLayer {
                            val x = ((deal.value * 840f - idx * 40f) / 280f).coerceIn(0f, 1f)
                            alpha = (0.25f + 0.75f * x) * 0.94f
                            val s = 0.7f + 0.3f * x
                            scaleX = s; scaleY = s
                        }
                    ) { MjWallBackTile(wallW) }
                }
            }
            // 前排
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.offset(y = wallH * 0.15f)
            ) {
                repeat(top.handCount.coerceIn(1, 14)) { idx ->
                    Box(
                        Modifier.graphicsLayer {
                            val x = ((deal.value * 840f - idx * 40f) / 280f).coerceIn(0f, 1f)
                            alpha = 0.25f + 0.75f * x
                            val s = 0.7f + 0.3f * x
                            scaleX = s; scaleY = s
                        }
                    ) { MjWallBackTile(wallW) }
                }
            }
        }
        // 对家副露（顶部右侧，立牌小一号）
        if (top.melds.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = H * 0.115f)
                    .padding(end = W * 0.05f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                top.melds.forEach { m -> m.tiles.forEach { t -> MjDiscTile(t.code, meldTopW, lying = false) } }
            }
        }
        // 对家牌河（牌墙下方，最新一排靠中央，末张描金边）
        val topRiverRows = top.river.chunked(7).takeLast(2)
        val topRiverStart = top.river.size - topRiverRows.sumOf { it.size }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = H * 0.175f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            var gi = topRiverStart
            topRiverRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { t ->
                        MjDiscTile(t.code, riverW, highlight = gi++ == top.river.size - 1)
                    }
                }
            }
        }

        // ================= 上家（左）/ 下家（右）：沿梯形斜边透视缩放立牌侧墙 + 副露 + 牌河 =================
        listOf(left to false, right to true).forEach { (seat, isRight) ->
            // 侧墙：近大远小沿对角线排布（参考图左/右墙样式，白顶盖朝外侧）
            val backs = seat.handCount.coerceIn(8, 13)
            val nearX = if (isRight) W * 0.928f else W * 0.072f
            val nearY = H * 0.835f
            val farX = if (isRight) W * 0.768f else W * 0.232f
            val farY = H * 0.170f
            val stepT = 1f / (backs - 1).coerceAtLeast(1)
            Box(Modifier.align(Alignment.TopStart).fillMaxSize()) {
                repeat(backs) { idx ->
                    val t = idx * stepT
                    val px = nearX + (farX - nearX) * t
                    val py = nearY + (farY - nearY) * t
                    val sc = 1.02f - 0.26f * t
                    Box(
                        Modifier
                            .offset(x = px - sideW0 * sc * 0.5f, y = py - sideH0 * sc * 0.5f)
                            .size(sideW0 * sc, sideH0 * sc)
                            .graphicsLayer {
                                val x = ((deal.value * 840f - idx * 40f) / 280f).coerceIn(0f, 1f)
                                alpha = 0.25f + 0.75f * x
                                val s = 0.7f + 0.3f * x
                                scaleX = s; scaleY = s
                            }
                    ) { MjWallSideTile(sideW0 * sc, isRight) }
                }
            }
            // 副露：斜墙下端内侧，横躺牌（刻字随牌体旋转），超过 6 张分双列
            if (seat.melds.isNotEmpty()) {
                Row(
                    Modifier
                        .align(if (isRight) Alignment.TopEnd else Alignment.TopStart)
                        .offset(
                            x = if (isRight) -W * 0.205f else W * 0.145f,
                            y = H * 0.52f
                        ),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    seat.melds.flatMap { it.tiles }.chunked(6).forEach { col ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            col.forEach { t ->
                                MjDiscTile(t.code, sideMeldW, dir = if (isRight) 1 else -1)
                            }
                        }
                    }
                }
            }
            // 牌河：竖排横躺牌（末张描金边），贴斜墙内侧
            val riverCols = seat.river.chunked(6).takeLast(2)
            val riverStart = seat.river.size - riverCols.sumOf { it.size }
            Row(
                Modifier
                    .align(if (isRight) Alignment.TopEnd else Alignment.TopStart)
                    .offset(
                        x = if (isRight) -W * 0.325f else W * 0.265f,
                        y = H * 0.345f
                    ),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                var gi = riverStart
                riverCols.forEach { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        col.forEach { t ->
                            MjDiscTile(
                                t.code, riverW * 0.94f,
                                dir = if (isRight) 1 else -1,
                                highlight = gi++ == seat.river.size - 1
                            )
                        }
                    }
                }
            }
        }

        // ================= 中央罗盘（方形金属面板）+ 剩余数 + 局数 =================
        val plateOffsetY = -H * 0.023f
        val density = LocalDensity.current
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(y = plateOffsetY)
                .size(plateW, plateH)
        ) {
            Image(
                painter = painterResource(R.drawable.mj_plate2),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
            Text(
                "%02d".format(snap.wallCount.coerceIn(0, 99)),
                color = Color(0xFF7FE7E4),
                fontSize = with(density) { (plateW * 0.155f).toSp() },
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = plateH * 0.31f)
            )
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = plateH * 0.625f),
                horizontalArrangement = Arrangement.spacedBy(plateW * 0.025f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(plateW * 0.05f, plateW * 0.072f)
                        .border(1.5.dp, Color(0xFFE9EDE2), RoundedCornerShape(2.5.dp)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .padding(2.dp)
                            .fillMaxWidth()
                            .fillMaxHeight(0.55f)
                            .background(Color(0xFF2EA03C), RoundedCornerShape(1.5.dp))
                    )
                }
                Text(
                    snap.round.toString(),
                    color = Color(0xFFF3F6F0),
                    fontSize = with(density) { (plateW * 0.088f).toSp() },
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 中央提示
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
                color = Color(0xFFDFF5F0), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = plateOffsetY + plateH * 0.82f)
            )
        }

        // ================= 我的牌河（手牌上方横躺牌，最新靠上排末端描金边） =================
        val myRiverRows = me.river.chunked(7).takeLast(2)
        val myRiverStart = me.river.size - myRiverRows.sumOf { it.size }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(
                    x = W * 0.10f,
                    y = -(handH + 12.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.End
        ) {
            var gi = myRiverStart
            myRiverRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { t ->
                        MjDiscTile(t.code, riverW, highlight = gi++ == me.river.size - 1)
                    }
                }
            }
        }

        // ================= 手牌 + 副露（大牌贴底、牌与牌紧贴，轮到我时摸到的末张隔开，参考 APK/图 2） =================
        val hand = me.hand.sortedBy { it.code }
        val drawnGap = myTurn && snap.awaitingDiscard && hand.size > 1

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = H * 0.006f),
            contentAlignment = Alignment.BottomCenter
        ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Row {
                hand.forEachIndexed { i, t ->
                    val n = hand.size
                    if (i == n - 1 && drawnGap) Spacer(Modifier.width(handW * 0.34f))
                    MjTileView(
                        tile = t,
                        w = handW,
                        selected = t.id in selected,
                        laiziMark = snap.laiziCode == t.code,
                        modifier = Modifier
                            .graphicsLayer {
                                val p = deal.value
                                val e = if (p >= 1f) 1f else {
                                    val x = ((p * 840f - i * 45f) / 300f).coerceIn(0f, 1f)
                                    1f - (1f - x) * (1f - x) * (1f - x)
                                }
                                if (e < 1f) {
                                    translationX =
                                        -(i - (n - 1) / 2f) * (handW * 1.10f).toPx() * (1f - e)
                                    translationY = -(H.toPx() * 0.30f) * (1f - e)
                                    alpha = 0.25f + 0.75f * e
                                } else {
                                    translationX = 0f; translationY = 0f; alpha = 1f
                                }
                            }
                            .clickable {
                                if (t.id in selected) {
                                    val err = vm.discardSelected()
                                    if (err != null) toast = System.nanoTime() to err
                                } else vm.toggleSelect(t.id)
                            }
                    )
                }
            }
            if (me.melds.isNotEmpty()) {
                Spacer(Modifier.width(handW * 0.20f))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(meldW * 0.10f)
                ) {
                    me.melds.forEach { m ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(handW * 0.10f),
                            modifier = Modifier.offset(y = -handH * 0.085f)
                        ) {
                            m.tiles.forEach { t -> MjDiscTile(t.code, meldW, lying = false) }
                        }
                    }
                }
            }
        }
        }

        // ================= 右下操作区（横条按钮 / 宣告按钮） =================
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = frame * 1.6f, bottom = handH + 14.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 横条：暗杠/补杠/提示/出牌/换三张
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (myTurn && snap.awaitingDiscard) {
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
            // 宣告：胡/杠/碰/吃 + 过
            if (snap.myClaims.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    snap.myClaims.forEach { opt ->
                        when (opt.kind) {
                            "HU" -> MjImageButton(R.drawable.mj_btn_hu, 46.dp) { vm.doClaim(opt) }
                            "GANG" -> MjImageButton(R.drawable.mj_btn_gang, 46.dp) { vm.doClaim(opt) }
                            "PENG" -> MjImageButton(R.drawable.mj_btn_peng, 46.dp) { vm.doClaim(opt) }
                            "CHI" -> MjImageButton(R.drawable.mj_btn_chi, 46.dp) { vm.doClaim(opt) }
                        }
                    }
                    MjImageButton(R.drawable.mj_btn_pass, 42.dp) { vm.passClaim() }
                }
            }
        }

        // ================= 定缺（三色圆钮） =================
        if (snap.phase == MjPhase.DINGQUE && me.dingque < 0) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = handH * 1.32f + 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                MjSuitCircle(0, "万", listOf(Color(0xFFEF6A5A), Color(0xFFB3271D)), 46.dp) { vm.dingque(it) }
                MjSuitCircle(2, "条", listOf(Color(0xFF63D8B8), Color(0xFF1D8574)), 46.dp) { vm.dingque(it) }
                MjSuitCircle(1, "筒", listOf(Color(0xFFFFB84D), Color(0xFFD97A16)), 46.dp) { vm.dingque(it) }
            }
        }

        // ================= 玩家信息卡 =================
        MjPlayerCard(
            me, true,
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = W * 0.018f, bottom = handH * 1.38f)
        )
        MjPlayerCard(
            top, false,
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = W * 0.018f, top = H * 0.045f)
        )
        MjPlayerCard(
            left, false,
            Modifier
                .align(Alignment.CenterStart)
                .offset(y = -H * 0.21f)
                .padding(start = W * 0.018f)
        )
        MjPlayerCard(
            right, false,
            Modifier
                .align(Alignment.CenterEnd)
                .offset(y = -H * 0.20f)
                .padding(end = W * 0.018f)
        )

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
                        .padding(bottom = frame * 0.5f + handH + 26.dp)
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
                            .padding(start = frame * 0.7f + 54.dp, bottom = frame * 0.4f + 58.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x99122A44))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
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

// ================================================================ 素材小部件

/**
 * 出牌/副露贴图：
 * lying=true 横躺牌 —— 白脸面 + 象牙侧壁 + 绿底线 + 投影（参考图牌河样式）；
 * lying=false 立牌 —— 直接贴 mj_* 立体牌面（手牌同款）。
 * dir=±1 整体旋转 90°（刻字随牌体一起转）：左家 dir=-1（字头朝左）/ 右家 dir=+1（字头朝右）。
 * highlight=true 最新出牌描金边（参考 APK 出牌高亮框）。
 */
@Composable
internal fun MjDiscTile(code: Int, w: Dp, dir: Int = 0, lying: Boolean = true, highlight: Boolean = false) {
    if (!lying) {
        val h = w * 1.38202f
        Box(modifier = Modifier.size(w, h)) {
            Image(
                painter = painterResource(faceRes(code.coerceIn(0, 33))),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
        }
        return
    }
    val faceH = w * 0.84f
    val sideH = w * 0.14f
    val baseH = w * 0.05f
    val totalH = faceH + sideH + baseH
    val shape = RoundedCornerShape(w * 0.16f)
    val outer = if (dir == 0) Modifier.size(w, totalH) else Modifier.size(totalH, w)
    Box(modifier = outer, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(w, totalH)
                .graphicsLayer { rotationZ = 90f * dir }
        ) {
            // 象牙侧壁
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -baseH)
                    .size(w * 0.94f, sideH + baseH * 1.6f)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFF4F2E7), Color(0xFFC9C5B0))),
                        RoundedCornerShape(w * 0.10f)
                    )
            )
            // 绿底线
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(w * 0.90f, baseH)
                    .background(Color(0xFF2F8C3C), RoundedCornerShape(w * 0.08f))
            )
            // 白牌面（带投影 + 可选金边高亮）
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(w, faceH)
                    .shadow(w * 0.055f, shape)
                    .then(if (highlight) Modifier.border(1.5.dp, Color(0xE6FFE08A), shape) else Modifier)
            ) {
                Image(
                    painter = painterResource(faceRes(code.coerceIn(0, 33))),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

/** 牌墙立牌（上/前方）：白顶盖 + 亮绿牌背（参考图上墙样式） */
@Composable
internal fun MjWallBackTile(w: Dp) {
    val h = w * 1.30f
    Canvas(Modifier.size(w, h)) {
        val capH = size.width * 0.30f
        val r = size.width * 0.20f
        // 绿色牌身（顶部藏在白盖后面）
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF4CBA5B), Color(0xFF1D7A31))),
            topLeft = Offset(0f, capH * 0.55f),
            size = Size(size.width, size.height - capH * 0.55f),
            cornerRadius = CornerRadius(r, r)
        )
        // 右下暗边（立体感）
        drawRoundRect(
            color = Color(0x2E000000),
            topLeft = Offset(size.width * 0.80f, capH * 0.55f),
            size = Size(size.width * 0.20f, size.height - capH * 0.55f),
            cornerRadius = CornerRadius(r, r)
        )
        // 白顶盖（牌顶面受光）
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFFBF9EE), Color(0xFFD8D5BF))),
            topLeft = Offset(size.width * 0.05f, 0f),
            size = Size(size.width * 0.90f, capH),
            cornerRadius = CornerRadius(r * 0.7f, r * 0.7f)
        )
    }
}

/** 牌墙侧立牌（左/右家）：白顶盖朝外侧 + 绿牌身，竖长条 */
@Composable
internal fun MjWallSideTile(w: Dp, isRight: Boolean) {
    val h = w * 1.42f
    Canvas(Modifier.size(w, h)) {
        val capW = size.width * 0.34f
        val r = size.width * 0.20f
        // 绿色牌身
        val bodyLeft = if (isRight) 0f else capW * 0.52f
        drawRoundRect(
            brush = Brush.horizontalGradient(
                if (isRight) listOf(Color(0xFF1D7A31), Color(0xFF4CBA5B))
                else listOf(Color(0xFF4CBA5B), Color(0xFF1D7A31))
            ),
            topLeft = Offset(bodyLeft, 0f),
            size = Size(size.width - bodyLeft, size.height),
            cornerRadius = CornerRadius(r, r)
        )
        // 白顶盖（外侧受光面）
        val capLeft = if (isRight) size.width - capW else 0f
        drawRoundRect(
            brush = Brush.horizontalGradient(
                if (isRight) listOf(Color(0xFFD8D5BF), Color(0xFFFBF9EE))
                else listOf(Color(0xFFFBF9EE), Color(0xFFD8D5BF))
            ),
            topLeft = Offset(capLeft, size.height * 0.03f),
            size = Size(capW, size.height * 0.94f),
            cornerRadius = CornerRadius(r * 0.7f, r * 0.7f)
        )
    }
}

/** 素材金色数字串（余牌/罗盘） */
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

// ================================================================ 玩家信息卡

@Composable
internal fun MjPlayerCard(seat: MjSeatView, isMe: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                seat.avatar, if (isMe) 44.dp else 40.dp,
                Modifier.border(
                    2.5.dp,
                    if (seat.isTurn) Color(0xFFFFC107) else Color(0x66FFFFFF),
                    CircleShape
                )
            )
            Spacer(Modifier.width(5.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        seat.name,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (seat.isTurn) Color(0xFFFFE082) else Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 72.dp)
                    )
                    if (seat.isDealer) {
                        Spacer(Modifier.width(3.dp))
                        Image(
                            painter = painterResource(R.drawable.mj_mark_zhuang),
                            contentDescription = "庄家",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (seat.huRank > 0) {
                        Spacer(Modifier.width(3.dp))
                        Text("胡", fontSize = 11.sp, color = Gold, fontWeight = FontWeight.Black)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (seat.dingque >= 0) {
                        Text(
                            "缺${suitLabel(seat.dingque)}", fontSize = 10.sp,
                            color = Color(0xFF80DEEA),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0x59002438))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (seat.ting) {
                        Spacer(Modifier.width(4.dp))
                        Image(
                            painter = painterResource(R.drawable.mj_mark_ting),
                            contentDescription = "听牌",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (seat.isAi) {
                        Spacer(Modifier.width(4.dp))
                        Text("AI", fontSize = 9.sp, color = Color(0x88FFFFFF))
                    }
                }
            }
        }
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
