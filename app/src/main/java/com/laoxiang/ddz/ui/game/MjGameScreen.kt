package com.laoxiang.ddz.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.MjClaimOpt
import com.laoxiang.ddz.data.MjMeld
import com.laoxiang.ddz.data.MjPhase
import com.laoxiang.ddz.data.MjSnapshot
import com.laoxiang.ddz.data.MjTile
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.theme.Gold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 麻将牌局（麻将桌布局）：下=我、右=下家、上=对家、左=上家。
 * 四家牌河围绕中央；底部手牌扇形排列；吃碰杠胡按钮浮层；
 * 定缺 / 换三张 阶段浮层；结算浮层（血战多赢家）。
 */
@Composable
fun MjGameScreen(vm: MjViewModel, onExit: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()
    val snap = snapshot ?: run {
        Box(Modifier.fillMaxSize()) {
            TableBackground(
                bgKey = vm.prefs.tableBg,
                modifier = Modifier.fillMaxSize(),
                engraving = "老乡麻将"
            )
            Box(
                Modifier.fillMaxSize().background(Color(0xFF16305C).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) { Text("准备开局…", color = Color(0xCCD7E7FA), fontSize = 15.sp) }
        }
        return
    }

    val mySeat by vm.mySeat.collectAsState()
    val n = 4
    val seats = snap.seats
    val me = seats[mySeat.coerceIn(0, 3)]
    val right = seats[(mySeat + 1) % n]
    val top = seats[(mySeat + 2) % n]
    val left = seats[(mySeat + 3) % n]

    val selected by vm.selected.collectAsState()

    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showChat by remember { mutableStateOf(false) }
    var myBubble by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // 中央大特效：(时间戳, 素材资源, 附加文字)
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E4A38))
    ) {
        TableBackground(
            bgKey = vm.prefs.tableBg,
            modifier = Modifier.fillMaxSize(),
            engraving = if (snap.phase == MjPhase.PLAYING) "老乡麻将" else null
        )

        val tileW = (maxWidth / 11f).coerceAtMost(54.dp)
        val smallW = tileW * 0.42f
        val meldW = tileW * 0.55f

        // ---------------- 顶部工具条
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color(0x52000000))
                .border(1.dp, Color(0x3DA8C8F0), RoundedCornerShape(15.dp))
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                snap.mode.label,
                color = Color(0xFFEBCB8C),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp)
            )
            Text(
                "余 ${snap.wallCount}",
                color = Color(0xCCD7E7FA), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            if (snap.laiziCode >= 0) {
                Text("癞子", color = Color(0xFFFFD54F), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                MjTileView(
                    tile = MjTile.byId(tileIdOfCode(snap.laiziCode)),
                    w = smallW * 0.92f,
                    laiziMark = true,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            MjToolButton("离桌", danger = true) { onExit() }
        }

        // ---------------- 三家对手
        MjOpponentPanel(left, false, meldW, Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
        MjOpponentPanel(right, false, meldW, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
        MjOpponentPanel(top, true, meldW, Modifier.align(Alignment.TopCenter).padding(top = 34.dp))

        // ---------------- 牌河（围绕中央）
        MjRiver(
            left.river, smallW, vertical = true,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 92.dp)
        )
        MjRiver(
            right.river, smallW, vertical = true,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 92.dp)
        )
        MjRiver(
            top.river, smallW, vertical = false,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp)
        )
        MjRiver(
            me.river, smallW, vertical = false,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 26.dp)
        )

        // ---------------- 中央提示
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
                color = Color(0xCCFFE082),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 150.dp)
            )
        }

        // ---------------- 我的副露（手牌上方左侧）
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 118.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            me.melds.forEach { m -> MjMeldRow(m, meldW) }
        }

        // ---------------- 手牌
        val hand = me.hand.sortedBy { it.code }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 54.dp),
            horizontalArrangement = Arrangement.spacedBy((-tileW.value * 0.16f).dp),
            verticalAlignment = Alignment.Bottom
        ) {
            hand.forEach { t ->
                MjTileView(
                    tile = t,
                    w = tileW,
                    selected = t.id in selected,
                    laiziMark = snap.laiziCode == t.code,
                    modifier = Modifier.clickable {
                        if (t.id in selected) {
                            val err = vm.discardSelected()
                            if (err != null) toast = System.nanoTime() to err
                        } else vm.toggleSelect(t.id)
                    }
                )
            }
        }

        // ---------------- 底部按钮条
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 暗杠 / 补杠快捷
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
                MjRectButton(R.drawable.mj_btn_hint, 34.dp) {
                    val err = vm.hint()
                    if (err != null) toast = System.nanoTime() to err
                }
                MjRectButton(R.drawable.mj_btn_discard, 34.dp) {
                    val err = vm.discardSelected()
                    if (err != null) toast = System.nanoTime() to err
                }
            }
            // 换三张确认
            if (snap.phase == MjPhase.SWAP3 && !snap.swapPicked) {
                MjRectButton(R.drawable.mj_btn_swap3, 34.dp) {
                    val err = vm.confirmSwap()
                    if (err != null) toast = System.nanoTime() to err
                }
            }
        }

        // ---------------- 宣告按钮（胡/杠/碰/吃/过）
        if (snap.myClaims.isNotEmpty()) {
            MjClaimBar(
                claims = snap.myClaims,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 118.dp),
                onClaim = { vm.doClaim(it) },
                onPass = { vm.passClaim() }
            )
        }

        // ---------------- 定缺浮层
        if (snap.phase == MjPhase.DINGQUE && me.dingque < 0) {
            MjDingQueBar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 118.dp),
                onPick = { vm.dingque(it) }
            )
        }

        // ---------------- 左下：我的信息
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                me.avatar, 44.dp,
                Modifier.border(
                    2.5.dp,
                    if (me.isTurn) Color(0xFFFFC107) else Color(0x66FFFFFF),
                    CircleShape
                )
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        me.name,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (me.isTurn) Color(0xFFFFE082) else Color.White
                    )
                    if (me.isDealer) {
                        Spacer(Modifier.width(3.dp))
                        Image(
                            painter = painterResource(R.drawable.mj_mark_zhuang),
                            contentDescription = "庄家",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    if (me.dingque >= 0) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "缺${suitLabel(me.dingque)}",
                            fontSize = 10.sp,
                            color = Color(0xFF80DEEA),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0x33123A6E))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (me.huRank > 0) {
                        Spacer(Modifier.width(5.dp))
                        Text("胡", fontSize = 11.sp, color = Gold, fontWeight = FontWeight.Black)
                    }
                }
                myBubble?.let { (_, t) -> Bubble(t) }
            }
        }

        // ---------------- 右下：快捷喊话
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp)
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

        // ---------------- 结算浮层
        if (snap.result != null) {
            MjResultOverlay(
                snap = snap,
                mySeat = mySeat,
                onAgain = { vm.again() },
                onExit = onExit
            )
        }

        // ---------------- 特效层（素材艺术字）
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
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
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
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = 170.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
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

