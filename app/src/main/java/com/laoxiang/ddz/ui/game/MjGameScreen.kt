package com.laoxiang.ddz.ui.game

import androidx.compose.animation.core.Animatable
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
 * 麻将牌局 —— 按参考图排版：
 * 木框青绿呢面桌面；下=我（大牌手牌+右侧副露）、右=下家、上=对家（卧牌背）、左=上家（立牌背）；
 * 左右贴边牌墙立柱、底部牌墙横排；四家牌河用素材卧牌围绕中央罗盘；
 * 胡碰杠吃圆形素材按钮、定缺三色圆钮、换三张原版按钮；结算/特效/快捷喊话浮层。
 */

/** 出牌卧牌贴图（mj_disc_0..33）与旋转版 */
private val DISC_RES = intArrayOf(
    R.drawable.mj_disc_0, R.drawable.mj_disc_1, R.drawable.mj_disc_2, R.drawable.mj_disc_3,
    R.drawable.mj_disc_4, R.drawable.mj_disc_5, R.drawable.mj_disc_6, R.drawable.mj_disc_7,
    R.drawable.mj_disc_8, R.drawable.mj_disc_9, R.drawable.mj_disc_10, R.drawable.mj_disc_11,
    R.drawable.mj_disc_12, R.drawable.mj_disc_13, R.drawable.mj_disc_14, R.drawable.mj_disc_15,
    R.drawable.mj_disc_16, R.drawable.mj_disc_17, R.drawable.mj_disc_18, R.drawable.mj_disc_19,
    R.drawable.mj_disc_20, R.drawable.mj_disc_21, R.drawable.mj_disc_22, R.drawable.mj_disc_23,
    R.drawable.mj_disc_24, R.drawable.mj_disc_25, R.drawable.mj_disc_26, R.drawable.mj_disc_27,
    R.drawable.mj_disc_28, R.drawable.mj_disc_29, R.drawable.mj_disc_30, R.drawable.mj_disc_31,
    R.drawable.mj_disc_32, R.drawable.mj_disc_33
)

private val DISC_ROT = intArrayOf(
    R.drawable.mj_disc_0r, R.drawable.mj_disc_1r, R.drawable.mj_disc_2r, R.drawable.mj_disc_3r,
    R.drawable.mj_disc_4r, R.drawable.mj_disc_5r, R.drawable.mj_disc_6r, R.drawable.mj_disc_7r,
    R.drawable.mj_disc_8r, R.drawable.mj_disc_9r, R.drawable.mj_disc_10r, R.drawable.mj_disc_11r,
    R.drawable.mj_disc_12r, R.drawable.mj_disc_13r, R.drawable.mj_disc_14r, R.drawable.mj_disc_15r,
    R.drawable.mj_disc_16r, R.drawable.mj_disc_17r, R.drawable.mj_disc_18r, R.drawable.mj_disc_19r,
    R.drawable.mj_disc_20r, R.drawable.mj_disc_21r, R.drawable.mj_disc_22r, R.drawable.mj_disc_23r,
    R.drawable.mj_disc_24r, R.drawable.mj_disc_25r, R.drawable.mj_disc_26r, R.drawable.mj_disc_27r,
    R.drawable.mj_disc_28r, R.drawable.mj_disc_29r, R.drawable.mj_disc_30r, R.drawable.mj_disc_31r,
    R.drawable.mj_disc_32r, R.drawable.mj_disc_33r
)

private val NUM_RES = intArrayOf(
    R.drawable.mj_num0, R.drawable.mj_num1, R.drawable.mj_num2, R.drawable.mj_num3,
    R.drawable.mj_num4, R.drawable.mj_num5, R.drawable.mj_num6, R.drawable.mj_num7,
    R.drawable.mj_num8, R.drawable.mj_num9
)

