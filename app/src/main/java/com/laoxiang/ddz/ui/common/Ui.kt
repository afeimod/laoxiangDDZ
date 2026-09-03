package com.laoxiang.ddz.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.ui.theme.*

/** 头像资源缓存 */
private val avatarResCache = HashMap<String, Int>()

fun avatarRes(avatar: Int, context: android.content.Context): Int {
    val idx = avatar.coerceIn(1, 12).toString().padStart(2, '0')
    val name = "avatar_$idx"
    return avatarResCache.getOrPut(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}

/** 圆形头像 */
@Composable
fun AvatarImage(avatar: Int, size: Dp, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = remember(avatar) { avatarRes(avatar, ctx) }
    if (res != 0) {
        Image(
            painter = painterResource(res),
            contentDescription = "头像",
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Gold),
            contentAlignment = Alignment.Center
        ) {
            Text("老乡", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 红金主按钮（onClick 必须是最后参数以支持尾随 lambda） */
@Composable
fun GoldButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = Color.White,
            disabledContainerColor = Color(0x55C62828),
            disabledContentColor = Color(0xAAFFFFFF)
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** 描边次按钮（onClick 必须是最后参数以支持尾随 lambda） */
@Composable
fun OutlineGoldButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Gold,
            disabledContentColor = Color(0x66D4A24E)
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (enabled) Gold else Color(0x66D4A24E)),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 金色描边卡片 */
@Composable
fun GoldCard(
    modifier: Modifier = Modifier,
    corner: Dp = 18.dp,
    background: Color = Color(0xB3FFFFFF),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.verticalGradient(listOf(background, Color(0x99FFEFD8)))
            )
            .border(1.5.dp, Color(0xFFC9A25E), RoundedCornerShape(corner))
            .padding(16.dp),
        content = content
    )
}

/** 顶栏标题（含小印章） */
@Composable
fun TitleBar(title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = Color(0xFFFFE9C4),
            fontSize = 30.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.width(10.dp))
        Image(
            painter = painterResource(R.drawable.seal_mark),
            contentDescription = "老乡斗地主印章",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
        )
    }
    if (subtitle != null) {
        Text(subtitle, color = Color(0xCCFFD9A0), fontSize = 13.sp)
    }
}

/** 深红金渐变 */
fun festiveGradient(): Brush = Brush.verticalGradient(
    listOf(Color(0xFFB71C1C), Color(0xFF7A0F0F), Color(0xFF571010))
)
