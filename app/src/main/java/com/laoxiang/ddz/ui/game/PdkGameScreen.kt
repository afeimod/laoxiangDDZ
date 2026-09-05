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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.data.LastActionType
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.data.PdkSnapshot
import com.laoxiang.ddz.data.PdkSeatView
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.common.AvatarImageRes
import com.laoxiang.ddz.ui.theme.Gold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 跑得快牌局（横屏浮层布局）：三人=左右两对手；四人=左/顶/右三对手。
 * 复用牌桌背景、手牌行（点击+滑动多选）、出牌展示组件。
 */
@Composable
fun PdkGameScreen(pdkVm: PdkViewModel, onExit: () -> Unit) {
    val snapshot by pdkVm.snapshot.collectAsState()
    val snap = snapshot ?: run {
        Box(Modifier.fillMaxSize()) {
            TableBackground(
                bgKey = pdkVm.prefs.tableBg,
                modifier = Modifier.fillMaxSize(),
                engraving = "老乡跑得快"
            )
            Box(
                Modifier.fillMaxSize().background(Color(0xFF16305C).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text("准备开局…", color = Color(0xCCD7E7FA), fontSize = 15.sp)
            }
        }
        return
    }

    val mySeat by pdkVm.mySeat.collectAsState()
    val n = snap.seats.size
    // 按 mySeat 旋转：座 0=我、右=下家(+1)、左=上家(-1)、四人局顶部=对家(+2)
    val me = snap.seats[mySeat.coerceIn(0, n - 1)]
    val right = snap.seats[(mySeat + 1) % n]
    val left = snap.seats[(mySeat + n - 1) % n]
    val top = if (n == 4) snap.seats[(mySeat + 2) % n] else null

    val myTurn = snap.phase == Phase.PLAYING && snap.turn == mySeat
    val firstLead = snap.playedRanks.values.all { it == 0 }

    // ---------- 状态
    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showCounter by remember { mutableStateOf(false) }
    var bigText by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var myBubble by remember { mutableStateOf<Pair<Long, String>?>(null) }
    LaunchedEffect(myBubble?.first) { if (myBubble != null) { delay(2600); myBubble = null } }
    val flash = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val bigTextScale = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val fxFlow = pdkVm.fx
    LaunchedEffect(Unit) {
        fxFlow.collect { f ->
            when (f) {
                is Fx.Bomb -> {
                    bigText = "炸 弹 ！"
                    scope.launch {
                        flash.snapTo(0.45f)
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
                            shake.snapTo(if (i % 2 == 0) 14f else -14f)
                            delay(45)
                        }
                        shake.snapTo(0f)
                    }
                }
                else -> {}
            }
        }
    }
    LaunchedEffect(toast?.first) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    // 联机操作未送达提示（v20）
    LaunchedEffect(Unit) {
        pdkVm.opNotice.collect { t ->
            if (t != null) {
                toast = System.nanoTime() to t
                pdkVm.clearOpNotice()
            }
        }
    }

    // ---------- 发牌动画（逐张翻出+音效，斗地主同款）
    var animRound by remember { mutableStateOf(-1) }
    var dealtShown by remember { mutableStateOf(0) }
    val handSize = me.hand.size
    LaunchedEffect(snap.round, handSize) {
        if (handSize == 0) return@LaunchedEffect
        if (animRound != snap.round) {
            animRound = snap.round
            dealtShown = 0
            delay(260)
        }
        while (dealtShown < handSize) {
            dealtShown++
            pdkVm.sfx("deal", 0.8f)
            delay(60)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B3C6E))
            .graphicsLayer { translationX = shake.value.dp.toPx() }
    ) {
        val playedCardW = 40.dp
        val counter = remember(snap.playedRanks, me.hand) {
            val played = snap.playedRanks
            val mine = me.hand.groupBy { it.rank }.mapValues { it.value.size }
            (3..15).associateWith { r -> 4 - (played[r] ?: 0) - (mine[r] ?: 0) }
        }

        TableBackground(
            bgKey = pdkVm.prefs.tableBg,
            modifier = Modifier.fillMaxSize(),
            engraving = "老乡跑得快"
        )

        // ---- 顶部工具条
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
                snap.mode.label,
                color = Color(0xFFEBCB8C),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp)
            )
            PdkToolButton("记牌", active = showCounter) { showCounter = !showCounter }
            PdkToolButton("离桌", danger = true) { onExit() }
        }
        if (showCounter) {
            PdkCounterPanel(
                counter,
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 36.dp)
            )
        }

        // ---- 左：上家
        PdkSeatColumn(
            seat = left,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 4.dp),
            showCards = !firstLead,
            cardW = playedCardW * 0.8f
        )

        // ---- 右：下家
        PdkSeatColumn(
            seat = right,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 10.dp, top = 4.dp),
            showCards = !firstLead,
            cardW = playedCardW * 0.8f
        )

        // ---- 顶：对家（四人局）
        if (top != null) {
            PdkSeatColumn(
                seat = top,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp),
                showCards = !firstLead,
                cardW = playedCardW * 0.7f,
                horizontal = true
            )
        }

        // ---- 中央：我的出牌 / 过牌标 / 等待提示
        Column(
            Modifier
                .align(Alignment.Center)
                .offset(y = (-16).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                me.lastActionType == LastActionType.PLAYED && me.lastPlayed.isNotEmpty() ->
                    PlayedCards(me.lastPlayed, playedCardW)
                me.lastActionType == LastActionType.PASSED -> PassTag("要不起")
                myTurn && snap.lastMove == null && firstLead ->
                    Text(
                        "黑桃3先出",
                        color = Color(0xFFFFE082),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
            }
        }

        // ---- 中央上方：场况提示
        val centerHint = when {
            snap.phase != Phase.PLAYING -> ""
            myTurn && snap.lastMove == null -> if (firstLead) "你持黑桃3，先出" else "你先出"
            myTurn -> "轮到你，压得住必须压"
            else -> "等「${snap.seats.getOrNull(snap.turn)?.name ?: "?"}」出牌…"
        }
        if (centerHint.isNotEmpty()) {
            Text(
                centerHint,
                color = Color(0xB3FFFFFF),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 78.dp)
            )
        }

        // ---- 手牌
        val selCards by pdkVm.selected.collectAsState()
        HandRow(
            hand = me.hand.take(dealtShown),
            selected = selCards,
            onToggle = { pdkVm.toggleSelect(it) },
            onSweep = { pdkVm.selectCards(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 46.dp, end = 46.dp, bottom = GAME_STRIP_H)
        )

        // ---- 操作按钮：手牌正上方居中（不出/提示/出牌保持在中间）
        if (myTurn) {
            val handH = LocalConfiguration.current.screenHeightDp.dp * 0.30f
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = GAME_STRIP_H + handH + 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PdkPillButton("不 出", pdkBluePillBrush(), enabled = pdkVm.canPass()) {
                    val err = pdkVm.passTurn()
                    if (err != null) toast = System.nanoTime() to err
                }
                PdkPillButton("提 示", pdkBluePillBrush()) {
                    val err = pdkVm.hint()
                    if (err != null) toast = System.nanoTime() to err
                }
                PdkPillButton("出 牌", pdkOrangePillBrush()) {
                    val err = pdkVm.playSelected()
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

        // ---- 左下：我的头像（用玩家自选形象，低于纸牌）
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
            Text(
                me.name,
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (me.isTurn) Color(0xFFFFE082) else Color.White
            )
            myBubble?.let { (_, t) ->
                Spacer(Modifier.width(8.dp))
                Bubble(t)
            }
        }

        // ---- 快捷喊话面板
        androidx.compose.animation.AnimatedVisibility(
            visible = showChat,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            QuickChatPanel(
                onSend = { p ->
                    pdkVm.localChat(p)
                    myBubble = System.nanoTime() to p
                },
                onDismiss = { showChat = false }
            )
        }

        // ---- 特效层
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
    }
}

