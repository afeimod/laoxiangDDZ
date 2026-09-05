package com.laoxiang.ddz.ui.room

import android.content.Context
import com.laoxiang.ddz.net.NetMsg
import com.laoxiang.ddz.net.RoomBroadcast
import kotlinx.coroutines.flow.StateFlow

/**
 * 联机房间页的统一视图接口（v20）：
 * 斗地主由 GameViewModel 实现（沿用原 LanHost 链路），
 * 掼蛋/升级/跑得快/锄大地由 NetLobby 实现（GameHost/GameDrivers 链路）。
 */
interface NetRoomUi {
    val roomSeats: StateFlow<List<NetMsg.SeatInfo>>
    val roomStarted: StateFlow<Boolean>
    val hostIp: StateFlow<String?>
    val connectState: StateFlow<String?>
    val notice: StateFlow<String?>
    val foundRooms: StateFlow<List<RoomBroadcast>>
    val mySeat: StateFlow<Int>

    /** 对局已开始（房间页据此自动进入对局界面） */
    val gameLive: StateFlow<Boolean>

    /** 房主侧 AI 难度（响应式） */
    val aiLevelUi: StateFlow<Int>

    /** 房间座位数（3 或 4） */
    val seatCount: Int

    /** 是否房主侧 */
    val isHostSide: Boolean

    fun hostSetAiLevel(level: Int)
    fun hostStartGame()
    fun joinByIp(game: String, ip: String)
    fun startScan(context: Context)
    fun stopScan()
    fun leaveRoom()
}

/** 联机可选的游戏定义（id 即 LobbyScreen 弹层/手动连接的选择值） */
data class NetGameDef(val id: String, val label: String, val seats: Int)

val NET_GAMES = listOf(
    NetGameDef("ddz", "斗地主", 3),
    NetGameDef("guandan", "掼蛋", 4),
    NetGameDef("shengji", "升级", 4),
    NetGameDef("pdk3", "跑得快·3人", 3),
    NetGameDef("pdk4", "跑得快·4人", 4),
    NetGameDef("bigtwo", "锄大地", 4)
)

/** 选择值 → (引擎游戏 id, 座位数) */
fun parseNetGame(selId: String): Pair<String, Int> = when (selId) {
    "pdk3" -> "pdk" to 3
    "pdk4" -> "pdk" to 4
    else -> selId to (NET_GAMES.firstOrNull { it.id == selId }?.seats ?: 4)
}

/** 房间广播里的游戏 → 显示名 */
fun gameLabelOf(game: String, seats: Int): String = when (game) {
    "guandan" -> "掼蛋"
    "shengji" -> "升级"
    "pdk" -> if (seats == 3) "跑得快·3人" else "跑得快·4人"
    "bigtwo" -> "锄大地"
    else -> "斗地主"
}
