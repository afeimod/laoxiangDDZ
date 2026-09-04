package com.laoxiang.ddz.ui.lobby

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.game.GameViewModel
import com.laoxiang.ddz.ui.game.TableBg
import com.laoxiang.ddz.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 大厅主页（v13 重设计）：浅蓝天空场景，横屏单套布局。
 * 顶栏（玩家胶囊+品牌+设置）→ 三大方格：快速开始 / 本地联机 / 棋牌合集；
 * 电脑难度在设置弹层中调整。
 */
@Composable
fun LobbyScreen(
    gameVm: GameViewModel,
    onSingle: (Int) -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onCollection: () -> Unit
) {
    val prefs = gameVm.prefs
    var nickname by remember { mutableStateOf(prefs.nickname) }
    var avatar by remember { mutableStateOf(prefs.avatar) }
    var soundOn by remember { mutableStateOf(prefs.soundEnabled) }
    var musicOn by remember { mutableStateOf(prefs.musicEnabled) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showNicknameEditor by remember { mutableStateOf(false) }
    var showNetChooser by remember { mutableStateOf(false) }
    var nicknameDraft by remember { mutableStateOf("") }
    var aiLevel by remember { mutableStateOf(prefs.aiLevel) }
    // 牌桌背景选择（v12：预设 4 款 + 相册自定义）
    var tableBg by remember { mutableStateOf(prefs.tableBg) }
    var customBgStamp by remember { mutableStateOf(0L) }   // 自定义图刷新锚点
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickBgImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { saveCustomBg(context, uri) }
            if (ok) {
                tableBg = "custom"
                prefs.tableBg = "custom"
                customBgStamp = System.currentTimeMillis()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF6CB4F3))
    ) {
        // 全屏浅蓝天空主城背景
        Image(
            painter = painterResource(R.drawable.bg_lobby),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 顶部微亮 / 底部微沉
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x26FFFFFF), Color(0x00FFFFFF), Color(0x2E0E3A6E))
                    )
                )
        )

        // ================= 横屏布局（App 已锁横屏，单套布局） =================
        Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerCapsule(
                        avatar = avatar, nickname = nickname,
                        beans = prefs.beans, wins = prefs.wins, losses = prefs.losses,
                        onEditName = { showNicknameEditor = true },
                        onPickAvatar = { showAvatarPicker = true }
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "老乡斗地主",
                            fontSize = 22.sp, fontWeight = FontWeight.Black,
                            color = Color.White,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color(0xFF12305C),
                                    offset = androidx.compose.ui.geometry.Offset(1f, 2f),
                                    blurRadius = 5f
                                )
                            )
                        )
                        Text("棋牌合集 · 局域网联机", fontSize = 9.sp, color = Color(0xCCE8F2FF))
                    }
                    Spacer(Modifier.weight(1f))
                    SettingsGear { showSettings = true }
                }

                Spacer(Modifier.weight(0.55f))

                // ---- 三大方格：快速开始 / 本地联机 / 棋牌合集
                val config = LocalConfiguration.current
                val cellH = (config.screenHeightDp.dp * 0.42f).coerceIn(190.dp, 280.dp)
                val levelLabel = listOf("简单", "中等", "困难")[aiLevel.coerceIn(0, 2)]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ① 快速开始（斗地主）
                    HomeCell(
                        modifier = Modifier.weight(1f),
                        height = cellH,
                        background = Brush.verticalGradient(
                            listOf(Color(0xFFFFC94D), Color(0xFFF0821E))
                        ),
                        borderColor = Color(0x66FFFFFF),
                        title = "快速开始",
                        titleColor = Color.White,
                        subtitle = "斗地主 · 电脑$levelLabel",
                        subColor = Color(0xCFFFFFF3E0),
                        badge = "一键开局",
                        onClick = { onSingle(aiLevel) }
                    ) {
                        Image(
                            painter = painterResource(R.drawable.fan_cards),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(cellH * 0.42f)
                        )
                    }

                    // ② 本地联机
                    HomeCell(
                        modifier = Modifier.weight(1f),
                        height = cellH,
                        background = Brush.verticalGradient(
                            listOf(Color(0xF7FFFFFF), Color(0xFFE8F2FF))
                        ),
                        borderColor = Color(0xFFBFD9F5),
                        title = "本地联机",
                        titleColor = Color(0xFF14427E),
                        subtitle = "开一桌 · 加入牌局",
                        subColor = Color(0xFF6B83A3),
                        badge = "面对面",
                        onClick = { showNetChooser = true }
                    ) {
                        Box(
                            Modifier
                                .size(cellH * 0.34f)
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF5B8BE8), Color(0xFF3A63C0))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "⇆",
                                fontSize = (cellH.value * 0.14f).sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }

                    // ③ 棋牌合集
                    HomeCell(
                        modifier = Modifier.weight(1f),
                        height = cellH,
                        background = Brush.verticalGradient(
                            listOf(Color(0xFFF7FFFFFF), Color(0xFFFFF1DC))
                        ),
                        borderColor = Color(0xFFC9A25E),
                        title = "棋牌合集",
                        titleColor = Color(0xFF8E1414),
                        subtitle = "跑得快 · 持续上新",
                        subColor = Color(0xFF9A7B52),
                        badge = "NEW",
                        onClick = onCollection
                    ) {
                        Image(
                            painter = painterResource(R.drawable.pdk_icon),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(cellH * 0.42f)
                        )
                    }
                }

                Spacer(Modifier.weight(0.55f))
            }

        // ---------- 昵称修改弹层
        if (showNicknameEditor) {
            AlertDialog(
                onDismissRequest = { showNicknameEditor = false },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.nickname = nicknameDraft
                        nickname = nicknameDraft
                        showNicknameEditor = false
                    }) {
                        Text("定了", color = DeepRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNicknameEditor = false }) {
                        Text("算了", color = Color(0xFF8A6A45))
                    }
                },
                title = { Text("改个诨名", fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = nicknameDraft,
                        onValueChange = { nicknameDraft = it.take(8) },
                        label = { Text("最多八个字") },
                        singleLine = true
                    )
                }
            )
        }

        // ---------- 头像选择弹层
        if (showAvatarPicker) {
            AlertDialog(
                onDismissRequest = { showAvatarPicker = false },
                confirmButton = {
                    TextButton(onClick = { showAvatarPicker = false }) {
                        Text("定了", color = DeepRed, fontWeight = FontWeight.Bold)
                    }
                },
                title = { Text("挑个形象", fontWeight = FontWeight.Bold) },
                text = {
                    val names = mapOf(
                        1 to "铁柱", 2 to "二狗", 3 to "翠花", 4 to "小紫侠", 5 to "老烟枪",
                        6 to "大喇叭", 7 to "财神爷", 8 to "羞答答", 9 to "俏闺秀",
                        10 to "王大厨", 11 to "胖财主", 12 to "金元宝"
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.height(340.dp)
                    ) {
                        items((1..12).toList()) { idx ->
                            val sel = avatar == idx
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    avatar = idx
                                    prefs.avatar = idx
                                }
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .then(
                                            if (sel) Modifier.border(
                                                2.5.dp, Gold, RoundedCornerShape(12.dp)
                                            ) else Modifier
                                        )
                                        .padding(2.dp)
                                ) {
                                    AvatarImage(idx, 56.dp)
                                }
                                Text(
                                    names[idx] ?: "",
                                    fontSize = 11.sp,
                                    color = if (sel) DeepRed else Color(0xFF8A6A45)
                                )
                            }
                        }
                    }
                }
            )
        }

        // ---------- 本地联机选择弹层（开一桌 / 加入牌局）
        if (showNetChooser) {
            AlertDialog(
                onDismissRequest = { showNetChooser = false },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showNetChooser = false }) {
                        Text("算了", color = Color(0xFF8A6A45))
                    }
                },
                title = { Text("本地联机", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LanEntry(
                            "开一桌 · 当房主（同一 WiFi）",
                            Modifier.fillMaxWidth(),
                            height = 52.dp
                        ) {
                            showNetChooser = false
                            onHost()
                        }
                        LanEntry(
                            "串门 · 加入附近的牌局",
                            Modifier.fillMaxWidth(),
                            height = 52.dp
                        ) {
                            showNetChooser = false
                            onJoin()
                        }
                        Text(
                            "两台手机连同一个 WiFi / 热点就能开打",
                            fontSize = 10.sp,
                            color = Color(0x994A6285)
                        )
                    }
                }
            )
        }

        // ---------- 设置弹层（难度 + 声音 + 牌桌背景）
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("好嘞", color = DeepRed, fontWeight = FontWeight.Bold)
                    }
                },
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        // 电脑难度（v13 从主页移入设置）
                        Text("电脑难度", fontWeight = FontWeight.Bold, color = Color(0xFF123A6E))
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("简单", "中等", "困难").forEachIndexed { lv, label ->
                                DifficultyPill(
                                    label = label,
                                    selected = aiLevel == lv,
                                    modifier = Modifier.weight(1f),
                                    compact = true
                                ) {
                                    aiLevel = lv
                                    prefs.aiLevel = lv
                                }
                            }
                        }
                        Text(
                            "影响斗地主与跑得快的电脑水平",
                            fontSize = 9.sp,
                            color = Color(0x994A6285),
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0x14000000))
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("出牌音效")
                            Switch(
                                checked = soundOn,
                                onCheckedChange = {
                                    soundOn = it
                                    gameVm.soundSettings(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = ChineseRed
                                )
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("背景音乐")
                            Switch(
                                checked = musicOn,
                                onCheckedChange = {
                                    musicOn = it
                                    gameVm.musicSettings(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = ChineseRed
                                )
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0x14000000))
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("牌桌背景", fontWeight = FontWeight.Bold, color = Color(0xFF123A6E))
                        Text(
                            "左右滑动查看更多 →",
                            fontSize = 9.sp,
                            color = Color(0x994A6285)
                        )
                        Spacer(Modifier.height(8.dp))
                        // 可左右滑动的背景选择条（v13：解决弹层内显示不完整）
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(TableBg.PRESETS) { _, (key, label) ->
                                val sel = tableBg == key
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        tableBg = key
                                        prefs.tableBg = key
                                    }
                                ) {
                                    Box(
                                        Modifier
                                            .width(58.dp)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .border(
                                                if (sel) 2.dp else 1.dp,
                                                if (sel) Gold else Color(0xFFCBDEF2),
                                                RoundedCornerShape(7.dp)
                                            )
                                    ) {
                                        Image(
                                            painter = painterResource(TableBg.resFor(key)),
                                            contentDescription = label,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        label,
                                        fontSize = 8.sp,
                                        maxLines = 1,
                                        color = if (sel) DeepRed else Color(0xFF8A6A45),
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                            // 自定义图片块（相册选图，压缩后持久化）
                            item {
                                val selCustom = tableBg == "custom"
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { pickBgImage.launch("image/*") }
                                ) {
                                    Box(
                                        Modifier
                                            .width(58.dp)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(Color(0xFFF1F6FC))
                                            .border(
                                                if (selCustom) 2.dp else 1.dp,
                                                if (selCustom) Gold else Color(0xFFCBDEF2),
                                                RoundedCornerShape(7.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val thumb = remember(customBgStamp) {
                                            loadBgThumb(TableBg.customFile(context))
                                        }
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb.asImageBitmap(),
                                                contentDescription = "自定义背景",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Text(
                                                "＋图",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4A6285)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "自定义",
                                        fontSize = 8.sp,
                                        maxLines = 1,
                                        color = if (selCustom) DeepRed else Color(0xFF8A6A45),
                                        fontWeight = if (selCustom) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点「自定义」从相册选图当牌桌；重新选择可更换",
                            fontSize = 9.sp,
                            color = Color(0x994A6285)
                        )
                    }
                }
            )
        }
    }
}

// ================================================================= 共享组件

/** 顶部玩家信息胶囊：头像 + 昵称✎ + 豆数 + 战绩 */
@Composable
private fun PlayerCapsule(
    avatar: Int,
    nickname: String,
    beans: Int,
    wins: Int,
    losses: Int,
    onEditName: () -> Unit,
    onPickAvatar: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xCC14427E))
            .border(1.5.dp, Color(0xFF9CC4EE), RoundedCornerShape(26.dp))
            .clickable(onClick = onEditName)
            .padding(start = 5.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(avatar, 46.dp, Modifier.clickable(onClick = onPickAvatar))
        Spacer(Modifier.width(9.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    nickname.ifBlank { "无名老乡" },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 1
                )
                Spacer(Modifier.width(4.dp))
                Text("✎", fontSize = 11.sp, color = Color(0xCCBFD9F5))
            }
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFFFFD766), Color(0xFFF5921E))
                            )
                        )
                        .border(0.8.dp, Color(0xFFB07A1E), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("豆", fontSize = 8.sp, color = Color(0xFF6D3A00), fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "$beans",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD766)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${wins}胜${losses}负",
                    fontSize = 11.sp, color = Color(0xCCD7E7FA)
                )
            }
        }
    }
}

