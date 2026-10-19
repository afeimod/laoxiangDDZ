package com.laoxiang.ddz.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * 参考 APK（斗地主 5.3.29 / 麻将世界）UI 组件库 v1.6.0
 * —— 主页模式按钮 / 金圈图标钮 / NPC 对话气泡 / 跑马灯 / 浮动动画
 */

/** 参考主页四大模式按钮（挑战/休闲/大师/炸弹 场次卡） */
@Composable
fun RefModeButton(
    title: String,
    subtitle: String,
    gradTop: Color,
    gradBottom: Color,
    floatPhase: Float,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val t = rememberInfiniteTransition(label = "float")
    val f by t.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(periodMsFor(floatPhase), easing = LinearEasing), RepeatMode.Reverse
        ), label = "floatY"
    )
    Box(
        modifier
            .offset(y = (f * 5).dp)
            .shadow(7.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(gradTop, gradBottom)))
            .border(2.5.dp, Color(0xFFFFD97A), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // 顶部高光
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.86f)
                .height(14.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(Color(0x33FFFFFF))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            badge?.invoke()
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                fontSize = 22.sp, fontWeight = FontWeight.Black,
                color = Color.White,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        Color(0xB4000000), Offset(1.5f, 2f), 4f
                    )
                )
            )
            Text(subtitle, fontSize = 11.sp, color = Color(0xCCFFF3C4))
        }
    }
}

private fun periodMsFor(phase: Float): Int = (1100 + phase * 260).toInt()

/** 金圈图标按钮（参考主页 设置/分享/帮助/成就/奖励 五连） */
@Composable
fun RefIconBtn(label: String, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .shadow(4.dp, CircleShape)
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFFC95E), Color(0xFFE8961E))))
            .border(2.dp, Color(0xFFFFF1C4), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = Color(0xFF6B3200), fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.34f).sp
        )
    }
}

/** NPC 对话气泡（参考主页 UI_shouye_dhk：白底金边圆角框 + 左侧名牌） */
@Composable
fun RefNpcBubble(name: String, tip: String, alpha: Float = 1f, modifier: Modifier = Modifier) {
    Row(
        modifier.graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            Modifier
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF7FFFDF4))
                .border(2.dp, Color(0xFFE8B34B), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Column {
                Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9C5ACD))
                Spacer(Modifier.height(2.dp))
                Text(tip, fontSize = 13.sp, color = Color(0xFF595959), maxLines = 2)
            }
        }
        // 气泡尾巴
        Canvas(Modifier.size(16.dp, 14.dp).offset(x = (-1).dp)) {
            val p = Path().apply {
                moveTo(0f, 4f); lineTo(size.width, 0f); lineTo(size.width * 0.55f, size.height); close()
            }
            drawPath(p, Color(0xFFF7FFFDF4))
        }
    }
}

/** 跑马灯公告（参考大厅 顶部 Notice 滚动条） */
@Composable
fun RefMarquee(text: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "mq")
    val x by t.animateFloat(
        initialValue = 1f, targetValue = -1.6f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing)),
        label = "mqX"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x66000000))
            .border(1.dp, Color(0x59FFD9A0), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp, color = Color(0xFFFFE9B8), maxLines = 1,
            modifier = Modifier.graphicsLayer { translationX = x * 420f }
        )
    }
}

/** 简笔地主小人（主页 NPC 占位：斗笠+笑脸，Canvas 手绘避免加密素材缺口） */
@Composable
fun RefNpcFigure(size: Dp = 120.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "npc")
    val bob by t.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob"
    )
    Canvas(modifier.size(size).offset(y = (bob * 3).dp)) {
        val s = size.width
        // 身体（红袍）
        drawRoundRect(
            Color(0xFFD84343),
            Offset(s * 0.28f, s * 0.52f),
            androidx.compose.ui.geometry.Size(s * 0.44f, s * 0.42f),
            CornerRadius(s * 0.12f, s * 0.12f)
        )
        // 头
        drawCircle(Color(0xFFFFE3C2), s * 0.19f, Offset(s * 0.5f, s * 0.34f))
        // 斗笠
        val hat = Path().apply {
            moveTo(s * 0.26f, s * 0.245f)
            lineTo(s * 0.74f, s * 0.245f)
            lineTo(s * 0.5f, s * 0.10f)
            close()
        }
        drawPath(hat, Color(0xFFE0A84E))
        drawRect(Color(0xFFC08A32), Offset(s * 0.245f, s * 0.235f), androidx.compose.ui.geometry.Size(s * 0.51f, s * 0.03f))
        // 眼睛+笑
        drawCircle(Color(0xFF333333), s * 0.022f, Offset(s * 0.44f, s * 0.335f))
        drawCircle(Color(0xFF333333), s * 0.022f, Offset(s * 0.56f, s * 0.335f))
        drawArc(
            Color(0xFFB4574E), startAngle = 15f, sweepAngle = 150f, useCenter = false,
            topLeft = Offset(s * 0.435f, s * 0.345f),
            size = androidx.compose.ui.geometry.Size(s * 0.13f, s * 0.08f),
            style = Stroke(s * 0.018f)
        )
    }
}

/** 中央特效字（金色渐变 + 描边，参考胡牌文字 天和/地和/杠上炮…） */
@Composable
fun RefFxText(text: String, sizeSp: Int = 44, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x88000000))
            .border(1.5.dp, Color(0xFFFFD97A), RoundedCornerShape(16.dp))
            .padding(horizontal = 26.dp, vertical = 10.dp)
    ) {
        Text(
            text, fontSize = sizeSp.sp, fontWeight = FontWeight.Black,
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.verticalGradient(listOf(Color(0xFFFFF3B0), Color(0xFFFFAE33))),
                shadow = androidx.compose.ui.graphics.Shadow(Color(0xFF7A2E00), Offset(2f, 2.5f), 3f)
            ),
            textAlign = TextAlign.Center
        )
    }
}

/** 五角星路径（主页炸弹场装饰） */
internal fun starPath(cx: Float, cy: Float, r: Float): Path {
    val p = Path()
    for (i in 0 until 10) {
        val ang = Math.toRadians((-90.0 + i * 36.0))
        val rad = if (i % 2 == 0) r else r * 0.42f
        val px = cx + (rad * cos(ang)).toFloat()
        val py = cy + (rad * sin(ang)).toFloat()
        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
    }
    p.close()
    return p
}
