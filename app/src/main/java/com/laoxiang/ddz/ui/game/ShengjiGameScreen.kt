package com.laoxiang.ddz.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.data.Card
import com.laoxiang.ddz.data.CardSuit
import com.laoxiang.ddz.data.GdTeam
import com.laoxiang.ddz.data.LastActionType
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.data.SjRules
import com.laoxiang.ddz.data.SjSnapshot
import com.laoxiang.ddz.data.SjSeatView
import com.laoxiang.ddz.data.gdTeamOf
import com.laoxiang.ddz.ui.common.AvatarImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 升级发牌节奏：双副牌 108 张，慢速逐张（每张 0.38s ≈ 9.5 秒发完 25 张），边发边定主 */
private const val SJ_DEAL_MS = 380L

/**
 * 升级牌局（横屏）：左/顶/右三对手，顶部级牌+主花色+闲家得分。
 * 双副牌一起发不分先后；慢速发牌 + 亮主定主（对大王>对小王>对级牌>单张）；
 * 手牌自动两排：主牌（星标暖色）+ 四花色分组，级牌带「级」角标。
 */
@Composable
fun ShengjiGameScreen(vm: ShengjiViewModel, onExit: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()
    val snap = snapshot ?: run {
        Box(Modifier.fillMaxSize()) {
            TableBackground(
                bgKey = vm.prefs.tableBg,
                modifier = Modifier.fillMaxSize(),
                engraving = "老乡升级"
            )
            Box(
                Modifier.fillMaxSize().background(Color(0xFF16305C).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) { Text("准备开局…", color = Color(0xCCD7E7FA), fontSize = 15.sp) }
        }
        return
    }

    val me = snap.seats[0]
    val right = snap.seats[1]
    val top = snap.seats[2]
    val left = snap.seats[3]

    val myTurn = snap.phase == Phase.PLAYING && snap.turn == 0
    val bidding = snap.phase == Phase.BIDDING
    val burying = snap.phase == Phase.BURYING
    val myBurying = burying && snap.dealer == 0
    val selCards by vm.selected.collectAsState()
    val selBuryCount = selCards.size

    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 快捷喊话
    var showChat by remember { mutableStateOf(false) }
    var myBubble by remember { mutableStateOf<Pair<Long, String>?>(null) }
    LaunchedEffect(myBubble?.first) { if (myBubble != null) { delay(2600); myBubble = null } }

    LaunchedEffect(toast?.first) {
        if (toast != null) { delay(2000); toast = null }
    }

    // 亮主播报：任何一家亮主/反主 → 冒泡提示
    LaunchedEffect(snap.handNo, snap.claimSeat, snap.claimTier) {
        if (bidding && snap.claimSeat >= 0) {
            val who = if (snap.claimSeat == 0) "你" else snap.seats.getOrNull(snap.claimSeat)?.name ?: "?"
            val what = when {
                snap.claimNT -> if (snap.claimTier >= 4) "亮无主（对大王）" else "亮无主（对小王）"
                snap.claimTier >= 2 -> "反主 ${suitLabel(snap.claimSuit)}（对级牌）"
                else -> "亮 ${suitLabel(snap.claimSuit)} 主"
            }
            toast = System.nanoTime() to "$who $what"
        }
    }

    // ---- 慢速发牌逐张揭示（BIDDING 期间用真实发牌顺序；两副牌混发不分先后）
    var animRound by remember { mutableStateOf(-1) }
    var dealtShown by remember { mutableStateOf(0) }
    val dealHand: List<Card> = me.handDealOrder.ifEmpty { me.hand }
    val handSize = me.hand.size
    val revealing = bidding && dealtShown < dealHand.size
    LaunchedEffect(snap.handNo, handSize, snap.phase) {
        if (handSize == 0 || snap.phase != Phase.BIDDING) return@LaunchedEffect
        if (animRound != snap.handNo) {
            animRound = snap.handNo
            dealtShown = 0
            delay(300)
        }
        while (dealtShown < dealHand.size) {
            dealtShown++
            vm.sfx("deal", 0.75f)
            delay(SJ_DEAL_MS)
        }
        if (bidding) vm.settleBidding()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B3C6E))
            .graphicsLayer { translationX = shake.value.dp.toPx() }
    ) {
        val playedCardW = 36.dp
        TableBackground(
            bgKey = vm.prefs.tableBg,
            modifier = Modifier.fillMaxSize(),
            engraving = "老乡升级"
        )

        // 顶部工具条
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color(0x52123A6E))
                .border(1.dp, Color(0x3DA8C8F0), RoundedCornerShape(15.dp))
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "升级 · 打${rankLabel(snap.levelRank)} · " + when {
                    snap.phase == Phase.BIDDING && snap.claimSeat < 0 -> "定主中"
                    snap.trumpSuit == null -> "定主中"
                    snap.phase == Phase.BURYING -> "扣底中"
                    snap.claimNT && snap.phase != Phase.PLAYING -> "无主"
                    else -> "主${suitLabel(snap.trumpSuit)}" + if (snap.trumpSuit == CardSuit.JOKER) "(无主)" else ""
                },
                color = Color(0xFFEBCB8C), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp)
            )
            Text(
                "闲家 ${snap.oppPoints} 分",
                color = Color(0xCCD7E7FA), fontSize = 11.sp,
                modifier = Modifier.padding(end = 6.dp)
            )
            SjToolButton("离桌", danger = true) { onExit() }
        }

        // 对手
        SjSeatColumn(left, if (snap.claimSeat == left.seat) "已亮主" else null,
            Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 4.dp), playedCardW * 0.8f)
        SjSeatColumn(right, if (snap.claimSeat == right.seat) "已亮主" else null,
            Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 4.dp), playedCardW * 0.8f)
        SjSeatColumn(top, if (snap.claimSeat == top.seat) "已亮主" else null,
            Modifier.align(Alignment.TopCenter).padding(top = 42.dp), playedCardW * 0.7f)

        // 中央：一圈各家出的牌 / 刚收的上一圈（第四家出牌保留展示） / 不出标
        val availW = maxWidth
        Column(
            Modifier.align(Alignment.Center).offset(y = (-18).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val shownTrick = if (snap.trickPlays.isNotEmpty()) snap.trickPlays else snap.lastTrick
            val showingLast = snap.trickPlays.isEmpty() && snap.lastTrick.isNotEmpty()
            if (shownTrick.isNotEmpty()) {
                // 按总张数自适应压缩牌宽，四家都出也不溢出屏幕
                val cols = shownTrick.size
                val totalCards = shownTrick.sumOf { it.second.size }
                val fitW: androidx.compose.ui.unit.Dp = minOf(
                    playedCardW * 0.85f,
                    (availW - 40.dp - 10.dp * (cols - 1)) /
                            (cols + 0.58f * (totalCards - cols))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    shownTrick.forEach { (seat, cards) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PlayedCards(cards, fitW)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                snap.seats[seat].name + if (showingLast && seat == snap.lastTrickWinner) " 收圈" else "",
                                fontSize = 9.sp,
                                color = when {
                                    showingLast && seat == snap.lastTrickWinner -> Color(0xFFA5D6A7)
                                    seat == 0 -> Color(0xFFFFE082)
                                    else -> Color(0x99FFFFFF)
                                }
                            )
                        }
                    }
                }
            } else if (me.lastActionType == LastActionType.PASSED) {
                PassTag("不出")
            }
        }

        // 定主面板（发牌进度 >30% 后出现，参照欢乐升级：NT/♠/♥/♣/♦）
        if (bidding) {
            val showBar = dealtShown >= dealHand.size * 0.25f || !revealing
            if (showBar) {
                SjClaimBar(
                    snap = snap,
                    myHand = dealHand.ifEmpty { me.hand },
                    onClaim = { suit ->
                        val err = vm.claimTrump(suit)
                        if (err != null) toast = System.nanoTime() to err
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-52).dp)
                )
            }
        }

        // 场况提示
        val centerHint = when {
            bidding -> if (revealing) "发牌定主中…（亮主顺序：对大王＞对小王＞对级牌＞单张）" else "等待定主…"
            burying -> if (myBurying) "捡底成功！从手牌选 8 张扣为底牌（已选 ${selBuryCount}/8）"
                    else "等「${snap.seats.getOrNull(snap.dealer)?.name ?: "?"}」捡底扣牌…"
            myTurn && snap.lastPlay == null -> "你先出（单张/对子/拖拉机）"
            myTurn -> "跟 ${snap.lastPlay?.count ?: 0} 张"
            snap.phase == Phase.PLAYING -> "等「${snap.seats.getOrNull(snap.turn)?.name ?: "?"}」出牌…"
            else -> ""
        }
        if (centerHint.isNotEmpty()) {
            Text(
                centerHint, color = Color(0xCCFFFFFF), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
            )
        }

        // ---- 手牌：定主期=单排逐张；定主后/扣底期=多排花色分组（主牌星标 + 级牌角标 + 花色间隙）
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val handAvailH = screenH - 118.dp          // 顶部预留，其余全给手牌（贴底不剪切）
        var handBlockH by remember { mutableStateOf(150.dp) }
        val trump = snap.trumpSuit
        if (revealing) {
            HandRow(
                hand = dealHand.take(dealtShown),
                selected = selCards,
                onToggle = { vm.toggleSelect(it) },
                onSweep = { vm.selectCards(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 40.dp, end = 40.dp, bottom = GAME_STRIP_H)
            )
        } else {
            val display = me.hand
            val badges = remember(display, snap.levelRank) {
                display.filter { it.rank == snap.levelRank && it.suit != CardSuit.JOKER }
                    .associate { it.id to "级" }
            }
            val warmIds = remember(display, trump, snap.levelRank) {
                if (trump == null) emptySet()
                else display.filter { SjRules.isTrump(it, trump, snap.levelRank) }.map { it.id }.toSet()
            }
            val gapAfter = remember(display, trump, snap.levelRank) {
                val out = HashSet<Int>()
                fun groupKey(c: Card): Any =
                    if (trump != null && SjRules.isTrump(c, trump, snap.levelRank)) "T" else c.suit
                for (i in 0 until display.size - 1) {
                    if (groupKey(display[i]) != groupKey(display[i + 1])) out += display[i].id
                }
                out
            }
            HandRows(
                hand = display,
                selected = selCards,
                onToggle = { vm.toggleSelect(it) },
                onSweep = { vm.selectCards(it) },
                gapAfterIds = gapAfter,
                badges = badges,
                warmIds = warmIds,
                availH = handAvailH,
                onHeight = { handBlockH = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
        }

        // ---- 扣底面板（我坐庄）：捡起的底牌 + 选 8 张扣回（自动扣/扣底）
        if (myBurying) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = handBlockH + 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (snap.kitty.isNotEmpty()) {
                    Text(
                        "捡起的底牌（从中任选 8 张扣回，最后归收圈方计分）",
                        fontSize = 10.sp, color = Color(0x99FFFFFF)
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        snap.kitty.forEach { c -> PokerCard(c, 24.dp, raised = false, onClick = null) }
                    }
                    Spacer(Modifier.height(7.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PdkPillButton("自动扣", pdkBluePillBrush()) {
                        val err = vm.autoBury()
                        if (err != null) toast = System.nanoTime() to err
                    }
                    PdkPillButton(
                        "扣 底 ${selBuryCount}/8", pdkOrangePillBrush(),
                        enabled = selBuryCount == 8
                    ) {
                        val err = vm.burySelected()
                        if (err != null) toast = System.nanoTime() to err
                    }
                }
            }
        }

        // ---- 操作按钮：手牌正上方居中（提示/出牌保持在中间）
        if (myTurn && !bidding && !revealing) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = handBlockH + 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PdkPillButton("提 示", pdkBluePillBrush()) {
                    val err = vm.hint()
                    if (err != null) toast = System.nanoTime() to err
                }
                PdkPillButton("出 牌", pdkOrangePillBrush()) {
                    val err = vm.playSelected()
                    if (err != null) toast = System.nanoTime() to err
                }
            }
        }

        // ---- 右下角：仅快捷喊话入口（低于纸牌）
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 6.dp)
        ) {
            StripChatButton { showChat = true }
        }

        // 左下：我的头像（低于纸牌）
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
                Text(
                    me.name, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (me.isTurn) Color(0xFFFFE082) else Color.White
                )
                Text(
                    when {
                        snap.claimSeat == 0 -> "已亮主 · 坐庄"
                        snap.dealer == 0 && snap.phase != Phase.BIDDING -> "本副坐庄"
                        else -> "闲家"
                    },
                    fontSize = 9.sp, color = Color(0x99FFFFFF)
                )
            }
            myBubble?.let { (_, t) ->
                Spacer(Modifier.width(8.dp))
                Bubble(t)
            }
        }

        // 快捷喊话面板
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

        // 副牌结束结算面板
        snap.result?.let { r ->
            Box(
                Modifier.fillMaxSize().background(Color(0x99000A1F)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF7FFF8EC))
                        .border(2.dp, Color(0xFFC9A25E), RoundedCornerShape(20.dp))
                        .padding(horizontal = 30.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val iWon = gdTeamOf(0) == r.winnerTeam
                    Text(
                        if (iWon) "我方胜！" else "对方胜",
                        fontSize = 26.sp, fontWeight = FontWeight.Black,
                        color = if (iWon) Color(0xFFC62828) else Color(0xFF37474F)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "闲家得分 ${r.oppPoints} · ${if (r.switched) "闲家上台" else "庄家继续坐庄"}",
                        fontSize = 13.sp, color = Color(0xFF5D4037)
                    )
                    Text(
                        if (r.finalWin) "打 A 成功，终局！"
                        else "${r.winnerTeam.name}队升级 +${r.upgrade}，下副打 ${rankLabel(r.newLevels.getValue(r.winnerTeam))}",
                        fontSize = 13.sp, color = Color(0xFF5D4037),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PdkPillButton("离 桌", Brush.verticalGradient(listOf(Color(0xFF90A4AE), Color(0xFF607D8B)))) {
                            onExit()
                        }
                        if (!r.finalWin) {
                            PdkPillButton("下一副", pdkOrangePillBrush()) { vm.nextHand() }
                        }
                    }
                }
            }
        }

        toast?.let { (_, t) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    t, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = 160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/** 定主选条（参照欢乐升级）：无主 NT + 四花色，各自显示手中级牌/王数量；不够格置灰 */
@Composable
private fun SjClaimBar(
    snap: SjSnapshot,
    myHand: List<Card>,
    onClaim: (CardSuit?) -> Unit,
    modifier: Modifier = Modifier
) {
    val levelRank = snap.levelRank
    val suitCount = CardSuit.values()
        .filter { it != CardSuit.JOKER }
        .associateWith { s -> myHand.count { it.rank == levelRank && it.suit == s } }
    val jokerCount = myHand.count { it.suit == CardSuit.JOKER }

    val myOptions = if (snap.claimSeat == 0) emptyMap<CardSuit?, Int>() else buildMap<CardSuit?, Int> {
        suitCount.forEach { (s, n) ->
            if (n >= 1) put(s, if (n >= 2) 2 else 1)
        }
        if (myHand.count { it.rank == 17 } >= 2) put(null, 4)
        else if (myHand.count { it.rank == 16 } >= 2) put(null, 3)
    }

    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6142E52))
            .border(1.5.dp, Color(0x88FFD54F), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "亮主", fontSize = 12.sp, fontWeight = FontWeight.Black,
            color = Color(0xFFFFD54F), modifier = Modifier.padding(end = 2.dp)
        )
        ClaimTile(
            label = "无主", sub = "对王$jokerCount",
            highlight = snap.claimNT,
            enabled = myOptions.containsKey(null)
        ) { onClaim(null) }
        suitCount.forEach { (s, n) ->
            ClaimTile(
                label = suitLabel(s),
                sub = "级牌$n",
                red = s == CardSuit.HEART || s == CardSuit.DIAMOND,
                highlight = !snap.claimNT && snap.claimSuit == s,
                enabled = myOptions.containsKey(s)
            ) { onClaim(s) }
        }
    }
}

