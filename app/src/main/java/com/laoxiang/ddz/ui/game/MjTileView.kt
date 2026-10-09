package com.laoxiang.ddz.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.MjTile

/**
 * 麻将渲染 v7 —— 恢复原 APK（net.joygames.chinamj）素材与合成方案（用户明确要求保留原绘制方案，
 * v6 的 PIL 重绘字层废弃），并保留天凤版式所需的立/躺/四方向/高亮适配层：
 * - 大牌（手牌，APK DrawMj） = psmj0 白胚 + psmj 面 89x128 原尺寸叠加
 * - 桌牌（牌河，APK DrawCCMj）= 白胚 tablemj*_0 1:1 + 面 0.573 缩放旋转
 * - 小牌（副露，APK drawSmallMj）= 白胚 0.8 + 面 0.4584 旋转
 * - 四方向旋转角：下 0° / 右 270° / 上 180° / 左 90°（与 APK 字节码一致）
 * - 牌墙（按视频 z_wall_top/z_left_zone）：每墩 = 深色東刻牌背 + 白色牌身（双层）
 * - 他家手牌背（按视频侧家白色素背）+ 可鸣青蓝高亮（视频青蓝牌身）
 * 所有偏移取自反编译常量（1280 设计空间，[u] 为每设计像素的 Dp 数）。
 */

/** code(0..33) -> psmj 贴图资源（psmj0=白胚 1-9万 10-18索 19-27筒 28-34字 35-42花） */
internal fun psmjRes(code: Int): Int = when (code) {
    0 -> R.drawable.psmj1; 1 -> R.drawable.psmj2; 2 -> R.drawable.psmj3
    3 -> R.drawable.psmj4; 4 -> R.drawable.psmj5; 5 -> R.drawable.psmj6
    6 -> R.drawable.psmj7; 7 -> R.drawable.psmj8; 8 -> R.drawable.psmj9
    9 -> R.drawable.psmj19; 10 -> R.drawable.psmj20; 11 -> R.drawable.psmj21
    12 -> R.drawable.psmj22; 13 -> R.drawable.psmj23; 14 -> R.drawable.psmj24
    15 -> R.drawable.psmj25; 16 -> R.drawable.psmj26; 17 -> R.drawable.psmj27
    18 -> R.drawable.psmj10; 19 -> R.drawable.psmj11; 20 -> R.drawable.psmj12
    21 -> R.drawable.psmj13; 22 -> R.drawable.psmj14; 23 -> R.drawable.psmj15
    24 -> R.drawable.psmj16; 25 -> R.drawable.psmj17; 26 -> R.drawable.psmj18
    27 -> R.drawable.psmj28; 28 -> R.drawable.psmj29; 29 -> R.drawable.psmj30
    30 -> R.drawable.psmj31; 31 -> R.drawable.psmj32; 32 -> R.drawable.psmj33
    else -> R.drawable.psmj34
}

/**
 * psmj 面图逐张刻字内容中心（89x128 画布，bbox 中点实测）。
 * 每张牌的刻字在画布中的落位不同（cy 68~74，画布中心 64），
 * 横躺牌旋转后该偏差会变成左右偏移——逐张补偿后刻字才能全部正中白面。
 */
private val FACE_CC = floatArrayOf(
    43.0f, 73.5f, 43.0f, 71.0f, 43.0f, 69.5f, 43.0f, 71.0f,
    43.0f, 70.0f, 43.0f, 68.5f, 43.0f, 68.0f, 43.0f, 71.5f,
    43.0f, 70.0f, 43.5f, 70.0f, 43.0f, 69.5f, 43.0f, 69.5f,
    43.0f, 69.5f, 43.0f, 70.0f, 43.5f, 69.5f, 43.5f, 69.5f,
    43.0f, 69.5f, 43.0f, 69.5f, 43.5f, 74.0f, 43.5f, 69.5f,
    43.5f, 70.5f, 43.0f, 71.5f, 43.5f, 69.5f, 43.0f, 71.5f,
    43.5f, 70.5f, 43.0f, 70.0f, 45.0f, 69.5f, 45.0f, 69.5f,
    43.5f, 69.5f, 43.5f, 69.5f, 45.0f, 69.0f, 43.5f, 69.5f,
    45.0f, 69.0f, 43.5f, 69.5f
)

