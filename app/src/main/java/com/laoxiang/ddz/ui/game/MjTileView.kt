package com.laoxiang.ddz.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.data.MjSuit
import com.laoxiang.ddz.data.MjTile
import kotlin.math.min

/**
 * 麻将牌渲染：绿背白面 + 经典配色（参照实体麻将图：蓝字红"萬"、筒/条青红蓝纹、
 * 東南西北蓝 / 中红 / 發绿 / 白板蓝框）。全部 Canvas 绘制，零图片资源。
 * [w] 为牌宽，高 = w × 1.32。
 */
private val FACE_BLUE = Color(0xFF1A3B8F)
private val FACE_RED = Color(0xFFC62828)
private val FACE_GREEN = Color(0xFF1E8F3E)
private val BACK_TOP = Color(0xFF35B36B)
private val BACK_BOTTOM = Color(0xFF0E7A3D)
private val FACE_WHITE = Color(0xFFFFFDF6)

/** 单张麻将牌（tile=null 或 faceUp=false 画绿背） */
@Composable
fun MjTileView(
    tile: MjTile?,
    w: Dp,
    modifier: Modifier = Modifier,
    faceUp: Boolean = true,
    selected: Boolean = false,
    raised: Boolean = false,
    laiziMark: Boolean = false,
    alpha: Float = 1f
) {
    val h = w * 1.32f
    val measurer = rememberTextMeasurer()
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .offset(y = if (raised || selected) (-10).dp else 0.dp)
            .shadow(if (raised || selected) 6.dp else 3.dp, RoundedCornerShape(w / 7))
            .clip(RoundedCornerShape(w / 7))
            .size(w, h)
            .border(0.5.dp, Color(0x33000000), RoundedCornerShape(w / 7))
    ) {
        Canvas(Modifier.size(w, h)) {
            drawTileBody(measurer, tile, faceUp, laiziMark)
        }
    }
}

private fun DrawScope.drawTileBody(
    measurer: androidx.compose.ui.text.TextMeasurer,
    tile: MjTile?,
    faceUp: Boolean,
    laiziMark: Boolean
) {
    val r = size.width / 7f
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(BACK_TOP, BACK_BOTTOM)),
        cornerRadius = CornerRadius(r, r)
    )
    if (!faceUp || tile == null) return
    val inset = size.width * 0.075f
    val face = Rect(
        left = inset * 0.72f,
        top = inset * 0.6f,
        right = size.width - inset * 0.72f,
        bottom = size.height - size.height * 0.14f
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(FACE_WHITE, Color(0xFFF4EFDF)),
            startY = face.top, endY = face.bottom
        ),
        topLeft = Offset(face.left, face.top),
        size = Size(face.width, face.height),
        cornerRadius = CornerRadius(r * 0.8f, r * 0.8f)
    )
    drawRoundRect(
        color = Color(0x22000000),
        topLeft = Offset(face.left, face.top),
        size = Size(face.width, face.height),
        cornerRadius = CornerRadius(r * 0.8f, r * 0.8f),
        style = Stroke(width = 0.8f)
    )
    when (tile.suit) {
        MjSuit.WAN -> drawWan(measurer, tile.num, face)
        MjSuit.TONG -> drawTong(tile.num, face)
        MjSuit.TIAO -> drawTiao(tile.num, face)
        MjSuit.ZI -> drawZi(measurer, tile.num, face)
    }
    if (laiziMark) {
        val rr = size.width * 0.2f
        drawCircle(Color(0xFFFFC107), rr, Offset(size.width - rr - 2f, rr + 2f))
        drawCircle(Color(0xFF5D4037), rr * 0.55f, Offset(size.width - rr - 2f, rr + 2f))
    }
}

private fun spPx(v: Float) = TextUnit(v, TextUnitType.Unspecified)

// ------------------------------------------------ 万

