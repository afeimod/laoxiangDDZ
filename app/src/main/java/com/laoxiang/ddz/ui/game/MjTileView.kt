package com.laoxiang.ddz.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.MjTile

/**
 * 麻将牌渲染 v2 —— 预渲染立体贴图（res/drawable-nodpi/mj_*.png，152x200@RGBA）。
 * 立体要素全部烘焙在贴图内：柔和投影 / 祖母绿侧身厚度 / 象牙面渐变+斜面倒角 /
 * 顶部釉光 / 刻字压印（暗字 + 右下受光边）/ 竹节·筒环高光。
 * [w] 为牌宽，高 = w × 1.32（与贴图等比 152:200）。
 */

/** code(0..33) -> 牌面贴图：0..8 万 / 9..17 筒 / 18..26 条 / 27..33 东南西北中發白 */
private fun faceRes(code: Int): Int = when (code) {
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
            // 癞子金点标记（沿用 v1 位置与比例）
            Canvas(Modifier.size(w, h)) {
                val rr = size.width * 0.2f
                val c = Offset(size.width - rr - 2f, rr + 2f)
                drawCircle(Color(0xFFFFC107), rr, c)
                drawCircle(Color(0xFF5D4037), rr * 0.55f, c)
            }
        }
    }
}
