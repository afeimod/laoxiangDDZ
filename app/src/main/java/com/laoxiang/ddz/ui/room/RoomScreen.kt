package com.laoxiang.ddz.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.net.NetMsg
import com.laoxiang.ddz.net.RoomBroadcast
import com.laoxiang.ddz.ui.common.*
import com.laoxiang.ddz.ui.game.GameMode
import com.laoxiang.ddz.ui.game.GameViewModel
import com.laoxiang.ddz.ui.theme.*
import androidx.compose.runtime.collectAsState

/**
 * 联机房间页：
 * HOST  —— 房号/IP 展示 + 座位 + AI 等级 + 开局
 * CLIENT —— 房间扫描列表 + 手动 IP 直连 + 等待房主开局
 */
@Composable
fun RoomScreen(
    gameVm: GameViewModel,
    onExit: () -> Unit,
    onEnterGame: () -> Unit
) {
    val mode = gameVm.mode.collectAsState().value
    val snapshot by gameVm.snapshot.collectAsState()
    val roomStarted by gameVm.roomStarted.collectAsState()
    val hostIp by gameVm.hostIp.collectAsState()

    // 对局开始时自动进入
    LaunchedEffect(snapshot?.phase, roomStarted) {
        val phase = snapshot?.phase
        if (phase == Phase.BIDDING || phase == Phase.ROBBING || phase == Phase.PLAYING) {
            onEnterGame()
        }
    }

    when (mode) {
        GameMode.HOST -> HostRoom(gameVm, hostIp, onExit)
        else -> ClientRoom(gameVm, onExit)
    }
}

// ------------------------------------------------ 房主

