package com.laoxiang.ddz.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import com.laoxiang.ddz.ui.common.AvatarImageRes
import com.laoxiang.ddz.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 牌局界面（对标欢乐斗地主）：深蓝桌面即牌桌。
 * App 已锁定横屏，仅保留浮层布局（手牌居中紧凑，两侧留白，支持滑动多选）。
 */
@Composable
fun GameScreen(gameVm: GameViewModel, onExit: () -> Unit) {
    val snapshot by gameVm.snapshot.collectAsState()
    val snap = snapshot ?: run {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF16305C)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = LightGold)
                Spacer(Modifier.height(10.dp))
                Text("等房主开局…", color = Color(0xCCD7E7FA))
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
    // 记牌面板：横屏默认收起（避免开局遮挡桌面中央），点工具条展开
    var showCounter by remember { mutableStateOf(false) }
    var bigText by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val bigTextScale = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
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
    LaunchedEffect(toast?.first) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    // ---------- 发牌动画：新一局手牌逐张翻出，每张同步“啩嗒”声；地主拿底牌时补发增量 ----------
    var animRound by remember { mutableStateOf(-1) }
    var dealtShown by remember { mutableStateOf(0) }
    val handSize = me.hand.size
    LaunchedEffect(snap.round, handSize) {
        if (handSize == 0) return@LaunchedEffect
        if (animRound != snap.round) {
            animRound = snap.round
            dealtShown = 0
            delay(260)                       // 等洗牌音效落定再开始发
        }
        while (dealtShown < handSize) {      // 手牌减少（出牌）时循环自然跳过
            dealtShown++
            gameVm.sfx("deal", 0.8f)          // 一张一响，音画同步
            delay(65)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B3C6E))
    ) {
        val playedCardW = 42.dp

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = shake.value.dp.toPx()
                }
        ) {
            Image(
                painter = painterResource(R.drawable.bg_game_landscape),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            LandscapeTable(
                snap = snap, me = me, next = next, prev = prev,
                counter = counter, showCounter = showCounter,
                bubbles = bubbles, bidBubbles = bidBubbles,
                playedCardW = playedCardW, dealtShown = dealtShown,
                gameVm = gameVm, mySeat = mySeat,
                onToast = { toast = System.nanoTime() to (it ?: "") },
                onToggleCounter = { showCounter = !showCounter },
                onShowChat = { showChat = true },
                onExit = onExit
            )
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
                        .background(Color(0x88123A6E), RoundedCornerShape(14.dp))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
        }

        toast?.let { (_, t) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    t,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = 160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

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

// ================================================================= 横屏（浮层布局）

@Composable
private fun LandscapeTable(
    snap: com.laoxiang.ddz.data.GameSnapshot,
    me: com.laoxiang.ddz.data.SeatView,
    next: com.laoxiang.ddz.data.SeatView,
    prev: com.laoxiang.ddz.data.SeatView,
    counter: Map<Int, Int>,
    showCounter: Boolean,
    bubbles: List<com.laoxiang.ddz.ui.game.ChatBubble>,
    bidBubbles: Map<Int, String>,
    playedCardW: androidx.compose.ui.unit.Dp,
    dealtShown: Int,
    gameVm: GameViewModel,
    mySeat: Int,
    onToast: (String?) -> Unit,
    onToggleCounter: () -> Unit,
    onShowChat: () -> Unit,
    onExit: () -> Unit
) {
    val config = LocalConfiguration.current
    val screenH = config.screenHeightDp.dp
    val handH = screenH * 0.30f          // 手牌区估算高度（用于底部元素偏移）
    val myTurn = snap.phase == Phase.PLAYING && snap.turn == mySeat
    val waitHint = when {
        snap.phase == Phase.BIDDING && snap.bidCursor != mySeat -> "等「${nameOf(snap, snap.bidCursor)}」叫地主…"
        snap.phase == Phase.ROBBING && snap.robCursor != mySeat -> "等「${nameOf(snap, snap.robCursor)}」抢地主…"
        snap.phase == Phase.PLAYING && snap.turn != mySeat -> "等「${nameOf(snap, snap.turn)}」出牌…"
        snap.phase == Phase.PLAYING && snap.turn == mySeat && snap.lastMove == null -> "你先出，随便走"
        snap.phase == Phase.PLAYING -> "轮到你出牌"
        else -> ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // ---- 底层：场地水印
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "老乡斗地主",
                fontSize = 26.sp, fontWeight = FontWeight.Black,
                color = Color(0x12FFFFFF)
            )
            Text("经 典 场", fontSize = 11.sp, color = Color(0x10FFFFFF))
        }

        // ---- 顶部工具条：最上方居中、缩小
        GameToolbar(
            snap, counter, showCounter, onToggleCounter, onShowChat, onExit,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
        )

        // ---- 左上：上家头像 + 其出牌（整体上移）
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OpponentBlock(
                seat = prev,
                showCards = snap.phase == Phase.PLAYING,
                chatText = bubbleFor(bubbles, bidBubbles, prev.seat)
            )
            Spacer(Modifier.height(8.dp))
            SeatPlayArea(seat = prev, phase = snap.phase, cardW = playedCardW * 0.8f)
        }

        // ---- 右上：下家头像 + 其出牌（整体上移）
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 10.dp, top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OpponentBlock(
                seat = next,
                showCards = snap.phase == Phase.PLAYING,
                chatText = bubbleFor(bubbles, bidBubbles, next.seat)
            )
            Spacer(Modifier.height(8.dp))
            SeatPlayArea(seat = next, phase = snap.phase, cardW = playedCardW * 0.8f)
        }

        // ---- 中央：叫抢面板 / 我的出牌
        val centerModifier = Modifier
            .align(Alignment.Center)
            .offset(y = (-14).dp)
        if (snap.phase in setOf(Phase.BIDDING, Phase.ROBBING)) {
            BiddingCenter(
                snap = snap, mySeat = mySeat, gameVm = gameVm, onToast = onToast,
                modifier = centerModifier
            )
        } else {
            Column(
                centerModifier,
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

        // ---- 手牌：居中紧凑（两侧留白），支持点击选牌 + 按住滑动多选（发牌动画期间逐张增补）
        val selCards = gameVm.selected.collectAsState().value
        HandRow(
            hand = me.hand.take(dealtShown),
            selected = selCards,
            onToggle = { gameVm.toggleSelect(it) },
            onSweep = { gameVm.selectCards(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 46.dp, end = 46.dp, bottom = 4.dp)
        )

        // ---- 底部中央：出牌按钮 / 等待提示（浮在手牌上方）
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = handH + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (myTurn) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PillButton(
                        text = "不 出",
                        container = bluePillBrush(),
                        enabled = snap.lastMove != null
                    ) {
                        val err = gameVm.passTurn()
                        if (err != null) onToast(err)
                    }
                    PillButton(text = "提 示", container = bluePillBrush()) {
                        val err = gameVm.hint()
                        if (err != null) onToast(err)
                    }
                    PillButton(text = "出 牌", container = orangePillBrush()) {
                        val err = gameVm.playSelected()
                        if (err != null) onToast(err)
                    }
                }
            } else if (waitHint.isNotEmpty()) {
                Text(waitHint, fontSize = 13.sp, color = Color(0xCCFFFFFF))
            }
        }

        // ---- 左下：我的头像（上移到手牌上方，让手牌左右居中）
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = handH + 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val myBubble = bubbleFor(bubbles, bidBubbles, mySeat)
            if (myBubble != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Bubble(myBubble)
                    Spacer(Modifier.height(4.dp))
                    MyAvatarBadge(me)
                }
            } else {
                MyAvatarBadge(me)
            }
        }
    }
}