private fun DrawScope.drawWan(
    measurer: androidx.compose.ui.text.TextMeasurer, num: Int, face: Rect
) {
    val numCn = when (num) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"
        6 -> "六"; 7 -> "七"; 8 -> "八"; else -> "九"
    }
    val topSize = face.height * 0.4f
    val botSize = face.height * 0.48f
    val t1 = measurer.measure(
        numCn,
        TextStyle(color = FACE_BLUE, fontSize = spPx(topSize), fontWeight = FontWeight.Black)
    )
    drawText(t1, topLeft = Offset(face.left + (face.width - t1.size.width) / 2, face.top + face.height * 0.02f))
    val t2 = measurer.measure(
        "萬",
        TextStyle(color = FACE_RED, fontSize = spPx(botSize), fontWeight = FontWeight.Black)
    )
    drawText(t2, topLeft = Offset(face.left + (face.width - t2.size.width) / 2, face.bottom - t2.size.height * 1.06f))
}

// ------------------------------------------------ 筒

private data class Dot(val x: Float, val y: Float, val c: Color)

private fun tongDots(num: Int): List<Dot> {
    val B = FACE_BLUE; val G = FACE_GREEN; val R = FACE_RED
    return when (num) {
        1 -> listOf(Dot(0.5f, 0.5f, R))
        2 -> listOf(Dot(0.5f, 0.3f, B), Dot(0.5f, 0.7f, G))
        3 -> listOf(Dot(0.28f, 0.24f, B), Dot(0.5f, 0.5f, R), Dot(0.72f, 0.76f, G))
        4 -> listOf(
            Dot(0.33f, 0.3f, B), Dot(0.67f, 0.3f, B),
            Dot(0.33f, 0.7f, G), Dot(0.67f, 0.7f, G)
        )
        5 -> listOf(
            Dot(0.3f, 0.28f, B), Dot(0.7f, 0.28f, B), Dot(0.5f, 0.5f, R),
            Dot(0.3f, 0.72f, G), Dot(0.7f, 0.72f, G)
        )
        6 -> listOf(
            Dot(0.35f, 0.22f, G), Dot(0.65f, 0.22f, G),
            Dot(0.35f, 0.5f, R), Dot(0.65f, 0.5f, R),
            Dot(0.35f, 0.78f, R), Dot(0.65f, 0.78f, R)
        )
        7 -> listOf(
            Dot(0.3f, 0.16f, G), Dot(0.5f, 0.28f, G), Dot(0.7f, 0.4f, G),
            Dot(0.35f, 0.66f, R), Dot(0.65f, 0.66f, R),
            Dot(0.35f, 0.88f, R), Dot(0.65f, 0.88f, R)
        )
        8 -> listOf(
            Dot(0.33f, 0.16f, B), Dot(0.67f, 0.16f, B),
            Dot(0.33f, 0.39f, B), Dot(0.67f, 0.39f, B),
            Dot(0.33f, 0.62f, G), Dot(0.67f, 0.62f, G),
            Dot(0.33f, 0.85f, G), Dot(0.67f, 0.85f, G)
        )
        else -> listOf(
            Dot(0.24f, 0.18f, B), Dot(0.5f, 0.18f, B), Dot(0.76f, 0.18f, B),
            Dot(0.24f, 0.5f, R), Dot(0.5f, 0.5f, R), Dot(0.76f, 0.5f, R),
            Dot(0.24f, 0.82f, G), Dot(0.5f, 0.82f, G), Dot(0.76f, 0.82f, G)
        )
    }
}

