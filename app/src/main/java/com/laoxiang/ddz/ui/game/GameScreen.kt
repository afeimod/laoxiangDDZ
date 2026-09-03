package com.laoxiang.ddz.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.LastActionType
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 牌局界面：横竖屏自适应
 */
@Composable
fun GameScreen(gameVm: GameViewModel, onExit: () -> Unit) {
    val snapshot by gameVm.snapshot.collectAsState()
    val snap = snapshot ?: run {
        // 联机等待首个快照
        Box(
            Modifier
                .fillMaxSize()
                .background(DarkRed),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = LightGold)
                Spacer(Modifier.height(10.dp))
                Text("等房主开局…", color = Color(0xCCFFD9A0))
            }
        }
        return
    }
    val mySeat = gameVm.mySeat.collectAsState().value
    val me = snap.seats.first { it.seat == mySeat }
    val next = snap.seats.first { it.seat == (mySeat + 1) % 3 }
    val prev = snap.seats.first { it.seat == (mySeat + 2) % 3 }
    val counter by gameVm.cardCounter.collectAsState()
    val bubbles by gameVm.chatBubbles.collectAsState()

    // ---------- 状态
    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showChat by remember { mutableStateOf(false) }
    var showCounter by remember { mutableStateOf(true) }
    var bigText by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val bigTextScale = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 叫抢阶段气泡（临时）
    val bidBubbles = remember { mutableStateMapOf<Int, String>() }

    val fxFlow = gameVm.fx
    LaunchedEffect(Unit) {
        fxFlow.collect { f ->
            when (f) {
                is Fx.Bomb -> {
                    bigText = if (f.rocket) "王 炸 ！" else "炸 弹 ！"
                    scope.launch {
                        flash.snapTo(if (f.rocket) 0.75f else 0.45f)
                        flash.animateTo(0f, tween(700))
                    }
                    scope.launch {
                        bigTextScale.snapTo(0.2f)
                        bigTextScale.animateTo(1.15f, tween(220))
                        delay(600)
                        bigTextScale.animateTo(0f, tween(300))
                    }
                    scope.launch {
                        repeat(7) { i ->
                            shake.snapTo(if (i % 2 == 0) 16f else -16f)
                            delay(45)
                        }
                        shake.snapTo(0f)
                    }
                }
                is Fx.Plane -> {
                    bigText = "飞 机 起 飞 ！"
                    scope.launch {
                        bigTextScale.snapTo(0.2f)
                        bigTextScale.animateTo(1.05f, tween(240))
                        delay(500)
                        bigTextScale.animateTo(0f, tween(280))
                    }
                }
                is Fx.BidCall -> bidBubbles[f.seat] = "叫地主！"
                is Fx.Rob -> bidBubbles[f.seat] = "抢地主！"
                is Fx.BidPass -> bidBubbles[f.seat] = "不叫"
                is Fx.RobPass -> bidBubbles[f.seat] = "不抢"
                is Fx.Redeal -> toast = System.nanoTime() to "无人叫地主，重新发牌"
                is Fx.LandlordSet -> toast = System.nanoTime() to "底牌归地主，加倍！"
                else -> {}
            }
        }
    }
    // 气泡自动清理
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            gameVm.trimBubbles()
            if (bidBubbles.isNotEmpty()) {
                delay(2000)
                bidBubbles.clear()
            }
        }
    }
    // toast 自动清理（按 id 触发，重复消息也能弹）
    LaunchedEffect(toast?.first) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    val tableRes = if (gameVm.prefs.tableStyle == "round") R.drawable.table_round else R.drawable.table_square

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF571010))
    ) {
        val portrait = maxWidth < maxHeight
        val handCardW = if (portrait) (maxWidth / 7.2f) else (maxWidth / 13f).coerceAtMost(76.dp)
        val playedCardW = if (portrait) (maxWidth / 12f).coerceIn(26.dp, 44.dp) else 38.dp

        // 背景 + 桌子整体（受炸弹震动影响）
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = shake.value.dp.toPx()
                }
        ) {
            Image(
                painter = painterResource(
                    if (portrait) R.drawable.bg_game_portrait else R.drawable.bg_game_landscape
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (portrait) {
                PortraitTable(
                    snap = snap, me = me, next = next, prev = prev,
                    tableRes = tableRes, counter = counter, showCounter = showCounter,
                    bubbles = bubbles, bidBubbles = bidBubbles,
                    playedCardW = playedCardW, handCardW = handCardW,
                    gameVm = gameVm, mySeat = mySeat,
                    onToast = { toast = System.nanoTime() to (it ?: "") },
                    onToggleCounter = { showCounter = !showCounter },
                    onShowChat = { showChat = true },
                    onExit = onExit
                )
            } else {
                LandscapeTable(
                    snap = snap, me = me, next = next, prev = prev,
                    tableRes = tableRes, counter = counter, showCounter = showCounter,
                    bubbles = bubbles, bidBubbles = bidBubbles,
                    playedCardW = playedCardW, handCardW = handCardW,
                    gameVm = gameVm, mySeat = mySeat,
                    onToast = { toast = System.nanoTime() to (it ?: "") },
                    onToggleCounter = { showCounter = !showCounter },
                    onShowChat = { showChat = true },
                    onExit = onExit
                )
            }
        }

        // ---------- 全屏特效层
        if (flash.value > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFE53935).copy(alpha = flash.value * 0.6f))
            )
        }
        if (bigTextScale.value > 0.01f && bigText.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    bigText,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFFD54F),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = bigTextScale.value
                            scaleY = bigTextScale.value
                            alpha = bigTextScale.value
                        }
                        .background(Color(0x885D1010), RoundedCornerShape(14.dp))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
        }

        // toast
        toast?.let { (_, t) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    t,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = 150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        // 快捷聊天面板
        AnimatedVisibility(
            visible = showChat,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            QuickChatPanel(
                onSend = { gameVm.sendChat(it) },
                onDismiss = { showChat = false }
            )
        }
    }
}