/**
 * 桌牌/小牌四方向刻字目标点（盒坐标）——取自 APK 字节码实测：
 * DrawCCMj/drawSmallMj 把面图绕自身中心旋转后放到固定画布中心，
 * 换算成「面内容中心」的目标位置（中值内容 (43.8,70.8)，逐张偏差另由 FACE_CC 补偿）：
 *   桌牌(面 0.573)：dir0=(25.10,30.57) dir1=(33.07,33.40) dir2=(25.90,31.43) dir3=(32.93,32.60)
 *   小牌(面 0.4584)：dir0=(20.08,28.46) dir1=(26.96,26.42) dir2=(21.92,27.54) dir3=(26.04,25.58)
 * 注意横躺方向（dir1/3）目标比白面几何中心 (31.5,32) 略低且偏右——这是原版观感（用户对比确认），
 * 纯几何居中反而显"偏上"。
 */
private val TABLE_CC_X = floatArrayOf(25.10f, 33.07f, 25.90f, 32.93f)
private val TABLE_CC_Y = floatArrayOf(30.57f, 33.40f, 31.43f, 32.60f)
private val SMALL_CC_X = floatArrayOf(20.08f, 26.96f, 21.92f, 26.04f)
private val SMALL_CC_Y = floatArrayOf(28.46f, 26.42f, 27.54f, 25.58f)

/**
 * 逐张刻字对中：令面图内容中心经「缩放+旋转」后正好落在 [tx]/[ty] 目标点。
 * [scale]=面图缩放；[halfW]/[halfH]=缩放后面图半宽/半高。
 * 返回面图未旋转时相对盒左上角的偏移 (ox, oy)（与 Compose 绕自身中心旋转配套）。
 */
private fun faceCenteredOffset(code: Int, dir: Int, scale: Float, halfW: Float, halfH: Float, tx: Float, ty: Float): Pair<Float, Float> {
    val dx = (FACE_CC[code * 2] - 44.5f) * scale      // 内容中心相对画布中心
    val dy = (FACE_CC[code * 2 + 1] - 64f) * scale
    val rdx: Float; val rdy: Float
    when (dir) {
        1 -> { rdx = dy; rdy = -dx }                   // rot270（顺时针）：上→右
        2 -> { rdx = -dx; rdy = -dy }                  // rot180
        3 -> { rdx = -dy; rdy = dx }                   // rot90：上→左
        else -> { rdx = dx; rdy = dy }
    }
    return (tx - rdx - halfW) to (ty - rdy - halfH)
}

/** code(0..33) -> 旧版单图牌面（工具条小预览用） */
internal fun faceRes(code: Int): Int = when (code) {
    0 -> R.drawable.mj_wan1; 1 -> R.drawable.mj_wan2; 2 -> R.drawable.mj_wan3
    3 -> R.drawable.mj_wan4; 4 -> R.drawable.mj_wan5; 5 -> R.drawable.mj_wan6
    6 -> R.drawable.mj_wan7; 7 -> R.drawable.mj_wan8; 8 -> R.drawable.mj_wan9
    9 -> R.drawable.mj_tong1; 10 -> R.drawable.mj_tong2; 11 -> R.drawable.mj_tong3
    12 -> R.drawable.mj_tong4; 13 -> R.drawable.mj_tong5; 14 -> R.drawable.mj_tong6
    15 -> R.drawable.mj_tong7; 16 -> R.drawable.mj_tong8; 17 -> R.drawable.mj_tong9
    18 -> R.drawable.mj_tiao1; 19 -> R.drawable.mj_tiao2; 20 -> R.drawable.mj_tiao3
    21 -> R.drawable.mj_tiao4; 22 -> R.drawable.mj_tiao5; 23 -> R.drawable.mj_tiao6
    24 -> R.drawable.mj_tiao7; 25 -> R.drawable.mj_tiao8; 26 -> R.drawable.mj_tiao9
    27 -> R.drawable.mj_east; 28 -> R.drawable.mj_south; 29 -> R.drawable.mj_west
    30 -> R.drawable.mj_north; 31 -> R.drawable.mj_zhong; 32 -> R.drawable.mj_fa
    else -> R.drawable.mj_bai
}

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
    val h = w * 1.38202f
    val lifted = raised || selected
    val painter = painterResource(
        if (!faceUp || tile == null) R.drawable.mj_back else faceRes(tile.code)
    )
    Box(
        modifier
            .offset(y = if (lifted) (-10).dp else 0.dp)
            .then(if (lifted) Modifier.shadow(8.dp, RoundedCornerShape(w / 6)) else Modifier)
            .size(w, h)
    ) {
        Image(
            painter = painter,
            contentDescription = if (faceUp && tile != null) tile.label else "麻将牌背面",
            modifier = Modifier.size(w, h),
            contentScale = ContentScale.FillBounds,
            alpha = alpha
        )
        if (laiziMark && faceUp) {
            Image(
                painter = painterResource(R.drawable.mj_mark_caishen),
                contentDescription = "癞子标记",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = w * 0.04f, top = w * 0.05f)
                    .size(w * 0.26f, w * 0.26f * 59f / 30f)
            )
        }
    }
}

