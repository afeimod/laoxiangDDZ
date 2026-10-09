package com.laoxiang.ddz.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.MjTile

/**
 * 麻将渲染 v5 —— 完全按参考 APK（net.joygames.chinamj）素材与合成方式 1:1 移植：
 * - 大牌（手牌，DrawMj）  = psmj0 白胚 + psmj 面 89x128 原尺寸叠加
 * - 桌牌（牌河/副露，DrawCCMj）= 白胚 tablemj*_0 1:1 + 面 0.573 缩放旋转
 * - 小牌（他家手牌/副露，drawSmallMj）= 白胚 0.8 + 面 0.4584 旋转
 * - 四方向旋转角：下 0° / 右 270° / 上 180° / 左 90°（与 APK 字节码一致）
 * - 他家手牌背面：cemian2/3/4（右/上/左）
 * 所有偏移均取自反编译得到的常量（1280 设计空间，[u] 为每设计像素的 Dp 数）。
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
 * ⚠ 本表按【当前素材包 psmj1..34】逐张重测（PIL alpha 通道墨迹 bbox）：
 *   旧表（v1.4.11 第三次复核）在筒/万字上偏差达 2~4.5u（如一筒 74.0 vs 实测 69.5），
 *   是横躺牌刻字明显不居中的主因之一，已全部替换为本实测值。
 */
private val FACE_CC = floatArrayOf(
    43.5f, 74.5f, 43.0f, 72.0f, 43.0f, 70.5f, 43.0f, 71.5f,
    43.0f, 71.0f, 43.0f, 69.5f, 43.0f, 69.0f, 43.0f, 72.0f,
    43.0f, 70.5f, 43.5f, 69.5f, 43.0f, 69.0f, 43.0f, 69.5f,
    43.0f, 69.5f, 43.5f, 69.5f, 43.0f, 69.5f, 43.5f, 69.0f,
    43.5f, 69.0f, 43.0f, 69.5f, 43.0f, 73.5f, 43.5f, 70.0f,
    43.5f, 70.5f, 43.0f, 72.0f, 43.5f, 71.0f, 43.0f, 69.5f,
    43.0f, 71.5f, 43.5f, 70.5f, 43.0f, 71.0f, 45.0f, 70.0f,
    45.0f, 69.5f, 44.0f, 70.0f, 43.5f, 70.0f, 43.5f, 69.0f,
    44.5f, 69.5f, 43.5f, 69.0f
)

/**
 * 桌牌/小牌四方向刻字目标点（盒坐标）——【白胚素材实测白面中心】：
 *   立牌 tablemjh0/nh0 白面 bbox=(1,2,49,62) → 中心 (25.0,32.0)；
 *   横躺 tablemjwh0/eh0 白面 bbox=(1,13,62,51) → 中心 (31.5,32.0)；
 *   小牌 = 同素材 ×0.8 → 立牌 (20.0,25.6) / 横躺 (25.2,25.6)。
 * ⚠ 不再使用 APK 字节码目标点：那套常量对应 APK 自带素材的白面位置，
 *   而本项目素材来自用户素材包（裁切不同），字节码目标会把墨迹放到白面中心
 *   右下方 1.4~1.6u 处（用户反馈"横向刻字未居中"的主因之二）。
 * PIL 全流程仿真（合成图 vs 牌体逐像素差分）验证：新目标下四方向墨迹落点
 * 与白面中心偏差 ≤0.6u（旧组合最大 ~6u）。
 */
private val TABLE_CC_X = floatArrayOf(25.0f, 31.5f, 25.0f, 31.5f)
private val TABLE_CC_Y = floatArrayOf(32.0f, 32.0f, 32.0f, 32.0f)
private val SMALL_CC_X = floatArrayOf(20.0f, 25.2f, 20.0f, 25.2f)
private val SMALL_CC_Y = floatArrayOf(25.6f, 25.6f, 25.6f, 25.6f)

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
 * 占位：dir0/2 = 51x76u，dir1/3 = 64x64u；刻字按 APK 常量偏移（部分向上/侧溢出属原版行为）。
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
 * 小牌合成（APK drawSmallMj 1:1）：白胚 0.8 + 面 0.4584 缩放旋转（他家副露）。
 * 偏移改用白面对齐法（同 MjTableTile）：面内容中心落牌体白面中心（小牌白心 ×0.8）。
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
    // 逐张对中（同 MjTableTile）：小牌目标点 = APK drawSmallMj 字节码派生（SMALL_CC_*）
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

/** 他家手牌背面：右家 cemian2 / 上家 cemian3 / 左家 cemian4（APK w[1]/w[2]/w[3]） */
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

/** 明杠压杆（APK v[0]=cc2 盖在暗杠上）/ 暗杠背杆 */
@Composable
internal fun MjGangCover(u: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.mjcc2),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.size(u * 48f, u * 72f)
    )
}

/** 副露暗杠背杆（APK：上家=v[0] cc2 1:1 48x72；右/左家=v[1] cc1 0.9缩放 60x60→54 方） */
@Composable
internal fun MjMeldBack(top: Boolean, u: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(if (top) R.drawable.mjcc2 else R.drawable.mjcc1),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.size(
            if (top) u * 48f else u * 60f * 0.9f,
            if (top) u * 72f else u * 60f * 0.9f
        )
    )
}

/** 发牌盖牌（APK aa=gaipai 89x123 绿背） */
@Composable
internal fun MjDealCover(u: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.mjdeal_cover),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.size(u * 89f, u * 123f)
    )
}