// ================================================================= 竖屏

@Composable
private fun PortraitTable(
    snap: com.laoxiang.ddz.data.GameSnapshot,
    me: com.laoxiang.ddz.data.SeatView,
    next: com.laoxiang.ddz.data.SeatView,
    prev: com.laoxiang.ddz.data.SeatView,
    tableRes: Int,
    counter: Map<Int, Int>,
    showCounter: Boolean,
    bubbles: List<com.laoxiang.ddz.ui.game.ChatBubble>,
    bidBubbles: Map<Int, String>,
    playedCardW: androidx.compose.ui.unit.Dp,
    handCardW: androidx.compose.ui.unit.Dp,
    gameVm: GameViewModel,
    mySeat: Int,
    onToast: (String?) -> Unit,
    onToggleCounter: () -> Unit,
    onShowChat: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // 顶栏
        TopBar(snap, counter, showCounter, onToggleCounter, onShowChat, onExit, compact = true)
        Spacer(Modifier.height(4.dp))

        // 牌桌区
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
            Image(
                painter = painterResource(tableRes),
                contentDescription = "牌桌",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.96f)
            )

            // 左上：上家
            OpponentBlock(
                seat = prev,
                showCards = snap.phase == com.laoxiang.ddz.data.Phase.PLAYING,
                chatText = bubbleFor(bubbles, bidBubbles, prev.seat),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 6.dp)
            )
            // 右上：下家
            OpponentBlock(
                seat = next,
                showCards = snap.phase == com.laoxiang.ddz.data.Phase.PLAYING,
                chatText = bubbleFor(bubbles, bidBubbles, next.seat),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp, top = 6.dp)
            )

            // 两侧出牌
            SeatPlayArea(
                seat = prev, phase = snap.phase,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp),
                cardW = playedCardW
            )
            SeatPlayArea(
                seat = next, phase = snap.phase,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
                cardW = playedCardW
            )

            // 中央：叫抢提示 / 我的出牌
            if (snap.phase in setOf(Phase.BIDDING, Phase.ROBBING)) {
                BiddingCenter(
                    snap = snap, mySeat = mySeat, gameVm = gameVm, onToast = onToast,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (me.lastActionType == LastActionType.PLAYED && me.lastPlayed.isNotEmpty()) {
                        PlayedCards(me.lastPlayed, playedCardW)
                    }
                    if (me.lastActionType == LastActionType.PASSED) {
                        PassTag("要不起")
                    }
                }
            }
        }

        // 我的行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarImage(
                    me.avatar, 40.dp,
                    Modifier.border(
                        2.dp,
                        when {
                            me.isTurn -> Color(0xFFFFC107)
                            me.isLandlord -> Gold
                            else -> Color.Transparent
                        }, CircleShape
                    )
                )
                Text(
                    me.name, fontSize = 10.sp,
                    color = Color(0xCCFFFFFF), maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    snap.phase == Phase.BIDDING && snap.bidCursor != mySeat -> "等「${nameOf(snap, snap.bidCursor)}」叫地主…"
                    snap.phase == Phase.ROBBING && snap.robCursor != mySeat -> "等「${nameOf(snap, snap.robCursor)}」抢地主…"
                    snap.phase == Phase.PLAYING && snap.turn != mySeat -> "等「${nameOf(snap, snap.turn)}」出牌…"
                    snap.phase == Phase.PLAYING && snap.turn == mySeat && snap.lastMove == null -> "你先出，随便走"
                    snap.phase == Phase.PLAYING -> "轮到你出牌"
                    else -> ""
                },
                fontSize = 13.sp,
                color = Color(0xE6FFE082),
                modifier = Modifier.weight(1f)
            )
            val myBubble = bubbleFor(bubbles, bidBubbles, mySeat)
            if (myBubble != null) Bubble(myBubble)
        }

        // 操作按钮
        ActionButtons(
            snap = snap, mySeat = mySeat, gameVm = gameVm, onToast = onToast
        )

        // 手牌
        HandRow(
            hand = me.hand,
            selected = gameVm.selected.collectAsState().value,
            cardW = handCardW,
            onToggle = { gameVm.toggleSelect(it) }
        )
        Spacer(Modifier.height(4.dp))
    }
}

