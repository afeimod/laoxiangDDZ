package com.laoxiang.ddz.ui.lobby

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laoxiang.ddz.R
import com.laoxiang.ddz.ui.common.*
import com.laoxiang.ddz.ui.game.GameViewModel
import com.laoxiang.ddz.ui.theme.*

/**
 * 大厅：资料、三种模式入口、设置（桌型/音效/音乐）
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
    var tableStyle by remember { mutableStateOf(prefs.tableStyle) }
    var soundOn by remember { mutableStateOf(prefs.soundEnabled) }
    var musicOn by remember { mutableStateOf(prefs.musicEnabled) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var aiLevel by remember { mutableStateOf(prefs.aiLevel) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 大厅背景图
        Image(
            painter = painterResource(R.drawable.bg_lobby),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 半透明暖色遮罩，保证前景可读
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66380D08))
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .systemBarsPadding()
        ) {
            Spacer(Modifier.height(18.dp))
            TitleBar("老乡斗地主", "咱村儿的牌桌 · 单机 + 局域网")
            Spacer(Modifier.height(14.dp))

            // 资料卡
            GoldCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(avatar, 64.dp, Modifier.clickable { showAvatarPicker = true })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = {
                                nickname = it.take(8)
                                prefs.nickname = nickname
                            },
                            label = { Text("你的诨名") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Ink,
                                unfocusedTextColor = Ink,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Color(0x88D4A24E)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "战绩 ${prefs.wins}胜 ${prefs.losses}负 · 欢乐豆 ${prefs.beans}",
                            fontSize = 13.sp, color = Color(0xFF7A5A33)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 单人模式（难度选择）
            GoldCard {
                Text("单人对战", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DeepRed)
                Spacer(Modifier.height(4.dp))
                Text(
                    "庄稼汉陪你解闷，三档任选：",
                    fontSize = 13.sp, color = Color(0xFF8A6A45)
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 1, 2).forEach { lv ->
                        val label = listOf("简单", "中等", "困难")[lv]
                        FilterChip(
                            selected = aiLevel == lv,
                            onClick = { aiLevel = lv },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LightGold,
                                selectedLabelColor = DeepRed
                            )
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GoldButton("开打 · ${listOf("简单", "中等", "困难")[aiLevel]}局") {
                    onSingle(aiLevel)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 局域网联机
            GoldCard {
                Text("乡里乡亲局域网", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DeepRed)
                Spacer(Modifier.height(4.dp))
                Text(
                    "同一 WiFi 下自动找房间，人不够电脑补位",
                    fontSize = 13.sp, color = Color(0xFF8A6A45)
                )
                Spacer(Modifier.height(12.dp))
                GoldButton("我是房主 · 开一桌", container = Color(0xFF9C2B1F)) { onHost() }
                Spacer(Modifier.height(10.dp))
                OutlineGoldButton("串门 · 加入牌局") { onJoin() }
            }

            Spacer(Modifier.height(16.dp))

            // 设置入口
            OutlineGoldButton("设置（桌型 / 声音）") { showSettings = true }

            Spacer(Modifier.height(28.dp))
            Text(
                "素材版权归原作者 · 仅供学习交流",
                fontSize = 11.sp, color = Color(0x88FFE2B8),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
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
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    avatar = idx
                                    prefs.avatar = idx
                                }
                            ) {
                                val sel = avatar == idx
                                AvatarImage(
                                    idx, 56.dp,
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(
                                            if (sel) Modifier.background(Color(0x44D4A24E)) else Modifier
                                        )
                                )
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

        // ---------- 设置弹层
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
                        Text("牌桌样式", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = tableStyle == "square",
                                onClick = {
                                    tableStyle = "square"
                                    prefs.tableStyle = "square"
                                },
                                label = { Text("红木方桌") }
                            )
                            FilterChip(
                                selected = tableStyle == "round",
                                onClick = {
                                    tableStyle = "round"
                                    prefs.tableStyle = "round"
                                },
                                label = { Text("海景圆桌") }
                            )
                        }
                        Spacer(Modifier.height(14.dp))
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
