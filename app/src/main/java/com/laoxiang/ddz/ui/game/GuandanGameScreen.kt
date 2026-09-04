package com.laoxiang.ddz.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.data.CardSuit
import com.laoxiang.ddz.data.GdArrange
import com.laoxiang.ddz.data.GdSnapshot
import com.laoxiang.ddz.data.GdSeatView
import com.laoxiang.ddz.data.GdTeam
import com.laoxiang.ddz.data.LastActionType
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.data.gdTeamOf
import com.laoxiang.ddz.ui.common.AvatarImage
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 底部操作条高度（所有游戏统一：手牌上移，喊话/按钮放条内=低于纸牌） */
internal val GAME_STRIP_H = 52.dp

/**
 * 掼蛋牌局（横屏）：左/顶/右三对手（顶=对家同队），中央级牌与队伍等级。
 * 手牌 27 张自动两排 + 「一键理」智能列堆视图（炸弹/同花顺/顺子…自动识别）。
 */
@Composable
fun GuandanGameScreen(vm: GuandanViewModel, onExit: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()
    val snap = snapshot ?: run {
        Box(Modifier.fillMaxSize()) {
            TableBackground(
                bgKey = vm.prefs.tableBg,
                modifier = Modifier.fillMaxSize(),
                engraving = "老乡掼蛋"
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

    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val shake = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 快捷喊话
    var showChat by remember { mutableStateOf(false) }
    var myBubble by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var smartArrange by remember { mutableStateOf(true) }
    LaunchedEffect(myBubble?.first) { if (myBubble != null) { delay(2600); myBubble = null } }

    LaunchedEffect(Unit) {
        vm.fx.collect { f ->
            if (f is Fx.Bomb) {
                scope.launch {
                    flash.snapTo(0.4f)
                    flash.animateTo(0f, tween(650))
                }
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

    // 发牌逐张动画（两副牌混发：显示顺序即混排后的手牌序）
    var animRound by remember { mutableStateOf(-1) }
    var dealtShown by remember { mutableStateOf(0) }
    val handSize = me.hand.size
    val revealing = dealtShown < handSize
    LaunchedEffect(snap.handNo, handSize) {
        if (handSize == 0) return@LaunchedEffect
        if (animRound != snap.handNo) {
            animRound = snap.handNo
            dealtShown = 0
            delay(260)
        }
        while (dealtShown < handSize) {
            dealtShown++
            vm.sfx("deal", 0.8f)
            delay(46)
        }
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
            engraving = "老乡掼蛋"
        )

        // 顶部工具条 + 级牌信息
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
                "掼蛋 · 打${rankLabel(snap.levelRank)}",
                color = Color(0xFFEBCB8C), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp)
            )
            Text(
                "我方 ${snap.teamLevels[gdTeamOf(0)]} 级 · 对方 ${snap.teamLevels[gdTeamOf(1)]} 级",
                color = Color(0xCCD7E7FA), fontSize = 11.sp,
                modifier = Modifier.padding(end = 6.dp)
            )
            PdkToolButton("离桌", danger = true) { onExit() }
        }

        // 左右上下对手
        GdSeatColumn(left, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 4.dp), playedCardW * 0.8f)
        GdSeatColumn(right, Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 4.dp), playedCardW * 0.8f)
        GdSeatColumn(top, Modifier.align(Alignment.TopCenter).padding(top = 42.dp), playedCardW * 0.7f)

        // 中央：我的出牌
        Column(
            Modifier.align(Alignment.Center).offset(y = (-20).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                me.lastActionType == LastActionType.PLAYED && me.lastPlayed.isNotEmpty() ->
                    PlayedCards(me.lastPlayed, playedCardW)
                me.lastActionType == LastActionType.PASSED -> PassTag("不出")
            }
        }

        // 场况提示
        val centerHint = when {
            snap.phase != Phase.PLAYING -> ""
            myTurn && snap.lastMove == null -> "你先出"
            myTurn -> "轮到你"
            else -> "等「${snap.seats.getOrNull(snap.turn)?.name ?: "?"}」出牌…"
        }
        if (centerHint.isNotEmpty()) {
            Text(
                centerHint, color = Color(0xB3FFFFFF), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
            )
        }

        // ---- 手牌（27 张：发牌动画逐张 → 结束后自动整理；贴底不再被底边剪切，可上下拖动）
        val selCards by vm.selected.collectAsState()
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val handAvailH = screenH - 118.dp          // 顶部工具条+对手区预留，其余全给手牌
        var handBlockH by remember { mutableStateOf(150.dp) }
        val badges = remember(me.hand, snap.levelRank) {
            me.hand.filter { it.rank == snap.levelRank && it.suit != CardSuit.JOKER }
                .associate {
                    it.id to if (com.laoxiang.ddz.data.GdMove.isFengrenpei(it, snap.levelRank)) "配" else "级"
                }
        }

        if (revealing) {
            HandRow(
                hand = me.hand.take(dealtShown),
                selected = selCards,
                onToggle = { vm.toggleSelect(it) },
                onSweep = { vm.selectCards(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 40.dp, end = 40.dp, bottom = GAME_STRIP_H)
            )
        } else {
            if (smartArrange) {
                val combos = remember(me.hand, snap.levelRank) {
                    GdArrange.arrange(me.hand, snap.levelRank)
                }
                GdSmartHand(
                    combos = combos,
                    levelRank = snap.levelRank,
                    selected = selCards,
                    onSelectGroup = { vm.toggleGroup(it) },
                    availH = handAvailH,
                    onHeight = { handBlockH = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                )
            } else {
                HandRows(
                    hand = me.hand,
                    selected = selCards,
                    onToggle = { vm.toggleSelect(it) },
                    onSweep = { vm.selectCards(it) },
                    badges = badges,
                    availH = handAvailH,
                    onHeight = { handBlockH = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                )
            }
        }

        // ---- 操作按钮：手牌正上方居中（不出/提示/出牌保持在中间）
        if (myTurn && snap.phase == Phase.PLAYING && !revealing) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = handBlockH + 10.dp),
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
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StripChatButton { showChat = true }
        }

        // 理牌切换（右下、手牌上方）
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = handBlockH + 12.dp)
        ) {
            SmartToggleChip(smartArrange) { smartArrange = !smartArrange }
        }

        // 左下：我的头像 + 队标（低于纸牌）
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
                    if (me.isTurn) Color(0xFFFFC107) else Color(0xFF6EC6FF),
                    CircleShape
                )
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    me.name, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (me.isTurn) Color(0xFFFFE082) else Color.White
                )
                Text("蓝队 · 对家并肩", fontSize = 9.sp, color = Color(0x99FFFFFF))
            }
            // 我喊话的气泡：显示在头像上方
            myBubble?.let { (_, t) ->
                Spacer(Modifier.width(8.dp))
                Bubble(t)
            }
        }

        // 快捷喊话面板
        AnimatedVisibility(
            visible = showChat,
            enter = fadeIn(),
            exit = fadeOut(),
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
                        "头游 ${snap.seats[r.headSeat].name} · 二游 ${snap.seats[r.secondSeat].name}",
                        fontSize = 13.sp, color = Color(0xFF5D4037)
                    )
                    Text(
                        if (r.finalWin) "对方打 A 成功，终局！"
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

        // 特效层
        if (flash.value > 0.01f) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0xFFE53935).copy(alpha = flash.value * 0.55f))
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

