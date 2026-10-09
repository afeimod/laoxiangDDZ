package com.laoxiang.ddz.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.MjTile

/**
 * 麻将渲染 v6 —— 按用户视频（天凤风格）完全重制：
 * - 白胚牌体由 Canvas 绘制（象牙白渐变+灰描边+底部厚度边），任意立/横方向厚度恒在下方
 * - 刻字 = PIL 预生成的 34 张透明字层（ten_g*，黑墨数字+红萬/蓝红筒圈/绿索/黑字牌红中绿发）
 * - 牌墙背面 = ten_wall（黑面+灰刻東+白下缘），左右墙旋转 90°
 * - 可鸣高亮 = 视频中的青蓝色牌身
 */

/** code(0..33) -> ten_g 字层资源（0-8万 9-17筒 18-26条 27東 28南 29西 30北 31白 32发 33中） */
internal fun tenGlyphRes(code: Int): Int = when (code) {
    0 -> R.drawable.ten_gm1; 1 -> R.drawable.ten_gm2; 2 -> R.drawable.ten_gm3
    3 -> R.drawable.ten_gm4; 4 -> R.drawable.ten_gm5; 5 -> R.drawable.ten_gm6
    6 -> R.drawable.ten_gm7; 7 -> R.drawable.ten_gm8; 8 -> R.drawable.ten_gm9
    9 -> R.drawable.ten_gp1; 10 -> R.drawable.ten_gp2; 11 -> R.drawable.ten_gp3
    12 -> R.drawable.ten_gp4; 13 -> R.drawable.ten_gp5; 14 -> R.drawable.ten_gp6
    15 -> R.drawable.ten_gp7; 16 -> R.drawable.ten_gp8; 17 -> R.drawable.ten_gp9
    18 -> R.drawable.ten_gs1; 19 -> R.drawable.ten_gs2; 20 -> R.drawable.ten_gs3
    21 -> R.drawable.ten_gs4; 22 -> R.drawable.ten_gs5; 23 -> R.drawable.ten_gs6
    24 -> R.drawable.ten_gs7; 25 -> R.drawable.ten_gs8; 26 -> R.drawable.ten_gs9
    27 -> R.drawable.ten_gz1; 28 -> R.drawable.ten_gz2; 29 -> R.drawable.ten_gz3
    30 -> R.drawable.ten_gz4; 31 -> R.drawable.ten_gz5; 32 -> R.drawable.ten_gz6
    else -> R.drawable.ten_gz7
}

/** 兼容旧调用（工具条预览） */
internal fun faceRes(code: Int): Int = tenGlyphRes(code)

/** 牌面主色 */
private val FACE_TOP = Color(0xFFFDFCF7)
private val FACE_BOT = Color(0xFFEFEBDF)
private val FACE_EDGE = Color(0xFFB7B4A8)
private val FACE_THICK = Color(0xFFD8D4C6)
private val FACE_THICK_D = Color(0xFFB5B1A2)
val TEN_HL = Color(0xFF43C7DE)          // 视频青蓝高亮

/**
 * 牌体白胚（Canvas）：[w]x[h] 圆角矩形，底部厚度带。
 * [highlight]=青蓝牌身（视频可鸣牌）。
 */
@Composable
fun TenTileBody(
    w: Dp, h: Dp,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    alpha: Float = 1f
) {
    val top = if (highlight) Color(0xFF7FDEEC) else FACE_TOP
    val bot = if (highlight) Color(0xFF2FA8C4) else FACE_BOT
    Canvas(modifier.size(w, h).graphicsLayer { this.alpha = alpha }) {
        val th = size.height * 0.10f          // 厚度带高
        val r = size.width * 0.16f
        // 厚度（下移的深色圆角矩形）
        drawRoundRect(
            Brush.verticalGradient(listOf(FACE_THICK, FACE_THICK_D)),
            Offset(0f, th * 0.55f), Size(size.width, size.height - th * 0.55f),
            CornerRadius(r, r)
        )
        // 牌面
        drawRoundRect(
            Brush.verticalGradient(listOf(top, bot)),
            Offset(0f, 0f), Size(size.width, size.height - th),
            CornerRadius(r, r)
        )
        // 描边
        drawRoundRect(
            FACE_EDGE,
            Offset(0.75f, 0.75f), Size(size.width - 1.5f, size.height - th - 1.5f),
            CornerRadius(r, r), Stroke(width = size.width * 0.018f)
        )
    }
}

/** 刻字层（透明 PNG），[rot]=旋转角；[bw]x[bh]=所在牌盒尺寸 */
@Composable
private fun TenGlyph(code: Int, bw: Dp, bh: Dp, rot: Float, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(
        painter = painterResource(tenGlyphRes(code)),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .size(bw, bh)
            .graphicsLayer { rotationZ = rot; this.alpha = alpha }
    )
}

/** 立牌（手牌/刚出的牌）：白胚 + 正立刻字 */
@Composable
fun TenStandTile(
    code: Int, w: Dp,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    alpha: Float = 1f
) {
    val h = w * 1.38f
    Box(modifier.size(w, h)) {
        TenTileBody(w, h, highlight = highlight, alpha = alpha, modifier = Modifier.matchParentSize())
        TenGlyph(code, w, h, 0f, Modifier.align(Alignment.Center), alpha)
    }
}

