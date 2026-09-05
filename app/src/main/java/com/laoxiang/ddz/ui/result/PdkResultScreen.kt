package com.laoxiang.ddz.ui.result

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.common.GoldButton
import com.laoxiang.ddz.ui.common.OutlineGoldButton
import com.laoxiang.ddz.ui.game.PdkViewModel
import com.laoxiang.ddz.ui.theme.Gold
import kotlin.random.Random

/**
 * 跑得快结算页：胜负横幅 + 各家剩牌与得分 + 金豆变化 + 元宝雨（赢了）。
 */
@Composable
fun PdkResultScreen(
    pdkVm: PdkViewModel,
    onBackLobby: () -> Unit,
    onAgain: () -> Unit
) {
    val snap = pdkVm.snapshot.collectAsState().value ?: return
    val result = snap.result ?: return
    val mySeat = pdkVm.mySeat.collectAsState().value
    val myDelta = result.scoreDelta[mySeat] ?: 0
    val iWon = myDelta > 0

    val confetti = remember {
        List(26) {
            ConfettiPiece(
                x = Random.nextFloat(),
                delayMs = Random.nextInt(1600),
                speed = 0.55f + Random.nextFloat() * 0.8f,
                size = (5 + Random.nextInt(8)).toFloat(),
                gold = Random.nextFloat() < 0.7f
            )
        }
    }
    val time = remember { Animatable(if (iWon) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (iWon) time.animateTo(1.6f, tween(8000))
    }

    Box(Modifier.fillMaxSize().background(Color(0xE6310505))) {
        if (iWon) {
            Canvas(Modifier.fillMaxSize()) {
                val t = time.value
                confetti.forEach { p ->
                    val y = ((p.delayMs / 1600f) + t * p.speed) % 1.2f
                    val x = p.x + kotlin.math.sin(y * 6f + p.x * 10f) * 0.02f
                    val alpha = (1f - y).coerceIn(0f, 1f)
                    drawCircle(
                        color = if (p.gold) Color(0xFFD4A24E).copy(alpha = alpha)
                        else Color(0xFFFFE082).copy(alpha = alpha),
                        radius = p.size,
                        center = Offset(x * size.width, y * size.height)
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 46.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (iWon) "赢 了 ！" else "输 了 …",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    color = if (iWon) Color(0xFFFFD54F) else Color(0xFFFF8A80),
                    textAlign = TextAlign.Center
                )
                Text(
                    "${snap.mode.label} · 炸弹倍数 ×${result.multiplier}",
                    fontSize = 12.sp,
                    color = Color(0x99FFE0B2)
                )
                Spacer(Modifier.height(14.dp))

                // 各家结算卡
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x3D000000))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    snap.seats.sortedBy { s ->
                        if (s.seat == result.winnerSeat) 0 else 1
                    }.forEach { s ->
                        val delta = result.scoreDelta[s.seat] ?: 0
                        Row(
                            Modifier.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarImage(s.avatar, 34.dp)
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text(
                                    s.name + if (s.seat == result.winnerSeat) "  ·  第一名" else "",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (s.seat == result.winnerSeat) Gold
                                    else Color(0xE6FFFFFF)
                                )
                                Text(
                                    if (s.seat == result.winnerSeat) "先跑为赢"
                                    else "剩 ${result.remain[s.seat] ?: 0} 张",
                                    fontSize = 10.sp,
                                    color = Color(0x99FFE0B2)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (delta > 0) "+$delta 豆" else "$delta 豆",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = if (delta > 0) Color(0xFFFFD54F) else Color(0xFFFF8A80)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlineGoldButton(
                        "返回大厅",
                        modifier = Modifier.width(150.dp)
                    ) { onBackLobby() }
                    GoldButton(
                        "再来一局",
                        modifier = Modifier.width(150.dp)
                    ) { onAgain() }
                }
            }
        }
    }
}
