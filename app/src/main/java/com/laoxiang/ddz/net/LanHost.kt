package com.laoxiang.ddz.net

import com.laoxiang.ddz.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * 局域网房主（权威服务器）
 *
 * - 座位 0 = 房主本人（本地直接调用，不经网络）
 * - 座位 1/2 = 远端真人或 AI 托管
 * - 牌局引擎只在 [gameDispatcher] 单线程上运行；连接表用 [connLock] 保护
 * - 客户端断线自动转 AI 托管，牌局不中断
 */
class LanHost(
    private val hostName: String,
    private val hostAvatar: Int,
    private val scope: CoroutineScope
) {
    companion object {
        const val ROOM_NAME = "老乡斗地主房间"
    }

    /** 游戏逻辑单线程：保证引擎无并发访问 */
    private val gameDispatcher: CoroutineContext =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ddz-host-game").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private val engine = GameEngine()
    private var server: ServerSocket? = null
    private var advertiser: RoomAdvertiser? = null
    private var acceptJob: Job? = null
    private var aiJob: Job? = null
    private val connLock = Any()

    /** seat → 连接 */
    private val clients = HashMap<Int, ClientConn>()

    private class ClientConn(
        val socket: Socket,
        val writer: BufferedWriter,
        val name: String,
        val avatar: Int
    )

    @Volatile
    var aiLevel: Int = 1   // 0简单 1中等 2困难
        private set

    @Volatile
    var started: Boolean = false
        private set

    /** 房间座位展示状态（房间界面用） */
    val roomSeats = MutableStateFlow(List(3) { NetMsg.SeatInfo(seat = it) })

    /** 房主本人视角快照 */
    val hostSnapshot = MutableStateFlow<NetMsg.Snapshot?>(null)

    /** 聊天气泡（seat, text, sound） */
    val chatFlow = MutableStateFlow<Triple<Int, String, Int>?>(null)

    /** 提示 */
    val noticeFlow = MutableStateFlow<String?>(null)

    private fun levelOf(): AiLevel =
        when (aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }

    // ------------------------------------------------ 生命周期

    fun start() {
        stop()
        scope.launch(Dispatchers.IO) {
            val serverSocket = try {
                ServerSocket(NetPorts.GAME)
            } catch (e: Exception) {
                noticeFlow.value = "开房失败：端口 ${NetPorts.GAME} 被占用"
                return@launch
            }
            server = serverSocket
            advertiser = RoomAdvertiser(ROOM_NAME, hostName, scope).apply {
                playerCount = humanCount()
                start()
            }
            synchronized(connLock) {
                clients.clear()
                roomSeats.value = List(3) { s ->
                    if (s == 0) NetMsg.SeatInfo(0, hostName, hostAvatar, false, connected = true)
                    else NetMsg.SeatInfo(seat = s)
                }
            }
            broadcastRoom()
            acceptJob = scope.launch(Dispatchers.IO) {
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
        aiJob?.cancel(); aiJob = null
        synchronized(connLock) {
            clients.values.forEach { c -> runCatching { c.socket.close() } }
            clients.clear()
        }
        advertiser?.stop(); advertiser = null
        runCatching { server?.close() }
        server = null
        started = false
        roomSeats.value = List(3) { NetMsg.SeatInfo(seat = it) }
        hostSnapshot.value = null
    }

    private fun humanCount(): Int = 1 + synchronized(connLock) { clients.size }

    // ------------------------------------------------ 客户端接入

    private fun handleNewClient(socket: Socket) {
        scope.launch(Dispatchers.IO) {
            var joinedSeat = -1
            try {
                socket.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val first = reader.readLine() ?: throw IllegalStateException("无加入消息")
                val join = Protocol.decode(first) as? NetMsg.Join
                    ?: throw IllegalStateException("首条消息必须是 join")

                var freeSeat: Int? = null
                synchronized(connLock) {
                    freeSeat = (1..2).firstOrNull { s -> roomSeats.value[s].name.isEmpty() && !clients.containsKey(s) }
                    if (started || freeSeat == null) {
                        sendTo(writer, NetMsg.Error(if (started) "对局已开始，无法加入" else "房间已满（3人）"))
                        null
                    } else {
                        clients[freeSeat] = ClientConn(socket, writer, join.name, join.avatar)
                        joinedSeat = freeSeat
                        freeSeat
                    }
                }?.let { seat ->
                    sendTo(writer, NetMsg.Welcome(seat, hostName, ROOM_NAME))
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
        synchronized(connLock) {
            gone = clients.remove(seat)
        }
        runCatching { gone?.socket?.close() }
        if (started) {
            onGameThread {
                val p = engine.players.getOrNull(seat)
                if (p != null && !p.info.isAi) {
                    val name = p.info.name
                    p.info = p.info.copy(isAi = true, aiLevel = levelOf())
                    noticeFlow.value = "「$name」掉线，已托管出牌"
                    broadcast(emptyList())
                }
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
            is NetMsg.Bid -> onGameThread {
                doAction {
                    if (engine.phase == Phase.BIDDING) engine.callLandlord(seat, msg.yes)
                    else engine.robLandlord(seat, msg.yes)
                }
            }
            is NetMsg.Play -> onGameThread {
                val cards = Deck.byIds(msg.cardIds)
                doAction { engine.play(seat, cards) }
            }
            is NetMsg.Pass -> onGameThread { doAction { engine.pass(seat) } }
            is NetMsg.Chat -> relayChat(seat, msg.text, msg.sound)
            is NetMsg.Ping -> sendToClient(seat, NetMsg.Pong)
            is NetMsg.Start, is NetMsg.Ready -> {} // 仅房主可开始
            else -> {}
        }
    }

    // ------------------------------------------------ 房主操作（座位 0）

    fun setAiLevel(level: Int) {
        aiLevel = level
        broadcastRoom()
    }

    /** 开局：空位 AI 补位 */
    fun startGame() {
        onGameThread {
            if (started) return@onGameThread
            started = true
            advertiser?.started = true
            val seats = roomSeats.value
            val infos = (0..2).map { s ->
                val rs = seats[s]
                when {
                    s == 0 -> PlayerInfo(0, hostName, hostAvatar, false)
                    rs.isAi || rs.name.isEmpty() ->
                        PlayerInfo(s, aiNameFor(s), aiAvatarFor(s), true, levelOf())
                    else -> PlayerInfo(s, rs.name, rs.avatar, false)
                }
            }
            engine.newGame(infos)
            broadcast(engine.events.map { it.toNet() })
            broadcastRoom()
        }
    }

    private fun aiNameFor(seat: Int): String =
        (when (aiLevel) { 0 -> "庄稼汉"; 2 -> "老江湖"; else -> "赶集人" }) + seat

    private fun aiAvatarFor(seat: Int): Int = if (seat == 1) 5 else 2

    fun hostBid(yes: Boolean) = onGameThread {
        doAction {
            if (engine.phase == Phase.BIDDING) engine.callLandlord(0, yes)
            else engine.robLandlord(0, yes)
        }
    }

    fun hostPlay(cardIds: List<Int>) = onGameThread {
        val cards = Deck.byIds(cardIds)
        doAction { engine.play(0, cards) }
    }

    fun hostPass() = onGameThread { doAction { engine.pass(0) } }

    fun hostChat(text: String, sound: Int) = relayChat(0, text, sound)

    fun restart() = onGameThread {
        if (!started) return@onGameThread
        engine.newGame(engine.players.map { it.info })
        broadcast(engine.events.map { it.toNet() })
        broadcastRoom()
    }

    fun backToRoom() = onGameThread {
        started = false
        advertiser?.started = false
        hostSnapshot.value = null
        broadcastRoom()
    }

    private fun relayChat(seat: Int, text: String, sound: Int) {
        chatFlow.value = Triple(seat, text, sound)
        snapshotClients().forEach { (_, c) -> sendTo(c.writer, NetMsg.ChatBroadcast(seat, text, sound)) }
    }

    // ------------------------------------------------ 引擎调度

    private fun onGameThread(block: suspend () -> Unit) {
        scope.launch(gameDispatcher) { block() }
    }

    /** 执行动作 → 收集事件 → 广播 */
    private fun doAction(block: () -> Unit) {
        engine.events.clear()
        block()
        broadcast(engine.events.map { it.toNet() })
    }

    /** 广播当前状态（所有连接 + 房主），并驱动 AI */
    private fun broadcast(effects: List<NetMsg.Effect>) {
        val conns = snapshotClients()
        conns.forEach { (seat, conn) ->
            sendTo(conn.writer, NetMsg.Snapshot(engine.snapshotFor(seat), effects))
        }
        hostSnapshot.value = NetMsg.Snapshot(engine.snapshotFor(0), effects)
        scheduleAi()
    }

    private fun broadcastRoom() {
        val room = NetMsg.Room(roomSeats.value, started, aiLevel, NetUtils.localIpAddress() ?: "?")
        snapshotClients().forEach { (_, c) -> sendTo(c.writer, room) }
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

    /** AI 出牌兜底链：提议 → 过牌 → 最小领出（防卡局） */
    private fun aiActSafe(engine: GameEngine, actor: Int, move: List<Card>?): Boolean {
        if (move != null && engine.play(actor, move)) return true
        if (engine.pass(actor)) return true
        val leads = MoveGen.genLeads(engine.myHand(actor))
        if (leads.isNotEmpty()) return engine.play(actor, leads.first().cards)
        return false
    }

    // ------------------------------------------------ AI 驱动

    private fun scheduleAi() {
        aiJob?.cancel()
        if (engine.phase !in setOf(Phase.BIDDING, Phase.ROBBING, Phase.PLAYING)) return

        val actor = when (engine.phase) {
            Phase.BIDDING -> engine.snapshotFor(0).bidCursor
            Phase.ROBBING -> engine.snapshotFor(0).robCursor
            else -> engine.currentTurn
        }
        if (actor < 0) return
        val player = engine.players.getOrNull(actor) ?: return
        if (!player.info.isAi) return

        val ctx = aiContext(actor)
        val ai = AiPlayer(actor, player.info.aiLevel ?: AiLevel.MEDIUM)
        val think = when (player.info.aiLevel) {
            AiLevel.EASY -> 700; AiLevel.HARD -> 1300; else -> 1000
        } + Random.nextLong(500)

        aiJob = scope.launch(gameDispatcher) {
            delay(think)
            val bidCursorNow = engine.snapshotFor(0).bidCursor
            val robCursorNow = engine.snapshotFor(0).robCursor
            when {
                engine.phase == Phase.BIDDING && actor == bidCursorNow ->
                    doAction { engine.callLandlord(actor, ai.shouldCall(ctx)) }
                engine.phase == Phase.ROBBING && actor == robCursorNow ->
                    doAction { engine.robLandlord(actor, ai.shouldRob(ctx)) }
                engine.phase == Phase.PLAYING && actor == engine.currentTurn -> {
                    doAction { aiActSafe(engine, actor, ai.chooseMove(ctx)) }
                }
            }
        }
    }

    private fun aiContext(seat: Int): AiContext = AiContext(
        seat = seat,
        hand = engine.myHand(seat),
        lastMove = engine.lastMove,
        lastMoveSeat = engine.lastMoveSeat,
        landlord = engine.landlord,
        handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
        playedCards = engine.players.flatMap { it.played }
    )

    // ------------------------------------------------ 事件映射

    private fun GameEngine.GameEvent.toNet(): NetMsg.Effect = when (this) {
        is GameEngine.GameEvent.Shuffle -> NetMsg.Effect("shuffle")
        is GameEngine.GameEvent.Redeal -> NetMsg.Effect("redeal")
        is GameEngine.GameEvent.TurnTo -> NetMsg.Effect("turn", seat)
        is GameEngine.GameEvent.BidCall -> NetMsg.Effect("bid_call", seat)
        is GameEngine.GameEvent.BidPass -> NetMsg.Effect("bid_pass", seat)
        is GameEngine.GameEvent.Rob -> NetMsg.Effect("rob", seat)
        is GameEngine.GameEvent.RobPass -> NetMsg.Effect("rob_pass", seat)
        is GameEngine.GameEvent.LandlordSet -> NetMsg.Effect("landlord_set", seat)
        is GameEngine.GameEvent.Played -> NetMsg.Effect("played", seat)
        is GameEngine.GameEvent.Pass -> NetMsg.Effect("pass", seat)
        is GameEngine.GameEvent.NewRound -> NetMsg.Effect("new_round", leader)
        is GameEngine.GameEvent.Bomb -> NetMsg.Effect("bomb", seat, rocket)
        is GameEngine.GameEvent.Plane -> NetMsg.Effect("plane", seat)
        is GameEngine.GameEvent.GameOver -> NetMsg.Effect("game_over", landlordWon = landlordWon)
    }

    // ------------------------------------------------ 基础 IO

    private fun sendTo(writer: BufferedWriter, msg: NetMsg) {
        try {
            synchronized(writer) {
                writer.write(Protocol.encode(msg))
                writer.newLine()
                writer.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun sendToClient(seat: Int, msg: NetMsg) {
        snapshotClients().firstOrNull { it.first == seat }?.let { sendTo(it.second.writer, msg) }
    }

    /** 记牌器（房主本地视角 seat 0） */
    fun remainingCounts(): Map<Int, Int> =
        if (started) engine.remainingCounts(0) else emptyMap()
}