/**
 * 大牌合成（APK DrawMj 1:1）：psmj0 白胚(89x123) + psmj 面(89x128) 同位叠加。
 * [u] = 1280 设计空间每像素 Dp 值；宽 89u，高 128u。
 */
@Composable
internal fun MjBigTile(
    code: Int,
    u: Dp,
    modifier: Modifier = Modifier,
    laiziMark: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    val w = u * 89f
    val h = u * 128f
    Box(modifier.size(w, h)) {
        Image(
            painter = painterResource(R.drawable.psmj0),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(w, u * 123f)
        )
        Image(
            painter = painterResource(psmjRes(code)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(w, h)
        )
        content()
        if (laiziMark) {
            Image(
                painter = painterResource(R.drawable.mj_mark_caishen),
                contentDescription = "癞子标记",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = w * 0.04f, top = w * 0.05f)
                    .size(w * 0.26f, w * 0.26f * 59f / 30f)
            )
        }
    }
}

/**
 * 桌面牌合成（APK DrawCCMj 1:1）：白胚 1:1 + 面 0.573 缩放旋转。
 * dir: 0=下(立牌 tablemjh0) / 1=右(横躺 tablemjwh0) / 2=上(立牌 tablemjnh0) / 3=左(横躺 tablemjeh0)
 * 占位：dir0/2 = 51x76u，dir1/3 = 64x64u；刻字按 APK 常量偏移。
 */