// ================================================================ 组件

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

/** 素材横条按钮（提示/打出/换三张，原始比例 126:72） */
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
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .shadow(if (enabled) 5.dp else 0.dp, shape)
            .clip(shape)
            .then(if (enabled) Modifier.background(container) else Modifier.background(Color(0x55FFFFFF)))
            .border(1.5.dp, Color(0x80FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Color.White else Color(0x88FFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}

internal fun mjBlueBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFF5B8BE8), Color(0xFF3A63C0)))

internal fun mjOrangeBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFFFFC24D), Color(0xFFF07E1E)))

internal fun mjGoldBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFFFFD54F), Color(0xFFF9A825)))

internal fun mjRedBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFFEF5350), Color(0xFFB71C1C)))

/** 对手信息面板：头像/名/庄/缺/听/胡 + 副露 + 手牌背 */
@Composable
private fun MjOpponentPanel(
    seat: com.laoxiang.ddz.data.MjSeatView,
    horizontal: Boolean,
    meldW: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                seat.avatar, 42.dp,
                Modifier.border(
                    2.5.dp,
                    if (seat.isTurn) Color(0xFFFFC107) else Color(0x66FFFFFF),
                    CircleShape
                )
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        seat.name,
                        fontSize = 11.sp,
                        color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
                        maxLines = 1
                    )
                    if (seat.isDealer) {
                        Spacer(Modifier.width(3.dp))
                        Image(
                            painter = painterResource(R.drawable.mj_mark_zhuang),
                            contentDescription = "庄家",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    if (seat.huRank > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("胡", fontSize = 11.sp, color = Gold, fontWeight = FontWeight.Black)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("剩 ${seat.handCount}", fontSize = 10.sp, color = Color(0x99FFFFFF))
                    if (seat.dingque >= 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "缺${suitLabel(seat.dingque)}", fontSize = 9.sp,
                            color = Color(0xFF80DEEA),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x33123A6E))
                                .padding(horizontal = 3.dp, vertical = 0.5.dp)
                        )
                    }
                    if (seat.ting) {
                        Spacer(Modifier.width(3.dp))
                        Image(
                            painter = painterResource(R.drawable.mj_mark_ting),
                            contentDescription = "听牌",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        // 副露
        if (seat.melds.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                seat.melds.forEach { m -> MjMeldRow(m, meldW * 0.82f) }
            }
        }
        // 手牌背（对家横排；左右竖排）
        Spacer(Modifier.height(3.dp))
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.spacedBy((-2).dp)) {
                repeat(seat.handCount.coerceAtMost(13)) {
                    MjTileView(null, 15.dp, alpha = 0.92f)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy((-14).dp)) {
                repeat(seat.handCount.coerceAtMost(13)) {
                    MjTileView(null, 15.dp, alpha = 0.92f)
                }
            }
        }
    }
    if (horizontal) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, content = content)
    } else {
        Column(modifier, horizontalAlignment = Alignment.Start, content = content)
    }
}

