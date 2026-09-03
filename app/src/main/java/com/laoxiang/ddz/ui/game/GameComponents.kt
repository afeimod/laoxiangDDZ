package com.laoxiang.ddz.ui.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.data.Card
import com.laoxiang.ddz.data.Deck
import com.laoxiang.ddz.data.LastActionType
import com.laoxiang.ddz.data.SeatView
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.common.AvatarImageRes
import com.laoxiang.ddz.ui.theme.*

// ------------------------------------------------ 牌面资源

/** 卡牌图宽高比（500x726） */
const val CARD_RATIO = 0.689f

private val cardResCache = HashMap<String, Int>()

fun cardRes(card: Card, context: android.content.Context): Int {
    val name = Deck.resName(card)
    return cardResCache.getOrPut(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}

/** 单张牌 */
@Composable
fun PokerCard(
    card: Card,
    width: Dp,
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val res = remember(card.id) { cardRes(card, ctx) }
    val lift by animateFloatAsState(
        targetValue = if (raised) 1f else 0f,
        animationSpec = tween(120),
        label = "lift"
    )
    val shape = RoundedCornerShape(width / 8)
    Box(
        modifier
            .width(width)
            .aspectRatio(CARD_RATIO)
            .graphicsLayer {
                translationY = -lift * width.toPx() * 0.28f
                shadowElevation = if (raised) 12f else 4f
            }
            .clip(shape)
            .background(Color.White)
            .border(0.5.dp, Color(0x338D6E63), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        if (res != 0) {
            Image(
                painter = painterResource(res),
                contentDescription = card.displayLabel,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    card.displayLabel,
                    color = if (card.suit.isRed) ChineseRed else Ink,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** 牌背 */
@Composable
fun CardBack(width: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.card_back),
        contentDescription = "牌背",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(width / 8))
            .border(0.5.dp, Color(0x33600000), RoundedCornerShape(width / 8))
    )
}

/** 出牌区：一排小牌（宽度自适应，牌再多也不超出可用宽度） */
@Composable
fun PlayedCards(
    cards: List<Card>,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    overlap: Boolean = true
) {
    if (cards.isEmpty()) return
    BoxWithConstraints(modifier) {
        val n = cards.size
        val w: Dp
        val spacing: Dp
        if (overlap) {
            // 每张露出 58%，相邻重叠 42%（spacedBy 负间距语义：step = w*(1-frac)）
            val visible = 0.58f
            val k = 1f + (n - 1) * visible
            w = cardWidth.coerceAtMost(maxWidth / k)
            spacing = -(w * (1f - visible))
        } else {
            w = cardWidth.coerceAtMost((maxWidth - 3.dp * (n - 1)) / n)
            spacing = 3.dp
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            cards.forEach { c ->
                PokerCard(c, w)
            }
        }
    }
}

// ------------------------------------------------ 座位块

/** 对手信息块（对标欢乐斗地主：头像 + 剩牌数蓝块并排，名字在下，含地主帽/聊天气泡） */
@Composable
fun OpponentBlock(
    seat: SeatView,
    showCards: Boolean,
    chatText: String?,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 26.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 聊天气泡
        chatText?.let { t ->
            Bubble(t)
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AvatarImageRes(
                    // 非地主=默认老乡头像；当地主后自动换成富翁地主头像
                    tableAvatarRes(seat), 50.dp,
                    Modifier
                        .border(
                            2.5.dp,
                            when {
                                seat.isTurn -> Color(0xFFFFC107)
                                seat.isLandlord -> Gold
                                else -> Color(0x66FFFFFF)
                            }, CircleShape
                        )
                )
                if (seat.isLandlord) {
                    // 地主小帽标
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-9).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFD4A24E))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("地主", fontSize = 9.sp, color = Color(0xFF4E2600), fontWeight = FontWeight.Black)
                    }
                }
            }
            if (showCards) {
                Spacer(Modifier.width(7.dp))
                CountTile(seat.handCount)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            seat.name,
            fontSize = 11.sp,
            color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
            maxLines = 1
        )
    }
}

/** 剩牌数蓝块（对标欢乐斗地主的「17」方块） */
@Composable
private fun CountTile(n: Int) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .shadow(3.dp, shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF4E7CD0), Color(0xFF2E55A0)))
            )
            .border(1.dp, Color(0x88FFFFFF), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            "$n",
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
    }
}

/** 聊天气泡 */
@Composable
fun Bubble(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2FFFFFF))
            .border(1.dp, Gold, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 12.sp, color = Ink, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** "要不起" / "不叫" 状态 */
@Composable
fun PassTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x995D4037))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = Color(0xFFFFE0B2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------ 记牌器

private val RANK_LABELS = mapOf(
    3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8", 9 to "9", 10 to "10",
    11 to "J", 12 to "Q", 13 to "K", 14 to "A", 15 to "2", 16 to "王", 17 to "王"
)

/** 记牌器面板 */
@Composable
fun CardCounterPanel(
    counts: Map<Int, Int>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB3163A6E))
            .border(1.dp, Color(0x66A8C8F0), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Text("记牌器（外界剩余）", fontSize = 10.sp, color = Color(0xCCD7E7FA))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (3..15).forEach { r ->
                val left = counts[r] ?: 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        RANK_LABELS[r] ?: "",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (left > 0) Color(0xFFFFE082) else Color(0x55FFE082)
                    )
                    Text(
                        "$left",
                        fontSize = 9.sp,
                        color = if (left > 0) Color.White else Color(0x55FFFFFF)
                    )
                }
            }
            val jokers = (counts[16] ?: 0) + (counts[17] ?: 0)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("王", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFE082))
                Text("$jokers", fontSize = 9.sp, color = Color.White)
            }
        }
    }
}

// ------------------------------------------------ 快捷聊天

val CHAT_PHRASES = listOf(
    "快点吧，我等到花儿都谢了！",
    "大你！",
    "你的牌打得太好了！",
    "不要走，决战到天亮！",
    "大家好，很高兴见到各位老乡~",
    "炸得漂亮！",
    "顶住，农民兄弟！",
    "咋又是我当地主…"
)

/** 快捷聊天面板 */
@Composable
fun QuickChatPanel(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(Color(0xF2FFF3E0))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("快捷喊话", fontWeight = FontWeight.Bold, color = DeepRed)
            TextButton(onClick = onDismiss) { Text("收起", color = DeepRed) }
        }
        CHAT_PHRASES.chunked(2).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { phrase ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFEDD9B8))
                            .border(1.dp, Color(0xFFC9A25E), RoundedCornerShape(10.dp))
                            .clickable {
                                onSend(phrase)
                                onDismiss()
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(phrase, fontSize = 13.sp, color = Ink, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