private fun DrawScope.drawTong(num: Int, face: Rect) {
    if (num == 1) {
        val cx = face.center.x; val cy = face.center.y
        val rr = min(face.width, face.height) * 0.34f
        drawCircle(FACE_BLUE, rr, Offset(cx, cy), style = Stroke(face.width * 0.05f))
        drawCircle(FACE_GREEN, rr * 0.74f, Offset(cx, cy), style = Stroke(face.width * 0.05f))
        drawCircle(FACE_RED, rr * 0.45f, Offset(cx, cy))
        drawCircle(Color.White, rr * 0.28f, Offset(cx, cy))
        drawCircle(FACE_RED, rr * 0.12f, Offset(cx, cy))
        return
    }
    val dots = tongDots(num)
    val maxR = when (num) {
        in 2..4 -> 0.16f; 5 -> 0.15f; in 6..8 -> 0.13f; else -> 0.12f
    }
    dots.forEach { d ->
        val cx = face.left + face.width * d.x
        val cy = face.top + face.height * d.y
        val rr = face.width * maxR
        drawCircle(d.c, rr, Offset(cx, cy), style = Stroke(face.width * 0.045f))
        drawCircle(Color(0x33FFFFFF), rr * 0.62f, Offset(cx, cy))
        drawCircle(d.c, rr * 0.3f, Offset(cx, cy))
    }
}

// ------------------------------------------------ 条

private data class Bamboo(val x: Float, val y: Float, val c: Color)

private fun tiaoSticks(num: Int): List<Bamboo> {
    val G = FACE_GREEN; val R = FACE_RED
    return when (num) {
        2 -> listOf(Bamboo(0.32f, 0.5f, G), Bamboo(0.68f, 0.5f, G))
        3 -> listOf(Bamboo(0.5f, 0.28f, G), Bamboo(0.32f, 0.72f, G), Bamboo(0.68f, 0.72f, G))
        4 -> listOf(
            Bamboo(0.32f, 0.3f, G), Bamboo(0.68f, 0.3f, G),
            Bamboo(0.32f, 0.7f, G), Bamboo(0.68f, 0.7f, G)
        )
        5 -> listOf(
            Bamboo(0.28f, 0.26f, G), Bamboo(0.72f, 0.26f, G),
            Bamboo(0.5f, 0.5f, R),
            Bamboo(0.28f, 0.74f, G), Bamboo(0.72f, 0.74f, G)
        )
        6 -> listOf(
            Bamboo(0.32f, 0.22f, G), Bamboo(0.68f, 0.22f, G),
            Bamboo(0.32f, 0.5f, G), Bamboo(0.68f, 0.5f, G),
            Bamboo(0.32f, 0.78f, G), Bamboo(0.68f, 0.78f, G)
        )
        7 -> listOf(
            Bamboo(0.5f, 0.14f, R),
            Bamboo(0.3f, 0.42f, G), Bamboo(0.5f, 0.42f, G), Bamboo(0.7f, 0.42f, G),
            Bamboo(0.3f, 0.72f, G), Bamboo(0.5f, 0.72f, G), Bamboo(0.7f, 0.72f, G)
        )
        8 -> listOf(
            Bamboo(0.28f, 0.18f, G), Bamboo(0.5f, 0.18f, G), Bamboo(0.72f, 0.18f, G),
            Bamboo(0.28f, 0.46f, G), Bamboo(0.72f, 0.46f, G),
            Bamboo(0.28f, 0.74f, G), Bamboo(0.5f, 0.74f, G), Bamboo(0.72f, 0.74f, G)
        )
        else -> listOf(
            Bamboo(0.26f, 0.18f, G), Bamboo(0.5f, 0.18f, G), Bamboo(0.74f, 0.18f, G),
            Bamboo(0.26f, 0.5f, G), Bamboo(0.5f, 0.5f, G), Bamboo(0.74f, 0.5f, G),
            Bamboo(0.26f, 0.82f, G), Bamboo(0.5f, 0.82f, G), Bamboo(0.74f, 0.82f, G)
        )
    }
}