// ================================================================= 横屏

@Composable
private fun LandscapeTable(
    snap: com.laoxiang.ddz.data.GameSnapshot,
    me: com.laoxiang.ddz.data.SeatView,
    next: com.laoxiang.ddz.data.SeatView,
    prev: com.laoxiang.ddz.data.SeatView,
    tableRes: Int,
    counter: Map<Int, Int>,
    showCounter: Boolean,
    bubbles: List<com.laoxiang.ddz.ui.game.ChatBubble>,
    bidBubbles: Map<Int, String>,
    playedCardW: androidx.compose.ui.unit.Dp,
    handCardW: androidx.compose.ui.unit.Dp,
    gameVm: GameViewModel,
    mySeat: Int,
    onToast: (String?) -> Unit,
    onToggleCounter: () -> Unit,
    onShowChat: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // 左侧对手
        Column(
            Modifier.width(120.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OpponentBlock(
                seat = prev,
                showCards = snap.phase == Phase.PLAYING,
                chatText = bubbleFor(bubbles, bidBubbles, prev.seat)
            )
            Spacer(Modifier.height(10.dp))
            SeatPlayArea(seat = prev, phase = snap.phase, cardW = playedCardW * 0.85f)
        }

        // 中间主区
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(snap, counter, showCounter, onToggleCounter, onShowChat, onExit, compact = false)

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
                Image(
                    painter = painterResource(tableRes),
                    contentDescription = "牌桌",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().alpha(0.96f)
                )
                // 中央
                if (snap.phase in setOf(Phase.BIDDING, Phase.ROBBING)) {
                    BiddingCenter(
                        snap = snap, mySeat = mySeat, gameVm = gameVm, onToast = onToast,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (me.lastActionType == LastActionType.PLAYED && me.lastPlayed.isNotEmpty()) {
                            PlayedCards(me.lastPlayed, playedCardW)
                        }
                        if (me.lastActionType == LastActionType.PASSED) {
                            PassTag("要不起")
                        }
                    }
                }
                // 右上角提示
                Text(
                    when {
                        snap.phase == Phase.BIDDING && snap.bidCursor != mySeat -> "等「${nameOf(snap, snap.bidCursor)}」叫地主…"
                        snap.phase == Phase.ROBBING && snap.robCursor != mySeat -> "等「${nameOf(snap, snap.robCursor)}」抢地主…"
                        snap.phase == Phase.PLAYING && snap.turn != mySeat -> "等「${nameOf(snap, snap.turn)}」出牌…"
                        snap.phase == Phase.PLAYING && snap.turn == mySeat && snap.lastMove == null -> "你先出，随便走"
                        snap.phase == Phase.PLAYING -> "轮到你出牌"
                        else -> ""
                    },
                    fontSize = 13.sp, color = Color(0xE6FFE082),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
                val myBubble = bubbleFor(bubbles, bidBubbles, mySeat)
                if (myBubble != null) {
                    Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { Bubble(myBubble) }
                }
            }

            ActionButtons(snap, mySeat, gameVm, onToast)
            HandRow(
                hand = me.hand,
                selected = gameVm.selected.collectAsState().value,
                cardW = handCardW,
                onToggle = { gameVm.toggleSelect(it) }
            )
            Spacer(Modifier.height(4.dp))
        }

        // 右侧对手
        Column(
            Modifier.width(120.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OpponentBlock(
                seat = next,
                showCards = snap.phase == Phase.PLAYING,
                chatText = bubbleFor(bubbles, bidBubbles, next.seat)
            )
            Spacer(Modifier.height(10.dp))
            SeatPlayArea(seat = next, phase = snap.phase, cardW = playedCardW * 0.85f)
        }
    }
}