/** 一组副露（碰/杠/吃小牌排） */
@Composable
private fun MjMeldRow(m: MjMeld, w: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy((-w.value * 0.28f).dp)) {
        m.tiles.forEach { t -> MjTileView(t, w) }
    }
}

/** 牌河 */
@Composable
private fun MjRiver(
    river: List<MjTile>,
    w: androidx.compose.ui.unit.Dp,
    vertical: Boolean,
    modifier: Modifier = Modifier
) {
    if (river.isEmpty()) return
    val chunked = river.chunked(if (vertical) 3 else 8)
    if (vertical) {
        Column(
            modifier
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            chunked.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { t -> MjTileView(t, w) }
                }
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            chunked.take(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { t -> MjTileView(t, w) }
                }
            }
        }
    }
}

/** 吃碰杠胡宣告条 */
@Composable
private fun MjClaimBar(
    claims: List<MjClaimOpt>,
    modifier: Modifier = Modifier,
    onClaim: (MjClaimOpt) -> Unit,
    onPass: () -> Unit
) {
    Row(
        modifier
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE612305C))
            .border(1.5.dp, Color(0x66A8C8F0), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        claims.forEach { opt ->
            when (opt.kind) {
                "HU" -> MjImageButton(R.drawable.mj_btn_hu, 56.dp) { onClaim(opt) }
                "GANG" -> MjImageButton(R.drawable.mj_btn_gang, 56.dp) { onClaim(opt) }
                "PENG" -> MjImageButton(R.drawable.mj_btn_peng, 56.dp) { onClaim(opt) }
                "CHI" -> MjImageButton(R.drawable.mj_btn_chi, 56.dp) { onClaim(opt) }
            }
        }
        MjImageButton(R.drawable.mj_btn_pass, 50.dp) { onPass() }
    }
}

/** 定缺选择条 */
@Composable
private fun MjDingQueBar(modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(
        modifier
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE612305C))
            .border(1.5.dp, Color(0x66A8C8F0), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("定缺：", color = Color(0xFFEBCB8C), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        listOf(0 to "缺万", 1 to "缺筒", 2 to "缺条").forEach { (suit, label) ->
            MjPillButton(label, mjBlueBrush()) { onPick(suit) }
        }
    }
}

/** 结算浮层（血战多赢家 / 流局查叫） */
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
                    result.liuju && myDelta >= 0 -> "流 局"
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
                                    } ?: if (result.liuju) "未胡" else "未胡",
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