/** 我自己的头像徽章（横屏左下角浮层用）：非地主=默认头像，地主=富翁地主头像 */
@Composable
private fun MyAvatarBadge(me: com.laoxiang.ddz.data.SeatView) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            AvatarImageRes(
                tableAvatarRes(me), 46.dp,
                Modifier.border(
                    2.5.dp,
                    when {
                        me.isTurn -> Color(0xFFFFC107)
                        me.isLandlord -> Gold
                        else -> Color(0x66FFFFFF)
                    }, CircleShape
                )
            )
            if (me.isLandlord) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-8).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFD4A24E))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("地主", fontSize = 9.sp, color = Color(0xFF4E2600), fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            me.name,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (me.isTurn) Color(0xFFFFE082) else Color.White,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

// ================================================================= 共用组件

/** 顶部工具条（最上方居中、缩小）：记牌 | 底牌 | 喊话 | 离桌，记牌面板展开时挂在其正下方 */
@Composable
private fun GameToolbar(
    snap: com.laoxiang.ddz.data.GameSnapshot,
    counter: Map<Int, Int>,
    showCounter: Boolean,
    onToggleCounter: () -> Unit,
    onShowChat: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .clip(RoundedCornerShape(15.dp))
                .background(Color(0x52123A6E))
                .border(1.dp, Color(0x3DA8C8F0), RoundedCornerShape(15.dp))
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarButton("记牌", active = showCounter, onClick = onToggleCounter)
            // 底牌展示（缩小版，随工具条居中）
            if (snap.bottomCards.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    snap.bottomCards.forEach { PokerCard(it, 24.dp) }
                    Text(
                        "×${snap.multiplier}",
                        color = Gold, fontWeight = FontWeight.Black, fontSize = 12.sp
                    )
                }
            } else if (snap.bottomHidden) {
                Text(
                    "底牌 ×${snap.multiplier}",
                    color = Color(0xCCD7E7FA), fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
            ToolbarButton("喊话", onClick = onShowChat)
            ToolbarButton("离桌", danger = true, onClick = onExit)
        }
        // 记牌面板：展开时居中挂在工具条下方
        if (showCounter && counter.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            CardCounterPanel(counts = counter)
        }
    }
}