/** 设置圆钮 */
@Composable
private fun SettingsGear(onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0xE6FFFFFF))
            .border(1.5.dp, Color(0xFFBFD9F5), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("⚙", fontSize = 19.sp, color = Color(0xFF14427E))
    }
}

/** 难度选择胶囊（白底蓝字 / 选中金橙渐变白字） */
@Composable
private fun DifficultyPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .height(if (compact) 34.dp else 38.dp)
            .shadow(if (selected) 4.dp else 0.dp, shape)
            .clip(shape)
            .then(
                if (selected) Modifier.background(
                    Brush.verticalGradient(listOf(Color(0xFFFFC24D), Color(0xFFF07E1E)))
                ) else Modifier.background(Color(0xFFF1F6FC))
            )
            .border(
                1.5.dp,
                if (selected) Color(0x99FFFFFF) else Color(0xFFCBDEF2),
                shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color(0xFF4A6285)
        )
    }
}

/** 主页大方格入口（图标 + 标题 + 副标题 + 角标，v13 重设计） */
@Composable
private fun HomeCell(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp,
    background: Brush,
    borderColor: Color,
    title: String,
    titleColor: Color,
    subtitle: String,
    subColor: Color,
    badge: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .height(height)
            .shadow(9.dp, shape)
            .clip(shape)
            .background(background)
            .border(2.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Black,
            color = titleColor
        )
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 10.sp, color = subColor, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x1A000000))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                badge,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor.copy(alpha = 0.92f)
            )
        }
    }
}

/** 局域网入口（白色胶囊卡片 + 深蓝文字） */
@Composable
private fun LanEntry(
    text: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 46.dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(height)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(Color(0xF2FFFFFF))
            .border(1.5.dp, Color(0xFFBFD9F5), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF14427E)
        )
    }
}

// ================================================================= 牌桌背景自定义

/** 相册选图 → 降采样到最长边 ≈1920 → JPEG 存 filesDir/table_bg_custom.jpg */
private fun saveCustomBg(ctx: android.content.Context, uri: Uri): Boolean = try {
    val resolver = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) false else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1920) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
        if (bmp == null) false else {
            val scale = 1920f / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bmp
            TableBg.customFile(ctx).outputStream().use { fos ->
                out.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
            if (out !== bmp) out.recycle()
            bmp.recycle()
            true
        }
    }
} catch (_: Throwable) {
    false
}

/** 小缩略图（设置弹层预览用） */
private fun loadBgThumb(f: java.io.File, max: Int = 240): Bitmap? = try {
    if (!f.exists()) null else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= max) s *= 2
        BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = s }
        )
    }
} catch (_: Throwable) {
    null
}