@Composable
internal fun MjTableTile(code: Int, dir: Int, u: Dp, modifier: Modifier = Modifier) {
    val boxW = if (dir == 1 || dir == 3) u * 64f else u * 51f
    val boxH = if (dir == 1 || dir == 3) u * 64f else u * 76f
    val fw = u * 89f * 0.573f          // 51.0u
    val fh = u * 128f * 0.573f         // 73.34u
    val bodyRes = when (dir) {
        1 -> R.drawable.tablemjwh0
        2 -> R.drawable.tablemjnh0
        3 -> R.drawable.tablemjeh0
        else -> R.drawable.tablemjh0
    }
    val rotation = when (dir) { 1 -> 270f; 2 -> 180f; 3 -> 90f; else -> 0f }
    Box(modifier.size(boxW, boxH)) {
        Image(
            painter = painterResource(bodyRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(boxW, boxH)
        )
        // 刻字逐张对中：目标点=APK 字节码派生（TABLE_CC_*），逐张内容偏差由 FACE_CC 补偿
        val (ox, oy) = faceCenteredOffset(
            code, dir, 0.573f, halfW = 25.5f, halfH = 36.67f,
            tx = TABLE_CC_X[dir], ty = TABLE_CC_Y[dir]
        )
        Image(
            painter = painterResource(psmjRes(code)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset(x = u * ox, y = u * oy)
                .size(fw, fh)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

/**
 * 立式桌牌（他家牌河最新一张立起用）：立牌白胚 + 按方向旋转的刻字。
 * 盒 = 51x76u（与 MjTableTile 的立牌占位一致）。
 */
@Composable
internal fun MjStandTableTile(code: Int, dir: Int, u: Dp, modifier: Modifier = Modifier) {
    val boxW = u * 51f
    val boxH = u * 76f
    val fw = u * 89f * 0.573f
    val fh = u * 128f * 0.573f
    val rotation = when (dir) { 1 -> 270f; 2 -> 180f; 3 -> 90f; else -> 0f }
    // 目标点用立牌 dir0 目标，内容偏差按实际方向旋转补偿
    val (ox, oy) = faceCenteredOffset(
        code, dir, 0.573f, halfW = 25.5f, halfH = 36.67f,
        tx = TABLE_CC_X[0], ty = TABLE_CC_Y[0]
    )
    Box(modifier.size(boxW, boxH)) {
        Image(
            painter = painterResource(R.drawable.tablemjh0),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(boxW, boxH)
        )
        Image(
            painter = painterResource(psmjRes(code)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset(x = u * ox, y = u * oy)
                .size(fw, fh)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

/**
 * 小牌合成（APK drawSmallMj 1:1）：白胚 0.8 + 面 0.4584 缩放旋转（副露）。
 * 盒：dir0/2 = 40.8x60.8u，dir1/3 = 51.2x51.2u。
 */
@Composable
internal fun MjSmallTile(code: Int, dir: Int, u: Dp, modifier: Modifier = Modifier) {
    val bodyRes = when (dir) {
        1 -> R.drawable.tablemjwh0
        2 -> R.drawable.tablemjnh0
        3 -> R.drawable.tablemjeh0
        else -> R.drawable.tablemjh0
    }
    val boxW = if (dir == 1 || dir == 3) u * 64f * 0.8f else u * 51f * 0.8f
    val boxH = if (dir == 1 || dir == 3) u * 64f * 0.8f else u * 76f * 0.8f
    val fw = u * 89f * 0.4584f          // 40.8u
    val fh = u * 128f * 0.4584f         // 58.68u
    val rotation = when (dir) { 1 -> 270f; 2 -> 180f; 3 -> 90f; else -> 0f }
    val (ox, oy) = faceCenteredOffset(
        code, dir, 0.4584f, halfW = 20.4f, halfH = 29.34f,
        tx = SMALL_CC_X[dir], ty = SMALL_CC_Y[dir]
    )
    Box(modifier.size(boxW, boxH)) {
        Image(
            painter = painterResource(bodyRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(boxW, boxH)
        )
        Image(
            painter = painterResource(psmjRes(code)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset(x = u * ox, y = u * oy)
                .size(fw, fh)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

/** 他家手牌背面（APK 深背）：右家 mjback_e / 上家 mjback_n / 左家 mjback_w */
@Composable
internal fun MjBackTile(seatPos: Int, u: Dp, modifier: Modifier = Modifier) {
    val (res, w, h) = when (seatPos) {
        1 -> Triple(R.drawable.mjback_e, 24f, 58f)
        2 -> Triple(R.drawable.mjback_n, 32f, 46f)
        else -> Triple(R.drawable.mjback_w, 24f, 58f)
    }
    Image(
        painter = painterResource(res),
        contentDescription = "手牌背面",
        contentScale = ContentScale.FillBounds,
        modifier = modifier.size(u * w, u * h)
    )
}

// ================================================================ 天凤版式适配层

/** 视频可鸣青蓝高亮色 */
val TEN_HL = Color(0xFF43C7DE)

/** 牌墙背面素色（深黑灰，東字刻花用灰） */
private val WALL_FACE = Color(0xFF191B21)
private val WALL_EDGE = Color(0xFF2A2D36)
private val WALL_GLYPH = Color(0xFF6A7080)
private val BODY_TOP = Color(0xFFFDFCF7)
private val BODY_BOT = Color(0xFFE9E5D8)

/**
 * 牌墙一墩（视频 z_wall_top/z_left_zone）：深色東刻牌背 + 白色牌身（双层）。
 * [rot] 0=上墙（横向排列，白身在背下方）90=右墙（深面朝左=朝中央）180=下墙 270=左墙（深面朝右=朝中央）。
 * 传入 [u]=沿墙方向墩宽；占位：rot0/180 = u x (u*0.62)，rot90/270 = (u*0.62) x u。
 */
@Composable
fun TenWallTile(u: Dp, rot: Float, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val horizontal = rot == 0f || rot == 180f
    val w = if (horizontal) u else u * 0.62f
    val h = if (horizontal) u * 0.62f else u
    // 深色背与白色身的矩形划分（px 比例）：横墙=上背下身；右墙=左背右身；左墙=右背左身
    val darkRight = rot == 270f                      // 左墙：深面靠右（朝中央）
    Box(modifier.size(w, h)) {
        // 白色牌身（第二层）
        Canvas(
            Modifier
                .size(w, h)
                .graphicsLayer { this.alpha = alpha }
        ) {
            val r = size.width * 0.18f
            val bodyW = if (horizontal) size.width else size.width * 0.60f
            val bodyH = if (horizontal) size.height * 0.60f else size.height
            val bx = when {
                horizontal -> 0f
                darkRight -> 0f                              // 左墙白身靠外（左）
                else -> size.width - bodyW                   // 右墙白身靠外（右）
            }
            val by = if (horizontal) size.height - bodyH else 0f
            drawRoundRect(
                Brush.verticalGradient(listOf(BODY_TOP, BODY_BOT)),
                Offset(bx, by), androidx.compose.ui.geometry.Size(bodyW, bodyH),
                CornerRadius(r, r)
            )
            drawRoundRect(
                Color(0xFFB7B4A8), Offset(bx, by),
                androidx.compose.ui.geometry.Size(bodyW, bodyH),
                CornerRadius(r, r), style = Stroke(size.width * 0.02f)
            )
        }
        // 深色東刻牌背（朝中央一侧，不另旋转——矩形划分已处理朝向）
        Canvas(
            Modifier
                .size(w, h)
                .graphicsLayer { this.alpha = alpha }
        ) {
            val fw = if (horizontal) size.width else size.width * 0.62f
            val fh = if (horizontal) size.height * 0.62f else size.height
            val fx = if (!horizontal && darkRight) size.width - fw else 0f
            val fy = if (horizontal) 0f else 0f
            drawRoundRect(
                Brush.verticalGradient(listOf(WALL_FACE, Color(0xFF0D0E12))),
                Offset(fx, fy), androidx.compose.ui.geometry.Size(fw, fh),
                CornerRadius(fw * 0.16f, fw * 0.16f)
            )
            drawRoundRect(
                WALL_EDGE, Offset(fx, fy),
                androidx.compose.ui.geometry.Size(fw, fh),
                CornerRadius(fw * 0.16f, fw * 0.16f), style = Stroke(fw * 0.03f)
            )
        }
        // 東字刻花（对准深色面区域，正向可读，与视频墙背一致）
        val glyphW = if (horizontal) w else w * 0.62f
        val glyphH = if (horizontal) h * 0.62f else h
        val glyphOffX = if (!horizontal && darkRight) w - glyphW else 0.dp
        val glyphOffY = if (horizontal) 0.dp else 0.dp
        Box(
            Modifier
                .offset(x = glyphOffX, y = glyphOffY)
                .size(glyphW, glyphH),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "東",
                color = WALL_GLYPH,
                fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (u * 0.32f).toSp() },
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                modifier = Modifier.graphicsLayer { this.alpha = alpha * 0.9f }
            )
        }
    }
}

/** 侧家/上家手牌白色素背（视频侧家白色牌背列） */
@Composable
fun SideHandBack(w: Dp, h: Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Canvas(modifier.size(w, h).graphicsLayer { this.alpha = alpha }) {
        val r = size.width * 0.2f
        drawRoundRect(
            Brush.verticalGradient(listOf(BODY_TOP, BODY_BOT)),
            Offset.Zero, size, CornerRadius(r, r)
        )
        drawRoundRect(
            Color(0xFFB7B4A8), Offset.Zero, size, CornerRadius(r, r),
            style = Stroke(size.width * 0.03f)
        )
    }
}

/** 立牌（手牌/牌河立起张）：APK 大牌合成 + 可鸣青蓝高亮。宽 [w]，高 = w*128/89 */
@Composable
fun TenStandTile(
    code: Int, w: Dp,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    alpha: Float = 1f
) {
    val h = w * 128f / 89f
    Box(modifier.size(w, h)) {
        MjBigTile(code, w / 89f, modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = alpha })
        if (highlight) {
            Canvas(Modifier.matchParentSize()) {
                val r = size.width * 0.14f
                drawRoundRect(TEN_HL.copy(alpha = 0.30f), Offset.Zero, size, CornerRadius(r, r))
                drawRoundRect(TEN_HL, Offset.Zero, size, CornerRadius(r, r), style = Stroke(size.width * 0.06f))
            }
        }
    }
}

/**
 * 牌河牌（视频规则，[scale]=相对桌牌基准缩放）：
 * dir0=我(下) 立牌正读 / dir1=右 横躺刻字 rot270 / dir2=上 立牌倒读 / dir3=左 横躺刻字 rot90
 * [standing]=最新出牌立起（左右两家横躺河中的立起张：立盒+按方向旋转刻字）
 */
@Composable
fun TenRiverTile(
    code: Int, dir: Int, u: Dp,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    alpha: Float = 1f,
    scale: Float = 1f,
    standing: Boolean = false
) {
    val uu = u * scale
    Box(modifier) {
        Box(Modifier.graphicsLayer { this.alpha = alpha }) {
            if (standing) MjStandTableTile(code, dir, uu) else MjTableTile(code, dir, uu)
        }
        if (highlight) {
            val bw = if (!standing && (dir == 1 || dir == 3)) uu * 64f else uu * 51f
            val bh = if (!standing && (dir == 1 || dir == 3)) uu * 64f else uu * 76f
            Canvas(Modifier.size(bw, bh).graphicsLayer { this.alpha = alpha }) {
                val r = size.width * 0.14f
                drawRoundRect(TEN_HL.copy(alpha = 0.30f), Offset.Zero, size, CornerRadius(r, r))
                drawRoundRect(TEN_HL, Offset.Zero, size, CornerRadius(r, r), style = Stroke(size.width * 0.06f))
            }
        }
    }
}

/**
 * 副露牌（APK 小牌合成；[claimed]=吃碰杠得来的供牌 → 整牌侧翻 90°，日麻规则标记）。
 * 占位：未翻 = MjSmallTile 盒（dir0/2 40.8x60.8u，dir1/3 51.2x51.2u）；翻 = 交换宽高。
 */
@Composable
fun TenMeldTile(
    code: Int, dir: Int, u: Dp,
    modifier: Modifier = Modifier,
    claimed: Boolean = false,
    scale: Float = 1f
) {
    val uu = u * scale
    if (!claimed) {
        MjSmallTile(code, dir, uu, modifier)
    } else if (dir == 1 || dir == 3) {
        // 基盒本就是方盒（51.2x51.2u），侧翻只改刻字朝向
        val base = uu * 64f * 0.8f
        Box(modifier.size(base, base)) {
            Box(
                Modifier
                    .size(base, base)
                    .graphicsLayer { rotationZ = -90f }
            ) { MjSmallTile(code, dir, uu) }
        }
    } else {
        // 竖盒 40.8x60.8 → 横盒 60.8x40.8
        val bw = uu * 51f * 0.8f
        val bh = uu * 76f * 0.8f
        Box(modifier.size(bh, bw)) {
            Box(
                Modifier
                    .size(bw, bh)
                    .align(Alignment.Center)
                    .graphicsLayer { rotationZ = -90f }
            ) { MjSmallTile(code, dir, uu) }
        }
    }
}

/** 癞子（宝牌）小红标——原 APK 财神标记素材 */
@Composable
fun LaiziBadge(w: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.mj_mark_caishen),
        contentDescription = "癞子标记",
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .padding(end = w * 0.02f, top = w * 0.03f)
            .size(w * 0.26f, w * 0.26f * 59f / 30f)
    )
}