// ================================================================= 组件

/** 对手列：头像+名+剩牌数+其刚出的牌 */
@Composable
private fun PdkSeatColumn(
    seat: PdkSeatView,
    modifier: Modifier = Modifier,
    showCards: Boolean,
    cardW: androidx.compose.ui.unit.Dp,
    horizontal: Boolean = false
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                seat.avatar, 50.dp,
                Modifier.border(
                    2.5.dp,
                    if (seat.isTurn) Color(0xFFFFC107) else Color(0x66FFFFFF),
                    CircleShape
                )
            )
            Spacer(Modifier.width(7.dp))
            Column {
                Text(
                    seat.name,
                    fontSize = 11.sp,
                    color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
                    maxLines = 1
                )
                Text(
                    "剩 ${seat.handCount} 张",
                    fontSize = 10.sp,
                    color = Color(0x99FFFFFF)
                )
            }
        }
        if (showCards && seat.lastActionType == LastActionType.PLAYED && seat.lastPlayed.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            PlayedCards(seat.lastPlayed, cardW)
        }
        if (showCards && seat.lastActionType == LastActionType.PASSED) {
            Spacer(Modifier.height(6.dp))
            PassTag("不出")
        }
        if (seat.finishedRank > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "第 ${seat.finishedRank} 名！",
                color = Gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

/** 跑得快记牌器（3..A/2，无王） */
@Composable
private fun PdkCounterPanel(counts: Map<Int, Int>, modifier: Modifier = Modifier) {
    val labels = mapOf(
        3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8", 9 to "9", 10 to "10",
        11 to "J", 12 to "Q", 13 to "K", 14 to "A", 15 to "2"
    )
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB3163A6E))
            .border(1.dp, Color(0x66A8C8F0), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Text("记牌器（外界剩余）", fontSize = 10.sp, color = Color(0xCCD7E7FA))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (3..15).forEach { r ->
                val left = counts[r] ?: 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        labels[r] ?: "",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (left > 0) Color(0xFFFFE082) else Color(0x55FFE082)
                    )
                    Text(
                        "$left",
                        fontSize = 9.sp,
                        color = if (left > 0) Color.White else Color(0x55FFFFFF)
                    )
                }
            }
        }
    }
}

@Composable
internal fun PdkToolButton(
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

@Composable
internal fun PdkPillButton(
    text: String,
    container: Brush,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(25.dp)
    Box(
        Modifier
            .shadow(if (enabled) 5.dp else 0.dp, shape)
            .clip(shape)
            .then(
                if (enabled) Modifier.background(container)
                else Modifier.background(Color(0x55FFFFFF))
            )
            .border(1.5.dp, Color(0x80FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Color(0x88FFFFFF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Black
        )
    }
}

internal fun pdkBluePillBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFF5B8BE8), Color(0xFF3A63C0)))

internal fun pdkOrangePillBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFFFFC24D), Color(0xFFF07E1E)))
