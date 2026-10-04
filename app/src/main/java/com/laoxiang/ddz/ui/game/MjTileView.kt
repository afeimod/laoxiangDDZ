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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.MjTile

/**
 * 麻将牌渲染 v4 —— 用户素材原样合成贴图（res/drawable-nodpi/mj_*.png，178x246@RGBA）。
 * 牌体 = 素材 psmmj0 白色立体牌体（含顶部绿边），刻字 = 素材 psmj1..34 原样叠加。
 * [w] 为牌宽，高 = w × 1.38202（与贴图等比 89:123）。
 */

/** code(0..33) -> 牌面贴图：0..8 万 / 9..17 筒 / 18..26 条 / 27..33 东南西北中發白 */
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

/** 单张麻将牌（tile=null 或 faceUp=false 画牌背） */
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
            // 财神标记（素材：mj_mark_caishen 30x59，竖排金字）叠在牌右上角
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
