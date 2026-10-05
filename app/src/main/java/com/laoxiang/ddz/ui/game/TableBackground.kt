package com.laoxiang.ddz.ui.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 牌桌背景管理：内置 4 款预设 + 相册自定义图片。
 * 选择结果持久化在 Prefs.tableBg；自定义图片压缩后存 filesDir/table_bg_custom.jpg。
 */
object TableBg {

    /** key → 名称（设置面板展示用；default 为经典深蓝） */
    val PRESETS = listOf(
        "default" to "经典深蓝",
        "green" to "墨绿毛毡",
        "red" to "喜庆红金",
        "purple" to "紫夜星辉"
    )

    fun resFor(key: String): Int = when (key) {
        "green" -> R.drawable.bg_table_green
        "red" -> R.drawable.bg_table_red
        "purple" -> R.drawable.bg_table_purple
        else -> R.drawable.bg_game_landscape
    }

    fun customFile(ctx: Context): File = File(ctx.filesDir, "table_bg_custom.jpg")

    fun hasCustom(ctx: Context): Boolean = customFile(ctx).let { it.exists() && it.length() > 0 }
}

/**
 * 项目专属刻字水印（"老乡XX"凹陷风），供各玩法桌面复用。
 * [engraving]：游戏专属刻字（如"老乡跑得快"），覆盖在背景之上。
 */
@Composable
fun TableEngraving(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // 双层错位营造"刻字"凹陷感：暗影层 + 高光层 + 主字层
        Text(
            text,
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 6.sp,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0x66000000),
                    offset = Offset(3f, 3f),
                    blurRadius = 6f
                )
            ),
            color = Color(0x2EFFFFFF),
            modifier = Modifier.graphicsLayer {
                rotationZ = -6f
                scaleX = 1.06f; scaleY = 1.06f
            }
        )
        Text(
            text,
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 6.sp,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0x4D000000),
                    offset = Offset(0f, 4f),
                    blurRadius = 10f
                )
            ),
            color = Color(0x30FFFFFF),
            modifier = Modifier.graphicsLayer { rotationZ = -6f }
        )
    }
}

/**
 * 牌桌背景图层。
 * key=custom 且本地有图时异步解码加载（超大相册图按 ~1920 降采样），
 * 加载完成前先给深蓝底色兜底；其余 key 直接用内置图。
 */
@Composable
fun TableBackground(bgKey: String, modifier: Modifier = Modifier, engraving: String? = null) {
    val ctx = LocalContext.current
    if (bgKey == "custom" && TableBg.hasCustom(ctx)) {
        // 文件时间戳作为刷新锚点：重新选图后能立即换新
        val stamp = remember(bgKey) { TableBg.customFile(ctx).lastModified() }
        var bmp by remember(stamp) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(stamp) {
            bmp = withContext(Dispatchers.IO) { decodeScaled(TableBg.customFile(ctx)) }
        }
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        } else {
            Box(modifier.background(Color(0xFF1B3C6E)))
        }
    } else {
        Image(
            painter = painterResource(TableBg.resFor(bgKey)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
    if (!engraving.isNullOrBlank()) {
        TableEngraving(engraving, modifier)
    }
}

/** 按最长边 ~1920 解码，避免超大相册图撑爆内存 */
private fun decodeScaled(f: File): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
    BitmapFactory.decodeFile(
        f.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
} catch (_: Throwable) {
    null
}
