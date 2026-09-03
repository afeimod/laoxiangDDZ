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
import com.laoxiang.ddz.ui.theme.*

// ------------------------------------------------ 牌面资源

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
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f),
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

/** 出牌区：一排小牌 */
@Composable
fun PlayedCards(
    cards: List<Card>,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    overlap: Boolean = true
) {
    if (cards.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (overlap) (-cardWidth * 0.42f) else 3.dp)
    ) {
        items(cards, key = { it.id }) { c ->
            PokerCard(c, cardWidth)
        }
    }
}

// ------------------------------------------------ 座位块

/** 对手信息块（头像 / 名字 / 剩牌数 / 地主帽 / 聊天气泡） */
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
        Box {
            AvatarImage(
                seat.avatar, 46.dp,
                Modifier
                    .border(
                        2.5.dp,
                        when {
                            seat.isTurn -> Color(0xFFFFC107)
                            seat.isLandlord -> Gold
                            else -> Color.Transparent
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
        Spacer(Modifier.height(3.dp))
        Text(
            seat.name,
            fontSize = 11.sp,
            color = if (seat.isTurn) Color(0xFFFFE082) else Color(0xCCFFFFFF),
            maxLines = 1
        )
        if (showCards) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardBack(cardWidth * 0.7f)
                Text(
                    " ${seat.handCount}",
                    fontSize = 13.sp,
                    color = Color(0xFFFFE082),
                    fontWeight = FontWeight.Bold
                )
            }
        }
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
            .background(Color(0x99380D08))
            .border(1.dp, Color(0x66D4A24E), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Text("记牌器（外界剩余）", fontSize = 10.sp, color = Color(0xCCFFD9A0))
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

// ------------------------------------------------ 底牌区

@Composable
fun BottomCardsBar(
    bottom: List<Card>,
    multiplier: Int,
    landlordName: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x88261007))
            .border(1.dp, Color(0x66D4A24E), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("底牌", fontSize = 11.sp, color = Color(0xCCFFD9A0))
        Spacer(Modifier.width(6.dp))
        bottom.forEach { c ->
            PokerCard(c, 26.dp)
            Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "×$multiplier",
            color = Gold,
            fontWeight = FontWeight.Black,
            fontSize = 17.sp
        )
        if (landlordName != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                "地主 $landlordName",
                fontSize = 11.sp,
                color = Color(0xCCFFD9A0),
                maxLines = 1
            )
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