/**
 * 牌河躺牌（视频规则，[scale]=相对手牌缩放，视频约 0.86）：
 * dir0=我(下) 横盒刻字逆时针90(头朝左) / dir1=右 竖盒刻字正立
 * dir2=上 横盒刻字顺时针90(头朝右) / dir3=左 竖盒刻字倒立
 */
@Composable
fun TenRiverTile(
    code: Int, dir: Int, u: Dp,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    alpha: Float = 1f,
    scale: Float = 1f
) {
    val pw = u * 64f * scale; val ph = u * 88f * scale
    when (dir) {
        1 -> Box(modifier.size(pw, ph)) {
            TenTileBody(pw, ph, highlight = highlight, alpha = alpha, modifier = Modifier.matchParentSize())
            TenGlyph(code, pw, ph, 0f, Modifier.align(Alignment.Center), alpha)
        }
        3 -> Box(modifier.size(pw, ph)) {
            TenTileBody(pw, ph, highlight = highlight, alpha = alpha, modifier = Modifier.matchParentSize())
            TenGlyph(code, pw, ph, 180f, Modifier.align(Alignment.Center), alpha)
        }
        2 -> Box(modifier.size(ph, pw)) {
            TenTileBody(ph, pw, highlight = highlight, alpha = alpha, modifier = Modifier.matchParentSize())
            TenGlyph(code, pw, ph, 90f, Modifier.align(Alignment.Center), alpha)
        }
        else -> Box(modifier.size(ph, pw)) {
            TenTileBody(ph, pw, highlight = highlight, alpha = alpha, modifier = Modifier.matchParentSize())
            TenGlyph(code, pw, ph, -90f, Modifier.align(Alignment.Center), alpha)
        }
    }
}

/**
 * 副露牌：各座位立牌读向不同（dir0=0° dir1=-90° dir2=180° dir3=90°）；
 * [claimed]=吃碰杠得来的供牌 → 横置（横盒+再转90°，日麻规则侧翻标记）。
 */
@Composable
fun TenMeldTile(
    code: Int, dir: Int, u: Dp,
    modifier: Modifier = Modifier,
    claimed: Boolean = false,
    scale: Float = 1f
) {
    val pw = u * 64f * scale; val ph = u * 88f * scale
    val baseRot = when (dir) { 1 -> -90f; 2 -> 180f; 3 -> 90f; else -> 0f }
    if (!claimed) {
        Box(modifier.size(pw, ph)) {
            TenTileBody(pw, ph, modifier = Modifier.matchParentSize())
            TenGlyph(code, pw, ph, baseRot, Modifier.align(Alignment.Center))
        }
    } else {
        Box(modifier.size(ph, pw)) {
            TenTileBody(ph, pw, modifier = Modifier.matchParentSize())
            TenGlyph(code, pw, ph, baseRot - 90f, Modifier.align(Alignment.Center))
        }
    }
}

/** 牌墙背面（黑面+灰刻東+白下缘）：rot 0=上墙(白缘朝下=朝中央) 90=右墙 180=下 270=左墙 */
@Composable
fun TenWallTile(u: Dp, rot: Float, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val w = u * 30f
    val h = u * 22f
    val boxW = if (rot == 90f || rot == 270f) h else w
    val boxH = if (rot == 90f || rot == 270f) w else h
    Image(
        painter = painterResource(R.drawable.ten_wall),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .size(boxW, boxH)
            .graphicsLayer { rotationZ = rot; this.alpha = alpha }
    )
}

/** 牌背小图（工具条/预览用）：黑面+灰東 */
@Composable
fun TenBackTile(w: Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val h = w * 1.38f
    val glyphSp = with(androidx.compose.ui.platform.LocalDensity.current) { (w * 0.62f).toSp() }
    Box(modifier.size(w, h)) {
        Canvas(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha }) {
            val r = size.width * 0.16f
            drawRoundRect(Color(0xFF14171E), Offset.Zero, size, CornerRadius(r, r))
            drawRoundRect(
                Color(0xFF232838),
                Offset(size.width * 0.08f, size.height * 0.06f),
                Size(size.width * 0.84f, size.height * 0.5f),
                CornerRadius(r, r)
            )
        }
        Text(
            "東", color = Color(0xFF4A5062), fontSize = glyphSp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ================================================================ 兼容入口

/** 单张麻将牌（tile=null 或 faceUp=false 画牌背）—— 工具条等小尺寸预览用 */
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
    val h = w * 1.38f
    val lifted = raised || selected
    Box(
        modifier
            .offset(y = if (lifted) (-8).dp else 0.dp)
            .size(w, h)
    ) {
        if (!faceUp || tile == null) {
            TenBackTile(w, Modifier.matchParentSize(), alpha)
        } else {
            TenStandTile(tile.code, w, Modifier.matchParentSize(), alpha = alpha)
        }
        if (laiziMark && faceUp && tile != null) LaiziBadge(w, Modifier.align(Alignment.TopEnd))
    }
}

/** 癞子（红中）小红标 */
@Composable
fun LaiziBadge(w: Dp, modifier: Modifier = Modifier) {
    val badgeSp = with(androidx.compose.ui.platform.LocalDensity.current) { (w * 0.20f).toSp() }
    Box(
        modifier
            .size(w * 0.34f, w * 0.34f)
            .clip(RoundedCornerShape(w * 0.09f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(Color(0xFFC22A1F), Offset.Zero, size, CornerRadius(size.width * 0.28f))
        }
        Text(
            "癩",
            color = Color.White,
            fontSize = badgeSp,
            fontWeight = FontWeight.Black
        )
    }
}