// ================================================================= 共用组件

@Composable
private fun TopBar(
    snap: com.laoxiang.ddz.data.GameSnapshot,
    counter: Map<Int, Int>,
    showCounter: Boolean,
    onToggleCounter: () -> Unit,
    onShowChat: () -> Unit,
    onExit: () -> Unit,
    compact: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 记牌器
        TextButton(
            onClick = onToggleCounter,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("记牌", color = Color(0xFFFFE082), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (showCounter && counter.isNotEmpty()) {
            CardCounterPanel(counts = counter)
        }
        Spacer(Modifier.width(8.dp))
        if (snap.bottomCards.isNotEmpty()) {
            BottomCardsBar(
                bottom = snap.bottomCards,
                multiplier = snap.multiplier,
                landlordName = snap.seats.getOrNull(snap.landlord)?.name
            )
        } else {
            Text(
                if (snap.bottomHidden) "底牌 ×${snap.multiplier}" else "",
                color = Color(0xCCFFD9A0), fontSize = 12.sp
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onShowChat, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("喊话", color = Color(0xFFFFE082), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = onExit,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("离桌", color = Color(0xCCFF8A80), fontSize = 12.sp)
        }
    }
}

/** 某座位出牌展示区 */
@Composable
private fun SeatPlayArea(
    seat: com.laoxiang.ddz.data.SeatView,
    phase: Phase,
    modifier: Modifier = Modifier,
    cardW: androidx.compose.ui.unit.Dp
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            seat.lastActionType == LastActionType.PLAYED && seat.lastPlayed.isNotEmpty() ->
                PlayedCards(seat.lastPlayed, cardW)
            seat.lastActionType == LastActionType.PASSED ->
                PassTag("要不起")
            phase == Phase.PLAYING && seat.handCount == 0 ->
                Text("走完了！", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 叫抢面板（桌面中央） */
@Composable
private fun BiddingCenter(
    snap: com.laoxiang.ddz.data.GameSnapshot,
    mySeat: Int,
    gameVm: GameViewModel,
    onToast: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val myBidTurn = (snap.phase == Phase.BIDDING && snap.bidCursor == mySeat) ||
            (snap.phase == Phase.ROBBING && snap.robCursor == mySeat)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (snap.phase == Phase.BIDDING && snap.bottomHidden) {
            // 未知底牌显示
            Row {
                CardBack(30.dp); Spacer(Modifier.width(3.dp))
                CardBack(30.dp); Spacer(Modifier.width(3.dp))
                CardBack(30.dp)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (myBidTurn) {
            Text(
                if (snap.phase == Phase.BIDDING) "你叫不叫地主？" else "抢不抢地主？",
                color = Color(0xFFFFE082), fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(
                    onClick = {
                        val err = gameVm.bid(false)
                        if (err != null) onToast(err)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFE0B2))
                ) { Text(if (snap.phase == Phase.BIDDING) "不叫" else "不抢") }
                Button(
                    onClick = {
                        val err = gameVm.bid(true)
                        if (err != null) onToast(err)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChineseRed)
                ) { Text(if (snap.phase == Phase.BIDDING) "叫地主" else "抢地主", fontWeight = FontWeight.Bold) }
            }
        } else {
            val cursor = if (snap.phase == Phase.BIDDING) snap.bidCursor else snap.robCursor
            Text(
                "等「${nameOf(snap, cursor)}」${if (snap.phase == Phase.BIDDING) "叫地主" else "抢地主"}…",
                color = Color(0xCCFFFFFF), fontSize = 14.sp
            )
        }
    }
}

/** 出牌操作按钮行 */
@Composable
private fun ActionButtons(
    snap: com.laoxiang.ddz.data.GameSnapshot,
    mySeat: Int,
    gameVm: GameViewModel,
    onToast: (String?) -> Unit
) {
    val myTurn = snap.phase == Phase.PLAYING && snap.turn == mySeat
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (myTurn) {
            OutlinedButton(
                onClick = {
                    val err = gameVm.passTurn()
                    if (err != null) onToast(err)
                },
                enabled = snap.lastMove != null,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFFE0B2),
                    disabledContentColor = Color(0x44FFE0B2)
                ),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            ) {
                Text("不出", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    val err = gameVm.hint()
                    if (err != null) onToast(err)
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            ) {
                Text("提示", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val err = gameVm.playSelected()
                    if (err != null) onToast(err)
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ChineseRed),
                modifier = Modifier.weight(1.4f).heightIn(min = 44.dp)
            ) {
                Text("出 牌", fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
        } else if (snap.phase == Phase.PLAYING) {
            Spacer(Modifier.height(44.dp))
        }
    }
}

/** 手牌行 */
@Composable
private fun HandRow(
    hand: List<com.laoxiang.ddz.data.Card>,
    selected: Set<Int>,
    cardW: androidx.compose.ui.unit.Dp,
    onToggle: (Int) -> Unit
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        val overlap = if (hand.size > 13) cardW * 0.34f else cardW * 0.44f
        val totalW = cardW + (cardW - overlap) * (hand.size - 1).coerceAtLeast(0)
        val scrollable = totalW > maxWidth
        if (scrollable) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(-overlap)) {
                items(hand, key = { it.id }) { c ->
                    PokerCard(c, cardW, raised = c.id in selected, onClick = { onToggle(c.id) })
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(-overlap),
                verticalAlignment = Alignment.Bottom
            ) {
                hand.forEach { c ->
                    PokerCard(c, cardW, raised = c.id in selected, onClick = { onToggle(c.id) })
                }
            }
        }
    }
}

// ================================================================= 工具

private fun bubbleFor(
    bubbles: List<com.laoxiang.ddz.ui.game.ChatBubble>,
    bidBubbles: Map<Int, String>,
    seat: Int
): String? {
    val chat = bubbles.lastOrNull { it.seat == seat }?.text
    val bid = bidBubbles[seat]
    return bid ?: chat
}

private fun nameOf(snap: com.laoxiang.ddz.data.GameSnapshot, seat: Int): String =
    snap.seats.getOrNull(seat)?.name ?: "?"