@Composable
private fun HostRoom(gameVm: GameViewModel, hostIp: String?, onExit: () -> Unit) {
    val seats by gameVm.roomSeats.collectAsState()
    val aiLevel = gameVm.prefs.aiLevel
    val notice by gameVm.notice.collectAsState()

    RoomScaffold(
        title = "我的牌桌",
        subtitle = "乡亲们连同一 WiFi 即可搜到；或把下面 IP 告诉他们手动连",
        onExit = onExit,
        showIp = hostIp
    ) {
        // 座位
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            seats.take(3).forEach { s ->
                SeatCard(s, Modifier.weight(1f))
            }
        }

        notice?.let {
            Spacer(Modifier.height(10.dp))
            Text("※ $it", color = Color(0xFFFFE08A), fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("电脑补位难度", color = Color(0xFFFFE9C4), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1, 2).forEach { lv ->
                FilterChip(
                    selected = aiLevel == lv,
                    onClick = { gameVm.hostSetAiLevel(lv) },
                    label = { Text(listOf("简单", "中等", "困难")[lv]) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LightGold,
                        selectedLabelColor = DeepRed
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        GoldButton("开局（空位电脑顶上）") { gameVm.hostStartGame() }

        Spacer(Modifier.height(10.dp))
        Text(
            "提示：开局后中途掉线的乡亲会自动转为电脑托管，牌局不散。",
            fontSize = 12.sp, color = Color(0xAAFFD9A0)
        )
    }
}

// ------------------------------------------------ 客户端

@Composable
private fun ClientRoom(gameVm: GameViewModel, onExit: () -> Unit) {
    val rooms by gameVm.foundRooms.collectAsState()
    val seats by gameVm.roomSeats.collectAsState()
    val connectState by gameVm.connectState.collectAsState()
    val mySeat by gameVm.mySeat.collectAsState()
    var manualIp by remember { mutableStateOf("") }
    val context = LocalContext.current

    // 进页即开始扫描
    DisposableEffect(Unit) {
        gameVm.startScan(context)
        onDispose { gameVm.stopScan() }
    }

    val joined = mySeat >= 0

    RoomScaffold(
        title = if (joined) "已上桌，等房主开局" else "串门找牌局",
        subtitle = if (joined) "房主一点开局，马上开打" else "自动搜寻同 WiFi 的老乡牌桌，也可手动输 IP",
        onExit = onExit,
        showIp = null
    ) {
        if (joined) {
            // 已加入：等待
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                seats.take(3).forEach { s ->
                    SeatCard(s, Modifier.weight(1f), highlight = s.seat == mySeat)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = LightGold, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("等待房主开局…", color = Color(0xFFFFE9C4))
            }
        } else {
            // 连接错误提示
            if (connectState != null && connectState != "") {
                Text(
                    "连接失败：$connectState",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
            }
            // 手动 IP
            Text("手动连接（房主屏幕上会显示 IP）", color = Color(0xFFFFE9C4), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it.trim().take(16) },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Color(0x66D4A24E),
                        focusedPlaceholderColor = Color(0x66FFFFFF),
                        unfocusedPlaceholderColor = Color(0x66FFFFFF)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { if (manualIp.isNotBlank()) gameVm.startClient(manualIp) },
                    colors = ButtonDefaults.buttonColors(containerColor = ChineseRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("连接", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("附近牌桌（每 2 秒刷新）", color = Color(0xFFFFE9C4), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (rooms.isEmpty()) {
                Text(
                    "还没搜到房间…让房主先开一桌，确认大家连同一个 WiFi",
                    fontSize = 13.sp, color = Color(0xAAFFD9A0)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 260.dp)
                ) {
                    items(rooms) { room ->
                        RoomRow(room) { gameVm.startClient(room.ip) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomRow(room: RoomBroadcast, onJoin: () -> Unit) {
    GoldCard(corner = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    room.roomName,
                    fontWeight = FontWeight.Bold,
                    color = DeepRed,
                    fontSize = 15.sp
                )
                Text(
                    "房主 ${room.hostName} · ${room.players}/3 人" +
                            if (room.started) " · 已开局" else "",
                    fontSize = 12.sp, color = Color(0xFF8A6A45)
                )
                Text(room.ip, fontSize = 11.sp, color = Color(0xAA8A6A45))
            }
            Button(
                onClick = onJoin,
                enabled = !room.started,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ChineseRed)
            ) {
                Text(if (room.started) "进行中" else "上桌", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------ 通用

@Composable
private fun RoomScaffold(
    title: String,
    subtitle: String,
    onExit: () -> Unit,
    showIp: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.laoxiang.ddz.R.drawable.bg_lobby),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x88380D08))
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .systemBarsPadding()
        ) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TitleBar(title)
                TextButton(onClick = onExit) {
                    Text("退出房间", color = Color(0xFFFFD9A0))
                }
            }
            Text(subtitle, fontSize = 13.sp, color = Color(0xCCFFD9A0))
            if (showIp != null) {
                Spacer(Modifier.height(12.dp))
                GoldCard(corner = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本机 IP", fontWeight = FontWeight.Bold, color = DeepRed)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            showIp,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF7A0F0F)
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SeatCard(
    seat: NetMsg.SeatInfo,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val occupied = seat.name.isNotEmpty() || seat.isAi
    GoldCard(modifier = modifier, corner = 14.dp) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (occupied) {
                AvatarImage(if (seat.isAi) 5 else seat.avatar, 52.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    seat.name.ifEmpty { "电脑" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlight) DeepRed else Ink
                )
                Text(
                    if (seat.isAi) "电脑" else if (seat.connected) "在线" else "空位",
                    fontSize = 11.sp,
                    color = Color(0xFF8A6A45)
                )
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋", fontSize = 26.sp, color = Color(0x88D4A24E))
                }
                Spacer(Modifier.height(6.dp))
                Text("虚位以待", fontSize = 12.sp, color = Color(0xAA8A6A45))
            }
            if (seat.seat == 0) {
                Spacer(Modifier.height(2.dp))
                Text("房主", fontSize = 10.sp, color = DeepRed)
            }
        }
    }
}
