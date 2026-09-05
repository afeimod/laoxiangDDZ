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
import com.laoxiang.ddz.data.BtSnapshot
import com.laoxiang.ddz.data.BtSeatView
import com.laoxiang.ddz.data.LastActionType
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.ui.common.AvatarImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 锄大地牌局（横屏浮层布局）：左/顶/右三对手，与跑得快四人局同构。
 */
@Composable
fun BigTwoGameScreen(vm: BigTwoViewModel, onExit: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()
    val snap = snapshot ?: run {
        Box(Modifier.fillMaxSize()) {
            TableBackground(
                bgKey = vm.prefs.tableBg,
                modifier = Modifier.fillMaxSize(),
                engraving = "老乡锄大地"
            )
            Box(
                Modifier.fillMaxSize().background(Color(0xFF16305C).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) { Text("准备开局…", color = Color(0xCCD7E7FA), fontSize = 15.sp) }
        }
        return
    }

    val mySeat by vm.mySeat.collectAsState()
    // 按 mySeat 旋转：左=上家(-1)、右=下家(+1)、顶=对家(+2)
    val me = snap.seats[mySeat.coerceIn(0, 3)]
    val right = snap.seats[(mySeat + 1) % 4]
    val top = snap.seats[(mySeat + 2) % 4]
    val left = snap.seats[(mySeat + 3) % 4]

    val myTurn = snap.phase == Phase.PLAYING && snap.turn == mySeat
    val firstLead = snap.playedRanks.values.all { it == 0 }

    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showCounter by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var myBubble by remember { mutableStateOf<Pair<Long, String>?>(null) }
    LaunchedEffect(myBubble?.first) { if (myBubble != null) { delay(2600); myBubble = null } }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.fx.collect { f ->
            if (f is Fx.Bomb) {
                scope.launch {
                    repeat(7) { i ->
                        shake.snapTo(if (i % 2 == 0) 12f else -12f)
                        delay(45)
                    }
                    shake.snapTo(0f)
                }
            }
        }
    }
    LaunchedEffect(toast?.first) {
        if (toast != null) { delay(2000); toast = null }
    }

    // 联机操作未送达提示（v20）
    LaunchedEffect(Unit) {
        vm.opNotice.collect { t ->
            if (t != null) {
                toast = System.nanoTime() to t
                vm.clearOpNotice()
            }
        }
    }

    // 发牌逐张动画
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
            vm.sfx("deal", 0.8f)
            delay(55)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B3C6E))
            .graphicsLayer { translationX = shake.value.dp.toPx() }
    ) {
        val playedCardW = 40.dp
        TableBackground(
            bgKey = vm.prefs.tableBg,
            modifier = Modifier.fillMaxSize(),
            engraving = "老乡锄大地"
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
                "锄大地",
                color = Color(0xFFEBCB8C), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp)
            )
            PdkToolButton("记牌", active = showCounter) { showCounter = !showCounter }
            PdkToolButton("离桌", danger = true) { onExit() }
        }
        if (showCounter) {
            BtCounterPanel(
                snap,
                mySeat,
                Modifier.align(Alignment.TopCenter).padding(top = 36.dp)
            )
        }

        // 左：上家(3)；右：下家(1)；顶：对家(2)
        BtSeatColumn(left, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 4.dp), !firstLead, playedCardW * 0.8f)
        BtSeatColumn(right, Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 4.dp), !firstLead, playedCardW * 0.8f)
        BtSeatColumn(top, Modifier.align(Alignment.TopCenter).padding(top = 40.dp), !firstLead, playedCardW * 0.7f, horizontal = true)

        // 中央：我的出牌
        Column(
            Modifier.align(Alignment.Center).offset(y = (-16).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                me.lastActionType == LastActionType.PLAYED && me.lastPlayed.isNotEmpty() ->
                    PlayedCards(me.lastPlayed, playedCardW)
                me.lastActionType == LastActionType.PASSED -> PassTag("不出")
                myTurn && snap.lastMove == null && firstLead ->
                    Text(
                        "方块3先出",
                        color = Color(0xFFFFE082), fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
            }
        }

        // 场况提示
        val centerHint = when {
            snap.phase != Phase.PLAYING -> ""
            myTurn && snap.lastMove == null -> if (firstLead) "你持方块3，先出" else "你先出"
            myTurn -> "轮到你，自由出牌"
            else -> "等「${snap.seats.getOrNull(snap.turn)?.name ?: "?"}」出牌…"
        }
        if (centerHint.isNotEmpty()) {
            Text(
                centerHint, color = Color(0xB3FFFFFF), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp)
            )
        }

        // 手牌
        val selCards by vm.selected.collectAsState()
        HandRow(
            hand = me.hand.take(dealtShown),
            selected = selCards,
            onToggle = { vm.toggleSelect(it) },
            onSweep = { vm.selectCards(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 46.dp, end = 46.dp, bottom = GAME_STRIP_H)
        )

        // ---- 操作按钮：手牌正上方居中（不出/提示/出牌保持在中间）
        if (myTurn) {
            val handH = LocalConfiguration.current.screenHeightDp.dp * 0.30f
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = GAME_STRIP_H + handH + 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PdkPillButton("不 出", pdkBluePillBrush(), enabled = snap.lastMove != null) {
                    val err = vm.passTurn()
                    if (err != null) toast = System.nanoTime() to err
                }
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
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp)
        ) {
            StripChatButton { showChat = true }
        }

        // 左下：我的头像（低于纸牌）
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 6.dp),
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
                me.name, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (me.isTurn) Color(0xFFFFE082) else Color.White
            )
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

@Composable
private fun BtSeatColumn(
    seat: BtSeatView,
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
                    seat.name, fontSize = 11.sp,
                    color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
                    maxLines = 1
                )
                Text("剩 ${seat.handCount} 张", fontSize = 10.sp, color = Color(0x99FFFFFF))
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
                color = Color(0xFFD4A24E), fontSize = 12.sp, fontWeight = FontWeight.Black
            )
        }
    }
}

/** 锄大地记牌器（3..2，无王） */
@Composable
private fun BtCounterPanel(snap: BtSnapshot, mySeat: Int, modifier: Modifier = Modifier) {
    val labels = mapOf(
        3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8", 9 to "9", 10 to "10",
        11 to "J", 12 to "Q", 13 to "K", 14 to "A", 15 to "2"
    )
    val mine = snap.seats.first { it.seat == mySeat }.hand.groupBy { it.rank }.mapValues { it.value.size }
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
                val left = 4 - (snap.playedRanks[r] ?: 0) - (mine[r] ?: 0)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        labels[r] ?: "", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (left > 0) Color(0xFFFFE082) else Color(0x55FFE082)
                    )
                    Text(
                        "$left", fontSize = 9.sp,
                        color = if (left > 0) Color.White else Color(0x55FFFFFF)
                    )
                }
            }
        }
    }
}