/** 工具条小按钮（缩小版） */
@Composable
private fun ToolbarButton(
    text: String,
    danger: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) Color(0x663A63C0) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                danger -> Color(0xFFFFB3AB)
                active -> Color(0xFFFFD54F)
                else -> Color(0xFFFFE082)
            }
        )
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

/** 叫抢面板（桌面中央，橙=叫 蓝=不叫） */
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CardBack(32.dp); CardBack(32.dp); CardBack(32.dp)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (myBidTurn) {
            Text(
                if (snap.phase == Phase.BIDDING) "你叫不叫地主？" else "抢不抢地主？",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PillButton(
                    text = if (snap.phase == Phase.BIDDING) "不 叫" else "不 抢",
                    container = bluePillBrush()
                ) {
                    val err = gameVm.bid(false)
                    if (err != null) onToast(err)
                }
                PillButton(
                    text = if (snap.phase == Phase.BIDDING) "叫地主" else "抢地主",
                    container = orangePillBrush()
                ) {
                    val err = gameVm.bid(true)
                    if (err != null) onToast(err)
                }
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

private fun bluePillBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFF5B8BE8), Color(0xFF3A63C0)))

private fun orangePillBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFFFFC24D), Color(0xFFF07E1E)))

/** 欢乐斗地主式大胶囊按钮 */
@Composable
private fun PillButton(
    text: String,
    container: Brush,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(25.dp)
    Box(
        modifier
            .shadow(if (enabled) 5.dp else 0.dp, shape)
            .clip(shape)
            .then(
                if (enabled) Modifier.background(container)
                else Modifier.background(Color(0x55FFFFFF))
            )
            .border(1.5.dp, Color(0x80FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Color(0x88FFFFFF),
            fontSize = 17.sp,
            fontWeight = FontWeight.Black
        )
    }
}

/** 出牌操作按钮行（竖屏用） */
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
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (myTurn) {
            PillButton(
                text = "不 出",
                container = bluePillBrush(),
                enabled = snap.lastMove != null
            ) {
                val err = gameVm.passTurn()
                if (err != null) onToast(err)
            }
            Spacer(Modifier.width(20.dp))
            PillButton(text = "提 示", container = bluePillBrush()) {
                val err = gameVm.hint()
                if (err != null) onToast(err)
            }
            Spacer(Modifier.width(20.dp))
            PillButton(text = "出 牌", container = orangePillBrush()) {
                val err = gameVm.playSelected()
                if (err != null) onToast(err)
            }
        } else if (snap.phase == Phase.PLAYING) {
            Spacer(Modifier.height(46.dp))
        }
    }
}

/**
 * 手牌行：居中紧凑（不铺满，两侧留白），高度驱动大卡 + 自适应重叠。
 * 选牌手势 = 单击选/取消 + 按住横向滑动多选（扫过的牌全部加入选中）。
 */
@Composable
private fun HandRow(
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
private data class HandGeo(val n: Int, val startXpx: Float, val stepPx: Float, val cardWpx: Float)

/** x 坐标 → 牌序号；落在牌堆两侧留白区时返回 null（不误选） */
private fun idxAtX(g: HandGeo, x: Float): Int? {
    if (g.n <= 0 || g.stepPx <= 0f) return null
    val rel = x - g.startXpx
    val end = (g.n - 1) * g.stepPx + g.cardWpx
    if (rel < -0.25f * g.cardWpx || rel > end + 0.25f * g.cardWpx) return null
    if (rel <= 0f) return 0
    return (rel / g.stepPx).toInt().coerceIn(0, g.n - 1)
}

// ================================================================= 工具

/** 牌桌头像规则：非地主=默认老乡头像；当地主后自动换成富翁地主头像 */
internal fun tableAvatarRes(seat: com.laoxiang.ddz.data.SeatView): Int =
    if (seat.isLandlord) R.drawable.avatar_landlord else R.drawable.avatar_default

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
