package com.laoxiang.ddz.net

import com.laoxiang.ddz.data.GameSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 局域网客户端（座位由房主分配）
 *
 * 连接 → Join → 收 Welcome/Room/Snapshot/Chat
 * 出牌/叫抢/聊天全部发消息，等待房主权威快照。
 */
class LanClient(
    private val scope: CoroutineScope
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null

    val mySeat = MutableStateFlow(-1)
    val roomState = MutableStateFlow<NetMsg.Room?>(null)
    val snapshot = MutableStateFlow<NetMsg.Snapshot?>(null)
    val chatFlow = MutableStateFlow<Triple<Int, String, Int>?>(null)
    val kicked = MutableStateFlow(false)

    /** 连接状态：null 未连 / "" 连接中 / 其他=错误信息 */
    val connectError = MutableStateFlow<String?>(null)
    @Volatile
    var connected = false
        private set

    /**
     * 连接房主。结果通过 [connectError]（null=成功）与 [roomState] 通知。
     */
    fun connect(ip: String, name: String, avatar: Int) {
        disconnect()
        connectError.value = ""   // 连接中
        scope.launch(Dispatchers.IO) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, NetPorts.GAME), 4000)
                socket = s
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                writer = w
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

                send(NetMsg.Join(name, avatar))

                readJob = scope.launch(Dispatchers.IO) { readLoop(reader) }
                pingJob = scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(8000)
                        send(NetMsg.Ping)
                    }
                }
                connected = true
                // connectError 由 welcome/room 到达后清空
            } catch (e: Exception) {
                connectError.value = "连接失败：${e.message ?: "无法到达 $ip"}"
                runCatching { socket?.close() }
                socket = null
                connected = false
            }
        }
    }

    private suspend fun readLoop(reader: BufferedReader) {
        try {
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                when (val msg = Protocol.decode(line) ?: continue) {
                    is NetMsg.Welcome -> {
                        mySeat.value = msg.seat
                        connectError.value = null
                    }
                    is NetMsg.Room -> roomState.value = msg
                    is NetMsg.Snapshot -> snapshot.value = msg
                    is NetMsg.ChatBroadcast -> chatFlow.value = Triple(msg.seat, msg.text, msg.sound)
                    is NetMsg.Error -> connectError.value = msg.text
                    is NetMsg.Kicked -> kicked.value = true
                    is NetMsg.Pong -> {}
                    else -> {}
                }
            }
        } catch (_: Exception) {
        } finally {
            connected = false
            if (connectError.value == null) connectError.value = "与房主的连接已断开"
        }
    }

    private fun send(msg: NetMsg) {
        val w = writer ?: return
        try {
            synchronized(w) {
                w.write(Protocol.encode(msg))
                w.newLine()
                w.flush()
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------ 对外操作

    fun bid(yes: Boolean) = send(NetMsg.Bid(yes))

    fun play(cardIds: List<Int>) = send(NetMsg.Play(cardIds))

    fun pass() = send(NetMsg.Pass)

    fun chat(text: String, sound: Int = -1) = send(NetMsg.Chat(text, sound))

    fun disconnect() {
        runCatching { send(NetMsg.Leave) }
        readJob?.cancel(); readJob = null
        pingJob?.cancel(); pingJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        connected = false
        mySeat.value = -1
        roomState.value = null
        snapshot.value = null
        kicked.value = false
    }

    /** 记牌器：快照公开信息推导（54 - 已出 - 我手牌） */
    fun remainingCounts(): Map<Int, Int> {
        val snap = snapshot.value?.snapshot ?: return emptyMap()
        val my = snap.seats.firstOrNull { it.seat == mySeat.value } ?: return emptyMap()
        return (3..17).associateWith { r ->
            val total = if (r >= 16) 1 else 4
            total - (snap.playedRanks[r] ?: 0) - my.hand.count { it.rank == r }
        }
    }
}