/** 风位指示（(turn-dealer+4)%4 → 东南西北） */
private val WIND_RES = intArrayOf(
    R.drawable.mj_wind_e, R.drawable.mj_wind_s, R.drawable.mj_wind_w, R.drawable.mj_wind_n
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
        val frame = W * 0.0285f                     // 木框厚度
        val handW = ((W - 200.dp) / 14.6f).coerceAtMost(H * 0.098f)
        val handH = handW * 1.382f
        val meldW = handW * 0.78f
        val discW = handW * 0.60f
        val backW = handW * 0.44f
        val backH = backW * 1.5f
        val backLieW = handW * 0.72f
        val wallW = handW * 0.42f
        val wallH = wallW * 122f / 82f
        val plateSz = H * 0.245f
        val edgeX = frame + wallW * 0.75f           // 左右贴边带中心

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

        // ================= 牌墙：左右上角立柱 + 底部横排 =================
        Column(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = edgeX - wallW * 0.35f, y = frame - 2.dp),
            verticalArrangement = Arrangement.spacedBy(wallW * 0.28f)
        ) {
            repeat(4) { MjWallTile(wallW, vertical = true) }
        }
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = -(edgeX - wallW * 0.35f), y = frame - 2.dp),
            verticalArrangement = Arrangement.spacedBy(wallW * 0.28f)
        ) {
            repeat(4) { MjWallTile(wallW, vertical = true) }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -(frame * 0.5f + handH + wallH * 0.5f + 5.dp)),
            horizontalArrangement = Arrangement.spacedBy(wallW * 0.05f)
        ) {
            repeat(12) { MjWallTile(wallW, vertical = false) }
        }

        // ================= 对家：卧牌背横排 =================
        val backsTop = frame + 8.dp + backLieW * 0.5f
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = backsTop - backLieW * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(-backLieW * 0.30f)
        ) {
            repeat(top.handCount.coerceIn(1, 14)) { MjBackLie(backLieW) }
        }
        // 对家副露（顶部右侧）
        if (top.melds.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = backsTop + backLieW * 0.28f)
                    .padding(end = frame + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                top.melds.forEach { m -> m.tiles.forEach { t -> MjDiscTile(t.code, discW * 0.94f) } }
            }
        }
        // 对家牌河（牌背下方，最新一排靠中央）
        val topRiverRows = top.river.chunked(8).takeLast(2)
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = backsTop + backLieW * 0.40f + discW * 0.52f + 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            topRiverRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { t -> MjDiscTile(t.code, discW) }
                }
            }
        }

        // ================= 上家（左）/ 下家（右）：立牌背列 + 副露 + 牌河 =================
        val colTop = H * 0.26f
        val backSp = backH * 0.44f
        listOf(left to false, right to true).forEach { (seat, isRight) ->
            val backs = seat.handCount.coerceIn(1, 14)
            Column(
                Modifier
                    .align(if (isRight) Alignment.TopEnd else Alignment.TopStart)
                    .offset(
                        x = (if (isRight) -1 else 1) * (edgeX - backW * 0.5f),
                        y = colTop
                    ),
                verticalArrangement = Arrangement.spacedBy(-backH * 0.56f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(backs) { MjBackStand(backW) }
            }
            // 副露：立背列下方竖排卧牌（超过 6 张分双列，防溢出屏幕）
            val backsEnd = colTop + (backs - 1) * backH * 0.44f + backH * 0.5f
            if (seat.melds.isNotEmpty()) {
                Row(
                    Modifier
                        .align(if (isRight) Alignment.TopEnd else Alignment.TopStart)
                        .offset(
                            x = (if (isRight) -1 else 1) * (edgeX - discW * 0.35f),
                            y = backsEnd + 10.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    seat.melds.flatMap { it.tiles }.chunked(6).forEach { col ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            col.forEach { t -> MjDiscTile(t.code, discW * 0.88f, rotated = true) }
                        }
                    }
                }
            }
            // 牌河：竖排（旋转卧牌），靠内一列
            val riverCols = seat.river.chunked(4).takeLast(2)
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(
                        x = (if (isRight) -1 else 1) * (frame + wallW * 1.53f + discW * 0.5f),
                        y = H * 0.03f
                    ),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                riverCols.forEach { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        col.forEach { t -> MjDiscTile(t.code, discW, rotated = true) }
                    }
                }
            }
        }

        // ================= 中央罗盘 + 剩余数 + 风位 =================
        val plateOffsetY = -H * 0.045f
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(y = plateOffsetY)
                .size(plateSz)
        ) {
            Image(
                painter = painterResource(R.drawable.mj_plate),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NumDigits(snap.wallCount, plateSz * 0.20f)
            }
            Image(
                painter = painterResource(WIND_RES[(snap.turn - snap.dealer + 4) % 4]),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = plateSz * 0.16f)
                    .size(plateSz * 0.30f, plateSz * 0.17f)
            )
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
                color = Color(0xFFE6C36A), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = plateOffsetY + plateSz * 0.66f)
            )
        }

        // ================= 我的牌河（牌墙上方，最新靠墙） =================
        val myRiverRows = me.river.chunked(8).takeLast(2)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(
                    x = W * 0.12f,
                    y = -(frame * 0.5f + handH + wallH + discW * 1.04f + 12.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.End
        ) {
            myRiverRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { t -> MjDiscTile(t.code, discW) }
                }
            }
        }

        // ================= 手牌 + 副露 =================
        val hand = me.hand.sortedBy { it.code }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = frame * 0.5f)
                .padding(start = 96.dp, end = 6.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(horizontalArrangement = Arrangement.spacedBy(handW * 0.035f)) {
                hand.forEach { t ->
                    MjTileView(
                        tile = t,
                        w = handW,
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
            if (me.melds.isNotEmpty()) {
                Spacer(Modifier.width(handW * 0.14f))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(meldW * 0.10f)
                ) {
                    me.melds.forEach { m ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(handW * 0.035f),
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
                .padding(end = frame + wallW * 1.7f, bottom = frame * 0.5f + handH + 10.dp),
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
                    .padding(bottom = frame * 0.5f + handH * 1.32f),
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
                .padding(start = frame * 0.7f, bottom = frame * 0.4f)
        )
        MjPlayerCard(
            top, false,
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = frame * 0.7f, top = frame * 0.5f)
        )
        MjPlayerCard(
            left, false,
            Modifier
                .align(Alignment.CenterStart)
                .offset(y = -H * 0.29f)
                .padding(start = frame + wallW * 1.53f + discW * 0.62f)
        )
        MjPlayerCard(
            right, false,
            Modifier
                .align(Alignment.CenterEnd)
                .offset(y = -H * 0.29f)
                .padding(end = frame + wallW * 1.53f + discW * 0.62f)
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

/** 出牌卧牌（素材 tablemjwh0 + 原刻字）；rotated=true 用预旋转版（左右家） */
@Composable
internal fun MjDiscTile(code: Int, w: Dp, rotated: Boolean = false) {
    Image(
        painter = painterResource(if (rotated) DISC_ROT[code.coerceIn(0, 33)] else DISC_RES[code.coerceIn(0, 33)]),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(w, w)
    )
}

/** 卧牌背（对家手牌，素材 cc1） */
@Composable
internal fun MjBackLie(w: Dp) {
    Image(
        painter = painterResource(R.drawable.mj_back_lie),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(w, w)
    )
}

/** 立牌背（左右家手牌，素材 cc2） */
@Composable
internal fun MjBackStand(w: Dp) {
    Image(
        painter = painterResource(R.drawable.mj_back),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(w, w * 1.5f)
    )
}

/** 牌墙端视（素材 cemian1；vertical=左右墙立柱用预旋转版） */
@Composable
internal fun MjWallTile(w: Dp, vertical: Boolean) {
    Image(
        painter = painterResource(if (vertical) R.drawable.mj_wall_r else R.drawable.mj_wall),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(w, if (vertical) w * 82f / 122f else w * 122f / 82f)
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