/** 智能理牌开关（默认开） */
@Composable
private fun SmartToggleChip(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (on) Color(0xE6FFD54F) else Color(0x66000000))
            .border(1.dp, Color(0x88C9A25E), RoundedCornerShape(11.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            if (on) "⚡ 一键理" else "散 排",
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (on) Color(0xFF5D3A00) else Color(0xCCFFFFFF)
        )
    }
}

/** 底部条喊话按钮（所有游戏统一：右下、低于纸牌） */
@Composable
internal fun StripChatButton(onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF5C6BC0), Color(0xFF3949AB))))
            .border(1.dp, Color(0x559FA8DA), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp)
    ) {
        Text("喊话", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/**
 * 掼蛋智能理牌视图：组合列堆（同组牌纵向叠放 + 列底标签），对标主流掼蛋「一键理」。
 * 点击整组 = 全选/取消该组；组内所有牌全部展开露角（6 张显 6 张、5 张显 5 张，不再截成 4 张）；
 * 行内堆叠步长按该行最大组张数自适应压缩，整体超出可用高度时自动缩小列堆尺寸；
 * 支持上下拖动查看桌面；[onHeight] 回调实际内容高度（供界面定位按钮）。
 */
@Composable
private fun GdSmartHand(
    combos: List<GdArrange.GdCombo>,
    levelRank: Int,
    selected: Set<Int>,
    onSelectGroup: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    availH: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    onHeight: (androidx.compose.ui.unit.Dp) -> Unit = {}
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val colGap = 5.dp
        val groupGap = 9.dp
        val labelH = 15.dp
        val rowGap = 4.dp
        val minStep = 9.dp          // 堆叠最小步长：保证每张牌都露出可辨认的牌角

        // 自适应缩放：从大到小找第一组能放进 availH 的列堆尺寸（组内全张数展开计入行高）
        data class Metric(val cardW: androidx.compose.ui.unit.Dp, val step: androidx.compose.ui.unit.Dp)
        val candidates = listOf(
            Metric(46.dp, 13.dp), Metric(42.dp, 12.dp), Metric(38.dp, 11.dp),
            Metric(34.dp, 10.dp), Metric(30.dp, 9.dp)
        )
        val avail = if (availH == androidx.compose.ui.unit.Dp.Unspecified) 240.dp else availH

        fun perRowOf(m: Metric): Int =
            ((maxWidth - 4.dp) / (m.cardW + colGap)).toInt().coerceAtLeast(4)

        // 各行步长 = min(档位步长, (行预算-卡高)/(该行最大张数-1))，下限 minStep → 每张必露角
        fun rowSteps(m: Metric): List<androidx.compose.ui.unit.Dp> {
            val rows = combos.chunked(perRowOf(m))
            if (rows.isEmpty()) return emptyList()
            val cardH = m.cardW / CARD_RATIO
            val fair = (avail - rowGap * (rows.size - 1) - labelH - 6.dp) / rows.size
            return rows.map { r ->
                val maxN = r.maxOf { it.cards.size }
                if (maxN <= 1) m.step
                else ((fair - cardH) / (maxN - 1)).coerceIn(minStep, m.step)
            }
        }

        fun contentHOf(m: Metric, steps: List<androidx.compose.ui.unit.Dp>): androidx.compose.ui.unit.Dp {
            val rows = combos.chunked(perRowOf(m))
            if (rows.isEmpty()) return 0.dp
            val cardH = m.cardW / CARD_RATIO
            val hs = rows.mapIndexed { i, r ->
                val maxN = r.maxOf { it.cards.size }
                cardH + steps[i] * (maxN - 1) + labelH + 6.dp
            }
            return hs.fold(0.dp) { a, b -> a + b } + rowGap * (rows.size - 1)
        }

        val chosen = candidates.firstOrNull { m ->
            contentHOf(m, rowSteps(m)) <= avail
        } ?: candidates.last()
        val cardW = chosen.cardW
        val perRow = perRowOf(chosen)
        val steps = rowSteps(chosen)
        val rows = combos.chunked(perRow)
        val contentH = contentHOf(chosen, steps)

        // 拖动牌面：默认贴底，可上提查看桌面
        var panPx by remember { mutableStateOf(0f) }
        LaunchedEffect(combos) { panPx = 0f }
        val density = LocalDensity.current
        val maxUpPx = with(density) { (contentH - 40.dp).coerceAtLeast(0.dp).toPx() }
        SideEffect { onHeight(contentH) }

        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, panPx.roundToInt()) }
                .pointerInput(maxUpPx) {
                    detectVerticalDragGestures { change, amount ->
                        panPx = (panPx + amount).coerceIn(-maxUpPx, 0f)
                        change.consume()
                    }
                },
            verticalArrangement = Arrangement.spacedBy(rowGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            rows.forEachIndexed { ri, rowCombos ->
                val rowStep = steps[ri]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(colGap),
                    verticalAlignment = Alignment.Bottom
                ) {
                    rowCombos.forEachIndexed { ci, combo ->
                        val allIds = combo.cards.map { it.id }
                        val groupSelected = allIds.isNotEmpty() && allIds.all { it in selected }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(cardW)
                        ) {
                            // 组堆高度含全部张数（6 张显 6 张）；不裁剪，选中抬牌不切边
                            Box(
                                Modifier
                                    .height(cardW / CARD_RATIO + rowStep * (combo.cards.size - 1))
                                    .clickable { onSelectGroup(allIds) }
                            ) {
                                combo.cards.forEachIndexed { i, c ->
                                    Box(
                                        Modifier
                                            .offset(x = 0.dp, y = rowStep * i)
                                            .width(cardW)
                                    ) {
                                        PokerCard(
                                            c, cardW,
                                            raised = c.id in selected || groupSelected,
                                            badge = if (c.rank == levelRank && c.suit != CardSuit.JOKER) "级" else null,
                                            onClick = null
                                        )
                                    }
                                }
                            }
                            if (combo.label.isNotEmpty()) {
                                Box(
                                    Modifier
                                        .height(labelH)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when {
                                                combo.label in listOf("天王炸", "炸弹") -> Color(0xE6E53935)
                                                combo.label == "同花顺" -> Color(0xE600897B)
                                                combo.label in listOf("钢板", "木板", "顺子") -> Color(0xE61E88E5)
                                                else -> Color(0xCC607D8B)
                                            }
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        // 炸弹张数不定（4~8），超过 4 张时标注 ×N（如 炸弹×6）
                                        combo.label + if (combo.cards.size > 4) "×${combo.cards.size}" else "",
                                        fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Spacer(Modifier.height(labelH))
                            }
                        }
                        if (combo.major && ci != rowCombos.lastIndex) {
                            Spacer(Modifier.width(groupGap))
                        }
                    }
                }
            }
        }
    }
}

private fun rankLabel(rank: Int): String = when (rank) {
    11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; else -> "$rank"
}

@Composable
private fun GdSeatColumn(
    seat: GdSeatView,
    modifier: Modifier = Modifier,
    cardW: androidx.compose.ui.unit.Dp
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        seat.name, fontSize = 11.sp,
                        color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (seat.team == GdTeam.A) Color(0x666EC6FF) else Color(0x66FFAB91)
                            )
                            .padding(horizontal = 4.dp, vertical = 0.5.dp)
                    ) {
                        Text(
                            if (seat.team == GdTeam.A) "蓝队" else "橙队",
                            fontSize = 8.sp, color = Color.White
                        )
                    }
                }
                Text("剩 ${seat.handCount} 张", fontSize = 10.sp, color = Color(0x99FFFFFF))
            }
        }
        if (seat.lastActionType == LastActionType.PLAYED && seat.lastPlayed.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            PlayedCards(seat.lastPlayed, cardW)
        }
        if (seat.lastActionType == LastActionType.PASSED) {
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
