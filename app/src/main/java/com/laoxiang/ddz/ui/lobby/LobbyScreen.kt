package com.laoxiang.ddz.ui.lobby

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.ui.common.AvatarImage
import com.laoxiang.ddz.ui.game.GameViewModel
import com.laoxiang.ddz.ui.theme.*

/**
 * 大厅主页（对标欢乐斗地主）：浅蓝天空场景，App 已锁定横屏，单套布局不滚动。
 * 顶栏（胶囊+品牌+设置）→ 左大卡片（难度+开局）+ 右联机入口竖排
 */
@Composable
fun LobbyScreen(
    gameVm: GameViewModel,
    onSingle: (Int) -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit
) {
    val prefs = gameVm.prefs
    var nickname by remember { mutableStateOf(prefs.nickname) }
    var avatar by remember { mutableStateOf(prefs.avatar) }
    var soundOn by remember { mutableStateOf(prefs.soundEnabled) }
    var musicOn by remember { mutableStateOf(prefs.musicEnabled) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showNicknameEditor by remember { mutableStateOf(false) }
    var nicknameDraft by remember { mutableStateOf("") }
    var aiLevel by remember { mutableStateOf(prefs.aiLevel) }

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
                        Text("经典单机 · 局域网联机", fontSize = 9.sp, color = Color(0xCCE8F2FF))
                    }
                    Spacer(Modifier.weight(1f))
                    SettingsGear { showSettings = true }
                }

                Spacer(Modifier.weight(0.5f))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左：大卡片开局区
                    Column(
                        Modifier
                            .weight(0.64f)
                            .shadow(12.dp, RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xF7FFFFFF))
                            .border(2.dp, Color(0xFFCFE4FB), RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "经典斗地主",
                                    fontSize = 20.sp, fontWeight = FontWeight.Black,
                                    color = Color(0xFF123A6E)
                                )
                                Text(
                                    "三人一副牌 · 智能电脑对手",
                                    fontSize = 10.sp, color = Color(0xFF6B83A3)
                                )
                            }
                            Image(
                                painter = painterResource(R.drawable.fan_cards),
                                contentDescription = "扑克装饰",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.height(56.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
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
                        Spacer(Modifier.height(12.dp))
                        BigStartButton("开 始 对 战", height = 54.dp) { onSingle(aiLevel) }
                    }
                    // 右：联机入口竖排
                    Column(
                        Modifier.weight(0.36f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LanEntry("开一桌 · 当房主", Modifier.fillMaxWidth(), height = 58.dp) { onHost() }
                        LanEntry("串门 · 加入牌局", Modifier.fillMaxWidth(), height = 58.dp) { onJoin() }
                    }
                }

                Spacer(Modifier.weight(0.5f))
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

        // ---------- 设置弹层（声音）
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
                    Column {
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

/** 超大开局按钮（金橙渐变 + 白描边 + 立体阴影） */
@Composable
private fun BigStartButton(
    text: String,
    height: androidx.compose.ui.unit.Dp = 64.dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFC24D), Color(0xFFF5921E), Color(0xFFE87B12))
                )
            )
            .border(2.dp, Color(0xB3FFFFFF), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
                .width(76.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x59FFFFFF))
        )
        Text(
            text,
            fontSize = if (height < 60.dp) 19.sp else 22.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            letterSpacing = 4.sp,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color(0x669C4A00),
                    offset = androidx.compose.ui.geometry.Offset(1f, 2f),
                    blurRadius = 3f
                )
            )
        )
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