@Composable
private fun ClaimTile(
    label: String,
    sub: String,
    red: Boolean = false,
    highlight: Boolean = false,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    highlight -> Color(0xFFFFD54F)
                    enabled -> Color(0xFF2A4D7E)
                    else -> Color(0xFF1B2F4B)
                }
            )
            .border(
                1.dp,
                when {
                    highlight -> Color(0xFFC9A25E)
                    enabled -> Color(0x66A8C8F0)
                    else -> Color(0x22FFFFFF)
                },
                RoundedCornerShape(9.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 15.sp, fontWeight = FontWeight.Black,
            color = when {
                highlight -> Color(0xFF4E2600)
                !enabled -> Color(0x55FFFFFF)
                red -> Color(0xFFEF9A9A)
                else -> Color.White
            }
        )
        Text(
            sub, fontSize = 8.sp,
            color = when {
                highlight -> Color(0xFF6D4C00)
                !enabled -> Color(0x44FFFFFF)
                else -> Color(0x99FFFFFF)
            }
        )
    }
}

private fun rankLabel(rank: Int): String = when (rank) {
    11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; else -> "$rank"
}

private fun suitLabel(s: CardSuit?): String = when (s) {
    CardSuit.SPADE -> "♠"
    CardSuit.HEART -> "♥"
    CardSuit.DIAMOND -> "♦"
    CardSuit.CLUB -> "♣"
    CardSuit.JOKER -> "无主"
    null -> "待定"
}

@Composable
private fun SjSeatColumn(
    seat: SjSeatView,
    bubble: String?,
    modifier: Modifier = Modifier,
    cardW: androidx.compose.ui.unit.Dp
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        bubble?.let {
            Bubble(it)
            Spacer(Modifier.height(3.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                seat.avatar, 48.dp,
                Modifier.border(
                    2.5.dp,
                    when {
                        seat.isTurn -> Color(0xFFFFC107)
                        seat.team == GdTeam.A -> Color(0xFF6EC6FF)
                        else -> Color(0xFFFFAB91)
                    },
                    CircleShape
                )
            )
            Spacer(Modifier.width(7.dp))
            Column {
                Text(
                    seat.name, fontSize = 11.sp,
                    color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
                    maxLines = 1
                )
                Text("剩 ${seat.handCount} 张", fontSize = 10.sp, color = Color(0x99FFFFFF))
            }
        }
    }
}

@Composable
private fun SjToolButton(
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
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