private fun DrawScope.drawTiao(num: Int, face: Rect) {
    if (num == 1) {
        // 一条：简化幺鸡（蓝身红头绿尾）
        val cx = face.center.x; val cy = face.center.y
        val s = min(face.width, face.height)
        val body = Path().apply {
            moveTo(cx - s * 0.16f, cy + s * 0.1f)
            cubicTo(cx - s * 0.3f, cy - s * 0.16f, cx + s * 0.1f, cy - s * 0.3f, cx + s * 0.2f, cy - s * 0.02f)
            cubicTo(cx + s * 0.26f, cy + s * 0.16f, cx + s * 0.02f, cy + s * 0.28f, cx - s * 0.16f, cy + s * 0.1f)
            close()
        }
        drawPath(body, FACE_BLUE)
        drawCircle(FACE_RED, s * 0.09f, Offset(cx + s * 0.12f, cy - s * 0.2f))
        drawLine(FACE_RED, Offset(cx + s * 0.19f, cy - s * 0.22f), Offset(cx + s * 0.3f, cy - s * 0.18f), s * 0.035f)
        drawLine(FACE_GREEN, Offset(cx - s * 0.14f, cy + s * 0.12f), Offset(cx - s * 0.3f, cy + s * 0.3f), s * 0.04f)
        drawLine(FACE_GREEN, Offset(cx - s * 0.1f, cy + s * 0.16f), Offset(cx - s * 0.22f, cy + s * 0.34f), s * 0.04f)
        drawLine(FACE_GREEN, Offset(cx - s * 0.04f, cy + s * 0.24f), Offset(cx - s * 0.04f, cy + s * 0.34f), s * 0.03f)
        return
    }
    val sticks = tiaoSticks(num)
    val bw = face.width * (if (num <= 4) 0.17f else 0.14f)
    val bh = face.height * (if (num <= 4) 0.4f else 0.3f)
    sticks.forEach { st ->
        val cx = face.left + face.width * st.x
        val cy = face.top + face.height * st.y
        drawBamboo(cx, cy, bw, bh, st.c)
    }
}

private fun DrawScope.drawBamboo(cx: Float, cy: Float, w: Float, h: Float, color: Color) {
    val seg = h / 3f
    val left = cx - w / 2
    val top = cy - h / 2
    val rr = CornerRadius(w * 0.42f, w * 0.42f)
    drawRoundRect(color, Offset(left, top), Size(w, seg * 0.92f), rr)
    drawRoundRect(color, Offset(left, top + seg * 1.04f), Size(w, seg * 0.92f), rr)
    drawRoundRect(color, Offset(left, top + seg * 2.08f), Size(w, seg * 0.92f), rr)
    drawRoundRect(
        Color(0x66FFFFFF), Offset(left + w * 0.22f, top + seg * 0.12f),
        Size(w * 0.3f, seg * 0.68f), rr
    )
}

// ------------------------------------------------ 字

private fun DrawScope.drawZi(
    measurer: androidx.compose.ui.text.TextMeasurer, num: Int, face: Rect
) {
    when (num) {
        7 -> {
            // 白板：双蓝框
            val l = face.left + face.width * 0.16f
            val t = face.top + face.height * 0.1f
            val r = face.right - face.width * 0.16f
            val b = face.bottom - face.height * 0.1f
            drawRoundRect(
                FACE_BLUE, Offset(l, t), Size(r - l, b - t),
                CornerRadius(face.width * 0.05f, face.width * 0.05f),
                style = Stroke(face.width * 0.05f)
            )
            drawRoundRect(
                FACE_BLUE, Offset(l + face.width * 0.07f, t + face.height * 0.05f),
                Size(r - l - face.width * 0.14f, b - t - face.height * 0.1f),
                CornerRadius(face.width * 0.04f, face.width * 0.04f),
                style = Stroke(face.width * 0.03f)
            )
        }
        else -> {
            val label = when (num) {
                1 -> "東"; 2 -> "南"; 3 -> "西"; 4 -> "北"; 5 -> "中"; else -> "發"
            }
            val color = when (num) {
                5 -> FACE_RED; 6 -> FACE_GREEN; else -> FACE_BLUE
            }
            val sz = face.height * 0.62f
            val t = measurer.measure(
                label,
                TextStyle(color = color, fontSize = spPx(sz), fontWeight = FontWeight.Black)
            )
            drawText(
                t,
                topLeft = Offset(
                    face.left + (face.width - t.size.width) / 2,
                    face.top + (face.height - t.size.height) / 2
                )
            )
        }
    }
}
