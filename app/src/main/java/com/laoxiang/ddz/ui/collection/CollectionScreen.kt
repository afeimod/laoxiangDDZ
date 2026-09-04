package com.laoxiang.ddz.ui.collection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.PdkMode
import com.laoxiang.ddz.ui.theme.Gold

/**
 * 棋牌合集：斗地主 / 跑得快（三人·四人）/ 更多棋牌敬请期待。
 */
@Composable
fun CollectionScreen(
    onBack: () -> Unit,
    onPlayDdz: () -> Unit,
    onPlayPdk: (PdkMode) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF12305C))
    ) {
        Image(
            painter = painterResource(R.drawable.bg_lobby),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x59001B33), Color(0xCC00121F), Color(0x59001B33))
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 26.dp, vertical = 10.dp)
        ) {
            // 顶栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x33FFFFFF))
                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(13.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "棋牌合集",
                        fontSize = 23.sp, fontWeight = FontWeight.Black, color = Color.White
                    )
                    Text(
                        "老乡们爱玩的，都在这张桌上",
                        fontSize = 10.sp, color = Color(0x99D7E7FA)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GameCard(
                    icon = {
                        Image(
                            painter = painterResource(R.drawable.fan_cards),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(62.dp)
                        )
                    },
                    title = "斗地主",
                    badge = "最热门",
                    badgeColor = Color(0xFFC62828),
                    desc = "经典三人 · 智能电脑 · 叫抢地主",
                    onClick = onPlayDdz
                )
                GameCard(
                    icon = {
                        Image(
                            painter = painterResource(R.drawable.pdk_icon),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(62.dp)
                        )
                    },
                    title = "跑得快 · 三人",
                    badge = "新上架",
                    badgeColor = Color(0xFF2E7D32),
                    desc = PdkMode.THREE.desc + " · 有牌必压",
                    onClick = { onPlayPdk(PdkMode.THREE) }
                )
                GameCard(
                    icon = {
                        Image(
                            painter = painterResource(R.drawable.pdk_icon),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(62.dp)
                        )
                    },
                    title = "跑得快 · 四人",
                    badge = "新上架",
                    badgeColor = Color(0xFF2E7D32),
                    desc = PdkMode.FOUR.desc + " · 先跑为赢",
                    onClick = { onPlayPdk(PdkMode.FOUR) }
                )
                // 敬请期待
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(0.55f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x26FFFFFF))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🀄", fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "更多棋牌 · 备货中",
                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xB3FFFFFF)
                        )
                        Text(
                            "掼蛋 / 升级 / 锄大地……老家牌桌上慢慢添",
                            fontSize = 10.sp, color = Color(0x80FFFFFF)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun GameCard(
    icon: @Composable () -> Unit,
    title: String,
    badge: String,
    badgeColor: Color,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(7.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF2FFFFFF))
            .border(1.5.dp, Color(0x33C9A25E), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFFFFF3E0), Color(0xFFFFE0B2)))
                ),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 18.sp, fontWeight = FontWeight.Black,
                    color = Color(0xFF123A6E)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        badge,
                        fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(desc, fontSize = 11.sp, color = Color(0xFF6B83A3))
        }
        Text(
            "开打 ▸",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE8871E)
        )
    }
}
