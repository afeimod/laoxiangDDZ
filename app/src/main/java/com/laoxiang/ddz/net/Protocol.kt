package com.laoxiang.ddz.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import com.laoxiang.ddz.data.CardSuit
import com.laoxiang.ddz.data.GameSnapshot

/**
 * 局域网联机协议（TCP 长连接 + 换行分隔 JSON）
 *
 * 端口约定：
 *  UDP 38888 —— 房间广播发现
 *  TCP 38889 —— 对局通信
 */
object NetPorts {
    const val DISCOVERY = 38888
    const val GAME = 38889
}

/** 消息根类型：密封层级 + classDiscriminator="t" 实现自动多态编解码 */
@Serializable
sealed interface NetMsg {

    // ---------------- 客户端 → 房主 ----------------

    @Serializable
    @SerialName("join")
    data class Join(val name: String, val avatar: Int) : NetMsg

    @Serializable
    @SerialName("leave")
    object Leave : NetMsg

    @Serializable
    @SerialName("ready")
    data class Ready(val ready: Boolean) : NetMsg

    /** AI 补位等级：0 简单 / 1 中等 / 2 困难（房主开桌时使用） */
    @Serializable
    @SerialName("start")
    data class Start(val aiLevel: Int) : NetMsg

    /** 叫 / 抢阶段通用 */
    @Serializable
    @SerialName("bid")
    data class Bid(val yes: Boolean) : NetMsg

    @Serializable
    @SerialName("play")
    data class Play(val cardIds: List<Int>) : NetMsg

    @Serializable
    @SerialName("pass")
    object Pass : NetMsg

    @Serializable
    @SerialName("chat")
    data class Chat(val text: String, val sound: Int = -1) : NetMsg

    @Serializable
    @SerialName("ping")
    object Ping : NetMsg

    /** 升级亮主：suit=null 亮无主（v20 全系列联机） */
    @Serializable
    @SerialName("claim")
    data class Claim(val suit: CardSuit? = null) : NetMsg

    /** 掼蛋/升级：结算后开下一副（房主端引擎幂等，任何玩家都可发起） */
    @Serializable
    @SerialName("nexthand")
    object NextHand : NetMsg

    /** 跑得快/锄大地：一局结束后再开一局（仅 GAME_OVER 时生效） */
    @Serializable
    @SerialName("restart")
    object Restart : NetMsg

    /** 麻将操作（v22）：discard/hu/peng/gang/chi/gang_an/gang_bu/pass/dingque/swap3 */
    @Serializable
    @SerialName("mjact")
    data class MjAct(
        val action: String,
        val tileIds: List<Int> = emptyList(),
        /** chi 中间张 code / dingque 花色 / 暗杠 code */
        val extra: Int = -1
    ) : NetMsg

    // ---------------- 房主 → 客户端 ----------------

    @Serializable
    @SerialName("welcome")
    data class Welcome(val seat: Int, val hostName: String, val roomName: String) : NetMsg

    @Serializable
    data class SeatInfo(
        val seat: Int,
        val name: String = "",
        val avatar: Int = 0,
        val isAi: Boolean = false,
        val ready: Boolean = false,
        val connected: Boolean = false
    )

    @Serializable
    @SerialName("room")
    data class Room(
        val seats: List<SeatInfo>,
        val started: Boolean,
        val aiLevel: Int,
        val hostIp: String,
        /** 房间玩的游戏：ddz / guandan / shengji / pdk / bigtwo（v20） */
        val game: String = "ddz"
    ) : NetMsg

    /** 视觉 / 音效事件（简化，客户端按序播报） */
    @Serializable
    data class Effect(
        val type: String,     // shuffle / bid_call / bid_pass / rob / rob_pass / landlord_set
        // played / pass / bomb / plane / game_over / redeal
        val seat: Int = -1,
        val rocket: Boolean = false,
        val landlordWon: Boolean = false
    )

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val snapshot: GameSnapshot,
        val effects: List<Effect> = emptyList()
    ) : NetMsg

    /** 通用对局快照（v20 全系列联机）：payload 为各游戏自己的快照 JSON */
    @Serializable
    @SerialName("gsnap")
    data class GSnapshot(
        val game: String,
        val payload: JsonElement,
        val effects: List<Effect> = emptyList()
    ) : NetMsg

    @Serializable
    @SerialName("chatbc")
    data class ChatBroadcast(val seat: Int, val text: String, val sound: Int = -1) : NetMsg

    @Serializable
    @SerialName("kicked")
    object Kicked : NetMsg

    @Serializable
    @SerialName("error")
    data class Error(val text: String) : NetMsg

    @Serializable
    @SerialName("pong")
    object Pong : NetMsg
}

val netJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "t"
}

object Protocol {
    fun encode(msg: NetMsg): String = netJson.encodeToString(NetMsg.serializer(), msg)

    fun decode(line: String): NetMsg? = try {
        netJson.decodeFromString(NetMsg.serializer(), line)
    } catch (e: Exception) {
        null
    }
}

// ------------------------------------------------ 工具

object NetUtils {
    /** 本机局域网 IP（遍历网卡，取站点本地地址） */
    fun localIpAddress(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /** 广播地址（/24 网段推算，失败用全局广播） */
    fun broadcastAddress(): String {
        val ip = localIpAddress() ?: return "255.255.255.255"
        return try {
            val parts = ip.split(".").map { it.toInt() }
            "${parts[0]}.${parts[1]}.${parts[2]}.255"
        } catch (e: Exception) {
            "255.255.255.255"
        }
    }
}
