package com.laoxiang.ddz.net

import com.laoxiang.ddz.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * 通用局域网房主（v20 · 掼蛋/升级/跑得快/锄大地联机）
 *
 * 架构与斗地主 LanHost 一致（权威引擎 + 出站队列 + 专职写协程）：
 * - 座位 0 = 房主本人；1..N-1 = 远端真人或 AI 托管；座位数随游戏 3/4
 * - 游戏引擎只在单线程 gameDispatcher 上运行，具体玩法委托给 [HostDriver]
 * - 客户端断线自动转 AI 托管；广播只入队，慢客人不拖累牌局
 */
class GameHost(
    private val hostName: String,
    private val hostAvatar: Int,
    val seatCount: Int,
    scope: CoroutineScope,
    driverFactory: (CoroutineScope, () -> Unit) -> HostDriver
) {
    companion object {
        /** 房间名前缀（按游戏拼接） */
        fun roomNameOf(gameId: String): String = when (gameId) {
            "guandan" -> "老乡掼蛋房间"
            "shengji" -> "老乡升级房间"
            "pdk" -> "老乡跑得快房间"
            "bigtwo" -> "老乡锄大地房间"
            else -> "老乡斗地主房间"
        }
    }

    val gameId: String get() = driver.gameId

    /** 游戏逻辑单线程 */
    private val gameDispatcher: CoroutineContext =
        Executors.newSingleThreadExecutor { r -> Thread(r, "gHost-game").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    private val gameScope = CoroutineScope(SupervisorJob() + gameDispatcher)

    private val driver: HostDriver = driverFactory(gameScope) { broadcastFromGame() }
    private val outerScope = scope

    private var server: ServerSocket? = null
    private var advertiser: RoomAdvertiser? = null
    private var acceptJob: Job? = null
    private val connLock = Any()
    private val clients = HashMap<Int, ClientConn>()

    private class ClientConn(
        val socket: Socket,
        val writer: BufferedWriter,
        val name: String,
        val avatar: Int
    ) {
        val outbound = OutboundQueue()
        var writeJob: Job? = null
    }

    @Volatile var aiLevel: Int = 1
        private set
    @Volatile var started: Boolean = false
        private set

    val roomSeats = MutableStateFlow(List(seatCount) { NetMsg.SeatInfo(seat = it) })
    val hostSnapshot = MutableStateFlow<NetMsg.GSnapshot?>(null)
    val chatFlow = MutableStateFlow<Triple<Int, String, Int>?>(null)
    val noticeFlow = MutableStateFlow<String?>(null)

    private fun levelOf(): AiLevel =
        when (aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }

    // ------------------------------------------------ 生命周期

    fun start() {
        stop()
        outerScope.launch(Dispatchers.IO) {
            val serverSocket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(NetPorts.GAME))
                }
            } catch (e: Exception) {
                noticeFlow.value = "开房失败：端口 ${NetPorts.GAME} 被占用"
                return@launch
            }
            server = serverSocket
            advertiser = RoomAdvertiser(
                roomNameOf(gameId), hostName, outerScope, gameId, seatCount
            ).apply {
                playerCount = humanCount()
                start()
            }
            synchronized(connLock) {
                clients.clear()
                roomSeats.value = List(seatCount) { s ->
                    if (s == 0) NetMsg.SeatInfo(0, hostName, hostAvatar, false, connected = true)
                    else NetMsg.SeatInfo(seat = s)
                }
            }
            broadcastRoom()
            acceptJob = outerScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    handleNewClient(socket)
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel(); acceptJob = null
        driver.cancelJobs()
        synchronized(connLock) {
            clients.values.forEach { c ->
                c.outbound.close()
                c.writeJob?.cancel()
                runCatching { c.socket.close() }
            }
            clients.clear()
        }
        advertiser?.stop(); advertiser = null
        runCatching { server?.close() }
        server = null
        started = false
        roomSeats.value = List(seatCount) { NetMsg.SeatInfo(seat = it) }
        hostSnapshot.value = null
    }

    private fun humanCount(): Int = 1 + synchronized(connLock) { clients.size }

    // ------------------------------------------------ 客户端接入

    private fun handleNewClient(socket: Socket) {
        outerScope.launch(Dispatchers.IO) {
            var joinedSeat = -1
            try {
                socket.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val first = reader.readLine() ?: throw IllegalStateException("无加入消息")
                val join = Protocol.decode(first) as? NetMsg.Join
                    ?: throw IllegalStateException("首条消息必须是 join")

                var freeSeat: Int? = null
                var registered: ClientConn? = null
                synchronized(connLock) {
                    freeSeat = (1 until seatCount).firstOrNull { s ->
                        roomSeats.value[s].name.isEmpty() && !clients.containsKey(s)
                    }
                    if (started || freeSeat == null) {
                        runCatching {
                            writer.write(Protocol.encode(NetMsg.Error(if (started) "对局已开始，无法加入" else "房间已满")))
                            writer.newLine(); writer.flush()
                        }
                        null
                    } else {
                        val conn = ClientConn(socket, writer, join.name, join.avatar)
                        clients[freeSeat] = conn
                        registered = conn
                        joinedSeat = freeSeat
                        freeSeat
                    }
                }?.let { seat ->
                    val conn = registered!!
                    startConnWriter(conn)
                    conn.outbound.offer(NetMsg.Welcome(seat, hostName, roomNameOf(gameId)))
                    updateRoomSeats()
                    broadcastRoom()
                    advertiser?.playerCount = humanCount()

                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val msg = Protocol.decode(line) ?: continue
                        handleClientMsg(seat, msg)
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (joinedSeat >= 0) onClientGone(joinedSeat)
                else runCatching { socket.close() }
            }
        }
    }

    private fun onClientGone(seat: Int) {
        val gone: ClientConn?
        synchronized(connLock) { gone = clients.remove(seat) }
        gone?.outbound?.close()
        gone?.writeJob?.cancel()
        runCatching { gone?.socket?.close() }
        if (started) {
            onGameThread {
                driver.detachToAi(seat, levelOf())
                noticeFlow.value = "有乡亲掉线，已托管出牌"
                broadcastFromGame()
            }
        }
        updateRoomSeats()
        broadcastRoom()
        advertiser?.playerCount = humanCount()
    }

    // ------------------------------------------------ 消息处理

    private fun handleClientMsg(seat: Int, msg: NetMsg) {
        when (msg) {
            is NetMsg.Leave -> onClientGone(seat)
            is NetMsg.Play -> onGameThread {
                doAction { driver.play(seat, msg.cardIds) }
            }
            is NetMsg.Pass -> onGameThread {
                doAction { driver.pass(seat) }
            }
            is NetMsg.Claim -> onGameThread {
                doAction { driver.claim(seat, msg.suit) }
            }
            is NetMsg.NextHand -> onGameThread {
                doAction { driver.nextHand() }
            }
            is NetMsg.Restart -> onGameThread {
                doAction { driver.restart() }
            }
            is NetMsg.Chat -> relayChat(seat, msg.text, msg.sound)
            is NetMsg.Ping -> sendToClient(seat, NetMsg.Pong)
            else -> {}
        }
    }

    // ------------------------------------------------ 房主操作（座位 0）

    fun setAiLevel(level: Int) {
        aiLevel = level
        broadcastRoom()
    }

    fun startGame() {
        onGameThread {
            if (started) return@onGameThread
            started = true
            advertiser?.started = true
            val seats = roomSeats.value
            val infos = (0 until seatCount).map { s ->
                val rs = seats[s]
                when {
                    s == 0 -> PlayerInfo(0, hostName, hostAvatar, false)
                    rs.isAi || rs.name.isEmpty() ->
                        PlayerInfo(s, driver.aiNameFor(s), driver.aiAvatarFor(s), true, levelOf())
                    else -> PlayerInfo(s, rs.name, rs.avatar, false)
                }
            }
            driver.newGame(infos)
            broadcastFromGame()
            broadcastRoom()
        }
    }

    fun hostPlay(ids: List<Int>) = onGameThread { doAction { driver.play(0, ids) } }
    fun hostPass() = onGameThread { doAction { driver.pass(0) } }
    fun hostClaim(suit: CardSuit?) = onGameThread { doAction { driver.claim(0, suit) } }
    fun hostNextHand() = onGameThread { doAction { driver.nextHand() } }
    fun hostRestart() = onGameThread { doAction { driver.restart() } }
    fun hostSettle() = onGameThread { doAction { driver.settle() } }
    fun hostAutoBury() = onGameThread { doAction { driver.autoBury() } }
    fun hostChat(text: String, sound: Int) = relayChat(0, text, sound)

    private fun relayChat(seat: Int, text: String, sound: Int) {
        chatFlow.value = Triple(seat, text, sound)
        snapshotClients().forEach { (_, c) -> c.outbound.offer(NetMsg.ChatBroadcast(seat, text, sound)) }
    }

    // ------------------------------------------------ 引擎调度

    private fun onGameThread(block: suspend () -> Unit) {
        outerScope.launch(gameDispatcher) { block() }
    }

    private fun doAction(block: () -> Boolean) {
        block()
        broadcastFromGame()
    }

    /** 广播当前状态（游戏线程内调用），并驱动 AI */
    private fun broadcastFromGame() {
        val conns = snapshotClients()
        val effects = driver.drainEffects()
        conns.forEach { (seat, conn) ->
            conn.outbound.offer(
                NetMsg.GSnapshot(driver.gameId, driver.snapshotFor(seat), effects)
            )
        }
        hostSnapshot.value = NetMsg.GSnapshot(driver.gameId, driver.snapshotFor(0), effects)
        driver.pumpAi()
    }

    private fun broadcastRoom() {
        val room = NetMsg.Room(roomSeats.value, started, aiLevel, NetUtils.localIpAddress() ?: "?", gameId)
        snapshotClients().forEach { (_, c) -> c.outbound.offer(room) }
    }

    private fun snapshotClients(): List<Pair<Int, ClientConn>> =
        synchronized(connLock) { clients.entries.map { it.key to it.value } }

    private fun updateRoomSeats() {
        synchronized(connLock) {
            roomSeats.value = roomSeats.value.mapIndexed { i, s ->
                when {
                    i == 0 -> s.copy(name = hostName, avatar = hostAvatar, isAi = false, connected = true)
                    clients.containsKey(i) -> s.copy(
                        name = clients[i]?.name ?: "", avatar = clients[i]?.avatar ?: 0,
                        isAi = false, connected = true
                    )
                    else -> NetMsg.SeatInfo(seat = i)
                }
            }
        }
    }

    private fun startConnWriter(conn: ClientConn) {
        conn.writeJob = outerScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val msg = conn.outbound.take() ?: break
                    conn.writer.write(Protocol.encode(msg))
                    conn.writer.newLine()
                    conn.writer.flush()
                }
            } catch (_: Exception) {
            } finally {
                conn.outbound.close()
                runCatching { conn.socket.close() }
            }
        }
    }

    private fun sendToClient(seat: Int, msg: NetMsg) {
        snapshotClients().firstOrNull { it.first == seat }?.let { it.second.outbound.offer(msg) }
    }
}

