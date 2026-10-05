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
 * 麻将牌局 —— 完全对齐欢乐麻将参考图排版：
 * 深蓝灰环境+亮青梯形呢面（bg_mj_table，金饰线/菱形暗纹/水印）；下=我（大牌手牌小间隙+
 * 发牌飞入动画+右侧副露）、上=对家（卧牌背小绿背横排）、左右家=斜列侧背（白脸边+绿背，
 * ±12° 倾斜）；四家牌河围绕中央方形罗盘（mj_plate2：北东南西刻字+青色余数+局数+电池）；
 * 无牌墙渲染（余数只见罗盘）；胡碰杠吃圆形素材按钮、定缺三色圆钮；结算/特效/喊话浮层。
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
        // 桌面：素材 table.jpg + 木纹边框（原样）
        Image(
            painter = painterResource(R.drawable.bg_mj_table),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        val W = maxWidth
        val H = maxHeight
        val frame = W * 0.0285f                     // 环境留白基准
        val handW = (W * 0.0515f).coerceAtMost(H * 0.115f)   // 大手牌（参考图 123px@2400）
        val handH = handW * 1.382f
        val meldW = handW * 0.78f
        val discTop = W * 0.033f                    // 立牌牌河（对家/自家）
        val discW = W * 0.0285f                     // 横躺牌（左右家牌河/副露）
        val backLieW = W * 0.020f                   // 对家卧牌背
        val sideW = W * 0.0375f                     // 左右家斜列侧背
        val sideH = sideW * 240f / 180f
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

        // ================= 对家：卧牌背横排（发牌时依次亮起） =================
        val backLieH = backLieW * 220f / 192f
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = H * 0.065f - backLieH * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(top.handCount.coerceIn(1, 14)) { idx ->
                Box(
                    Modifier.graphicsLayer {
                        val x = ((deal.value * 840f - idx * 40f) / 280f).coerceIn(0f, 1f)
                        alpha = 0.25f + 0.75f * x
                        val s = 0.7f + 0.3f * x
                        scaleX = s; scaleY = s
                    }
                ) { MjBackLie(backLieW) }
            }
        }
        // 对家副露（顶部右侧）
        if (top.melds.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = H * 0.115f)
                    .padding(end = W * 0.05f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                top.melds.forEach { m -> m.tiles.forEach { t -> MjDiscTile(t.code, discTop * 1.02f) } }
            }
        }
        // 对家牌河（牌背下方，最新一排靠中央）
        val topRiverRows = top.river.chunked(8).takeLast(2)
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = H * 0.155f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            topRiverRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { t -> MjDiscTile(t.code, discTop) }
                }
            }
        }

        // ================= 上家（左）/ 下家（右）：斜列侧背 + 副露 + 牌河 =================
        val sideStep = sideH * 0.40f
        listOf(left to false, right to true).forEach { (seat, isRight) ->
            val backs = seat.handCount.coerceIn(6, 13)
            val cx = if (isRight) W * 0.751f else W * 0.226f
            val stackTop = if (isRight) H * 0.11f else H * 0.155f
            val stackH = sideStep * (backs - 1) + sideH
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = cx - sideW * 0.5f, y = stackTop)
                    .size(sideW, stackH)
                    .graphicsLayer { rotationZ = if (isRight) -12f else 12f }
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(-sideH * 0.60f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(backs) { idx ->
                        Box(
                            Modifier.graphicsLayer {
                                val x = ((deal.value * 840f - idx * 40f) / 280f).coerceIn(0f, 1f)
                                alpha = 0.25f + 0.75f * x
                                val s = 0.7f + 0.3f * x
                                scaleX = s; scaleY = s
                            }
                        ) { MjBackSide(sideW) }
                    }
                }
            }
            // 副露：斜列下方，横躺牌（刻字随牌体旋转），超过 6 张分双列
            if (seat.melds.isNotEmpty()) {
                Row(
                    Modifier
                        .align(if (isRight) Alignment.TopEnd else Alignment.TopStart)
                        .offset(
                            x = if (isRight) (cx - sideW * 0.55f - W) else (cx - sideW * 0.55f),
                            y = stackTop + stackH + 8.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    seat.melds.flatMap { it.tiles }.chunked(6).forEach { col ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            col.forEach { t ->
                                MjDiscTile(t.code, discW * 0.88f, dir = if (isRight) 1 else -1)
                            }
                        }
                    }
                }
            }
            // 牌河：竖排横躺牌（刻字随牌体旋转），贴斜列内侧
            val riverCols = seat.river.chunked(5).takeLast(2)
            Row(
                Modifier
                    .align(if (isRight) Alignment.TopEnd else Alignment.TopStart)
                    .offset(
                        x = if (isRight) -W * 0.255f else W * 0.255f,
                        y = H * 0.36f
                    ),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                riverCols.forEach { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        col.forEach { t ->
                            MjDiscTile(t.code, discW, dir = if (isRight) 1 else -1)
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

        // ================= 我的牌河（手牌上方，最新靠上） =================
        val myRiverRows = me.river.chunked(8).takeLast(2)
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
            myRiverRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { t -> MjDiscTile(t.code, discTop) }
                }
            }
        }

        // ================= 手牌 + 副露 =================
        val hand = me.hand.sortedBy { it.code }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = H * 0.008f),
            contentAlignment = Alignment.BottomCenter
        ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(horizontalArrangement = Arrangement.spacedBy(handW * 0.065f)) {
                hand.forEachIndexed { i, t ->
                    val n = hand.size
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
                            m.tiles.forEach { t -> MjDiscTile(t.code, meldW) }
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
 * 出牌贴图：dir=0 立牌正向（对家/自家牌河、副露）。
 * dir=±1 横躺牌 —— 立牌贴图整体旋转 90°（刻字随牌体一起转，参考图样式）：
 * 左家 dir=-1（字头朝左）/ 右家 dir=+1（字头朝右）。
 */
@Composable
internal fun MjDiscTile(code: Int, w: Dp, dir: Int = 0) {
    val h = w * 1.38202f
    if (dir == 0) {
        Image(
            painter = painterResource(faceRes(code.coerceIn(0, 33))),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(w, h)
        )
    } else {
        Box(modifier = Modifier.size(h, w), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(faceRes(code.coerceIn(0, 33))),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(w, h)
                    .graphicsLayer { rotationZ = 90f * dir }
            )
        }
    }
}

/** 卧牌背（对家手牌，白脸边在上 + 亮绿身在下） */
@Composable
internal fun MjBackLie(w: Dp) {
    Image(
        painter = painterResource(R.drawable.mj_back_lie),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(w, w * 220f / 192f)
    )
}

/** 斜列侧背（左右家手牌，白脸边在左 + 绿背在右） */
@Composable
internal fun MjBackSide(w: Dp) {
    Image(
        painter = painterResource(R.drawable.mj_back_side),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(w, w * 240f / 180f)
    )
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
