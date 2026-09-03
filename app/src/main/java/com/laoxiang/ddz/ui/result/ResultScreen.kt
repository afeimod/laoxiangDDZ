package com.laoxiang.ddz.ui.result

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.common.GoldButton
import com.laoxiang.ddz.ui.common.OutlineGoldButton
import com.laoxiang.ddz.ui.game.GameMode
import com.laoxiang.ddz.ui.game.GameViewModel
import com.laoxiang.ddz.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 结算页：胜负横幅 + 春天/倍数 + 分数变化 + 金元宝雨
 */
@Composable
fun ResultScreen(
    gameVm: GameViewModel,
    onBackLobby: () -> Unit,
    onAgain: () -> Unit
) {
    val snapshot by gameVm.snapshot.collectAsState()
    val snap = snapshot ?: return
    val result = snap.result ?: return
    val mySeat = gameVm.mySeat.collectAsState().value
    val iAmLandlord = mySeat == snap.landlord
    val iWon = if (iAmLandlord) result.landlordWon else !result.landlordWon

    // 元宝雨
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
        // 金元宝雨（赢了才下）
        if (iWon) {
            Canvas(Modifier.fillMaxSize()) {
                val t = time.value
                confetti.forEach { p ->
                    val y = ((p.delayMs / 1600f) + t * p.speed) % 1.2f
                    val x = p.x + sin(y * 6f + p.x * 10f) * 0.02f
                    val alpha = (1f - y).coerceIn(0f, 1f)
                    drawCircle(
                        color = if (p.gold) Color(0xFFD4A24E).copy(alpha = alpha) else Color(0xFFFFE082).copy(alpha = alpha),
                        radius = p.size,
                        center = Offset(x * size.width, y * size.height)
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 胜负横幅
            val bannerScale = remember { Animatable(0.3f) }
            LaunchedEffect(Unit) {
                bannerScale.animateTo(1f, tween(400))
            }
            Text(
                when {
                    iWon && result.isSpring -> "春 天 ！"
                    iWon && result.isAntiSpring -> "闷 牌 胜 ！"
                    iWon -> "赢 了 ！"
                    else -> "输 了 …"
                },
                fontSize = 46.sp,
                fontWeight = FontWeight.Black,
                color = if (iWon) Color(0xFFFFD54F) else Color(0xFFFF8A80),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .scale(bannerScale.value)
                    .background(Color(0x665D1010), RoundedCornerShape(18.dp))
                    .padding(horizontal = 30.dp, vertical = 8.dp)
            )

            Spacer(Modifier.height(22.dp))

            // 我的信息
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF6FFF3E0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val me = snap.seats.first { it.seat == mySeat }
                    AvatarImage(me.avatar, 56.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${me.name} · ${if (iAmLandlord) "地主" else "农民"}",
                        fontWeight = FontWeight.Bold, color = DeepRed
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ResultStat("本局倍数", "×${result.multiplier}")
                        ResultStat(
                            "欢乐豆",
                            (if (gameVm.lastScore.collectAsState().value >= 0) "+" else "") +
                                    gameVm.lastScore.collectAsState().value
                        )
                        ResultStat("总战绩", "${gameVm.prefs.wins}胜${gameVm.prefs.losses}负")
                    }
                    if (result.isSpring) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "春天！农民一张未出，倍数翻番",
                            fontSize = 12.sp, color = Color(0xFF9C2B1F)
                        )
                    }
                    if (result.isAntiSpring) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "反春闷牌！地主只出一手，倍数翻番",
                            fontSize = 12.sp, color = Color(0xFF9C2B1F)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (gameVm.mode.collectAsState().value == GameMode.CLIENT) {
                OutlineGoldButton("回房间等房主再开") { onAgain() }
            } else {
                GoldButton("再来一局") { onAgain() }
            }
            Spacer(Modifier.height(12.dp))
            OutlineGoldButton("回大厅歇歇") { onBackLobby() }
        }
    }
}

@Composable
private fun ResultStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Black, color = DeepRed)
        Text(label, fontSize = 11.sp, color = Color(0xFF8A6A45))
    }
}

private data class ConfettiPiece(
    val x: Float,
    val delayMs: Int,
    val speed: Float,
    val size: Float,
    val gold: Boolean
)

private fun sin(v: Float): Float = kotlin.math.sin(v)