/** 各游戏的房主侧玩法驱动（引擎 + AI + 事件→特效），全部在游戏单线程上执行 */
interface HostDriver {
    val gameId: String

    fun newGame(infos: List<PlayerInfo>)
    fun aiNameFor(seat: Int): String
    fun aiAvatarFor(seat: Int): Int

    /** 客户端掉线 → AI 托管 */
    fun detachToAi(seat: Int, level: AiLevel)

    /** 出牌/过牌/亮主；升级 BURYING 阶段的 Play 会被路由为扣底 */
    fun play(seat: Int, ids: List<Int>): Boolean
    fun pass(seat: Int): Boolean
    fun claim(seat: Int, suit: CardSuit?): Boolean

    /** 副间推进 / 重开一局（引擎幂等，条件不满足返回 false） */
    fun nextHand(): Boolean
    fun restart(): Boolean

    /** 升级：庄家一键自动扣底（幂等；其他游戏恒 false） */
    fun autoBury(): Boolean = false

    /** 升级定主兜底收口（幂等；其他游戏恒 false） */
    fun settle(): Boolean = false

    fun snapshotFor(seat: Int): JsonElement
    fun drainEffects(): List<NetMsg.Effect>

    /** 每次广播后调用：安排下一次 AI 行动（内部用 gameScope 延时执行，执行后 trigger() 再广播） */
    fun pumpAi()

    fun cancelJobs()
}
