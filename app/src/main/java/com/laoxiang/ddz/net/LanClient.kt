package com.laoxiang.ddz.net

import com.laoxiang.ddz.data.CardSuit
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
 *
 * v19：所有发送改为「入队 + 专职写协程」——此前从 UI 主线程同步写 socket
 * 会抛 NetworkOnMainThreadException 被静默吞掉，消息根本没发出去
 * （表现即「点了抢地主/出牌没反应，几秒后提示送达失败」）。
 */
class LanClient(
    private val scope: CoroutineScope
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var outbound = OutboundQueue()
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var pingJob: Job? = null

    val mySeat = MutableStateFlow(-1)
    val roomState = MutableStateFlow<NetMsg.Room?>(null)
    val snapshot = MutableStateFlow<NetMsg.Snapshot?>(null)
    /** 通用对局快照（v20：掼蛋/升级/跑得快/锄大地联机用） */
    val gSnapshot = MutableStateFlow<NetMsg.GSnapshot?>(null)
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
        outbound = OutboundQueue()   // disconnect 会关旧队列，重连必须换新队列
        connectError.value = ""   // 连接中
        scope.launch(Dispatchers.IO) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, NetPorts.GAME), 4000)
                socket = s
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                writer = w

                // 专职写协程：把出站队列逐条写出（真正的 socket IO 全在 IO 线程）
                writeJob = scope.launch(Dispatchers.IO) { writeLoop(w) }
                send(NetMsg.Join(name, avatar))

                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
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
                    is NetMsg.GSnapshot -> gSnapshot.value = msg
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

    /** 发送 = 非阻塞入队，任何线程（含 UI 主线程）调用都安全且立即返回 */
    private fun send(msg: NetMsg) {
        outbound.offer(msg)
    }

    /** 专职写协程：唯一的 socket 写入点，永远在 IO 线程 */
    private suspend fun writeLoop(w: BufferedWriter) {
        try {
            while (currentCoroutineContext().isActive) {
                val msg = outbound.take() ?: break
                w.write(Protocol.encode(msg))
                w.newLine()
                w.flush()
            }
        } catch (_: Exception) {
            // 写失败 = 连接已死：关 socket 让读循环退出，UI 收到「连接已断开」
        } finally {
            connected = false
            runCatching { socket?.close() }
        }
    }

    // ------------------------------------------------ 对外操作

    fun bid(yes: Boolean) = send(NetMsg.Bid(yes))

    fun play(cardIds: List<Int>) = send(NetMsg.Play(cardIds))

    fun pass() = send(NetMsg.Pass)

    /** 升级亮主（suit=null 亮无主） */
    fun claim(suit: CardSuit?) = send(NetMsg.Claim(suit))

    /** 掼蛋/升级：开下一副 */
    fun nextHand() = send(NetMsg.NextHand)

    /** 跑得快/锄大地：再来一局 */
    fun restart() = send(NetMsg.Restart)

    fun chat(text: String, sound: Int = -1) = send(NetMsg.Chat(text, sound))

    fun disconnect() {
        outbound.close()
        readJob?.cancel(); readJob = null
        writeJob?.cancel(); writeJob = null
        pingJob?.cancel(); pingJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        connected = false
        mySeat.value = -1
        roomState.value = null
        snapshot.value = null
        gSnapshot.value = null
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
