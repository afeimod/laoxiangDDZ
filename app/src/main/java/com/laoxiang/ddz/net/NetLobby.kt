package com.laoxiang.ddz.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.laoxiang.ddz.data.CardSuit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 非斗地主游戏（掼蛋/升级/跑得快/锄大地）的共用联机会话（v20）
 *
 * 职责：
 * - 建房：GameHost（权威引擎）+ UDP 广播
 * - 加入：LanClient（回显快照）
 * - 房间扫描（跨游戏浏览：列表带游戏标签）
 * - 各游戏 ViewModel 的操作路由与状态出口
 *
 * 斗地主联机不走这里（沿用原 GameViewModel + LanHost，避免回归）。
 */
object NetLobby : com.laoxiang.ddz.ui.room.NetRoomUi {

    /** 当前会话所属游戏：guandan / shengji / pdk / bigtwo；null=无联机会话 */
    @Volatile
    var activeGame: String? = null
        private set

    /** 当前会话座位数 */
    @Volatile
    var activeSeatCount: Int = 4
        private set

    @Volatile
    var isHost: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------ 会话状态

    private val _mySeat = MutableStateFlow(-1)

    private val _gSnapshot = MutableStateFlow<NetMsg.GSnapshot?>(null)
    /** 当前会话的通用对局快照（含特效），各游戏 ViewModel 解码自己的类型 */
    val gSnapshot: StateFlow<NetMsg.GSnapshot?> = _gSnapshot.asStateFlow()

    private val _chat = MutableStateFlow<Triple<Int, String, Int>?>(null)
    val chatFlow: StateFlow<Triple<Int, String, Int>?> = _chat.asStateFlow()

    private val _roomSeats = MutableStateFlow<List<NetMsg.SeatInfo>>(emptyList())
    private val _roomStarted = MutableStateFlow(false)
    private val _hostIp = MutableStateFlow<String?>(null)
    private val _connectState = MutableStateFlow<String?>(null)
    private val _notice = MutableStateFlow<String?>(null)
    private val _foundRooms = MutableStateFlow<List<RoomBroadcast>>(emptyList())
    private val _kicked = MutableStateFlow(false)
    val kicked: StateFlow<Boolean> = _kicked.asStateFlow()

    private var host: GameHost? = null
    private var client: LanClient? = null
    private var scanner: RoomScanner? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ------------------------------------------------ 生命周期

    /** 建房（game = guandan/shengji/pdk/bigtwo；pdk 三人传 seatCount=3） */
    fun startHost(game: String, seatCount: Int, hostName: String, hostAvatar: Int) {
        leave()
        activeGame = game
        activeSeatCount = seatCount
        isHost = true
        acquireWifiLock()
        val h = GameHost(
            hostName = hostName.ifBlank { "房主" },
            hostAvatar = hostAvatar,
            seatCount = seatCount,
            scope = scope
        ) { gs, trig -> GameDrivers.create(game, gs, trig, seatCount) }
        host = h
        h.roomSeats.onEach { _roomSeats.value = it }.launchIn(scope)
        h.hostSnapshot.onEach { msg ->
            _gSnapshot.value = msg
            _mySeat.value = 0
        }.launchIn(scope)
        h.chatFlow.onEach { _chat.value = it }.launchIn(scope)
        h.noticeFlow.onEach { _notice.value = it }.launchIn(scope)
        h.setAiLevel(prefsAiLevel)
        h.start()
        _hostIp.value = NetUtils.localIpAddress()
        _connectState.value = null
    }

    /** 加入（加入前 UI 已从房间列表/手动选择得知游戏与座位数） */
    fun startClient(ip: String, game: String, seatCount: Int) {
        leave()
        activeGame = game
        activeSeatCount = seatCount
        isHost = false
        acquireWifiLock()
        val c = LanClient(scope)
        client = c
        c.mySeat.onEach { _mySeat.value = it }.launchIn(scope)
        c.roomState.onEach { r ->
            r?.let {
                _roomSeats.value = it.seats
                _roomStarted.value = it.started
                _hostIp.value = it.hostIp
            }
        }.launchIn(scope)
        c.gSnapshot.onEach { _gSnapshot.value = it }.launchIn(scope)
        c.chatFlow.onEach { _chat.value = it }.launchIn(scope)
        c.connectError.onEach { _connectState.value = it }.launchIn(scope)
        c.kicked.onEach { _kicked.value = it }.launchIn(scope)
        c.connect(ip, prefsNickname.ifBlank { "老乡" }, prefsAvatar)
    }

    /** 断开/关房（退出对局或退出房间时调用） */
    fun leave() {
        releaseWifiLock()
        host?.stop(); host = null
        client?.disconnect(); client = null
        stopScan()
        activeGame = null
        activeSeatCount = 4
        isHost = false
        _mySeat.value = -1
        _gSnapshot.value = null
        _chat.value = null
        _roomSeats.value = emptyList()
        _roomStarted.value = false
        _connectState.value = null
        _notice.value = null
        _kicked.value = false
    }

    /** 对局是否进行中（房间页据此自动进入对局界面） */
    private fun phaseLive(p: String?): Boolean =
        p in setOf("BIDDING", "ROBBING", "BURYING", "PLAYING")

    private fun phaseOf(msg: NetMsg.GSnapshot?): String? {
        val obj = msg?.payload as? JsonObject ?: return null
        return (obj["phase"] as? JsonPrimitive)?.content
    }

    // ------------------------------------------------ NetRoomUi 实现

    override val roomSeats: StateFlow<List<NetMsg.SeatInfo>> get() = _roomSeats.asStateFlow()
    override val roomStarted: StateFlow<Boolean> get() = _roomStarted.asStateFlow()
    override val hostIp: StateFlow<String?> get() = _hostIp.asStateFlow()
    override val connectState: StateFlow<String?> get() = _connectState.asStateFlow()
    override val notice: StateFlow<String?> get() = _notice.asStateFlow()
    override val foundRooms: StateFlow<List<RoomBroadcast>> get() = _foundRooms.asStateFlow()
    override val mySeat: StateFlow<Int> get() = _mySeat.asStateFlow()

    override val gameLive: StateFlow<Boolean> = _gSnapshot
        .map { phaseLive(phaseOf(it)) && it?.game == activeGame }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _aiLevelUi = MutableStateFlow(1)
    override val aiLevelUi: StateFlow<Int> get() = _aiLevelUi.asStateFlow()

    override val seatCount: Int get() = activeSeatCount
    override val isHostSide: Boolean get() = isHost

    override fun joinByIp(game: String, ip: String) {
        val (g, seats) = com.laoxiang.ddz.ui.room.parseNetGame(game)
        if (g == "ddz") {
            // 斗地主房间：交给 GameViewModel（MainActivity 注入的钩子）
            stopScan()
            onJoinDdz?.invoke(ip)
        } else {
            startClient(ip, g, seats)
        }
    }

    override fun startScan(context: Context) {
        scanImpl(context)
    }

    override fun stopScan() { stopScanImpl() }

    override fun leaveRoom() { leave() }

    /** 手动 IP 连斗地主房时的转接钩子（MainActivity 设置） */
    @Volatile
    var onJoinDdz: ((String) -> Unit)? = null

    // ------------------------------------------------ 房间扫描

    private fun scanImpl(context: Context) {
        stopScanImpl()
        scanner = RoomScanner(context, scope) { rooms ->
            _foundRooms.value = rooms.filter { it.ip != NetUtils.localIpAddress() }
        }
        scanner?.start()
    }

    private fun stopScanImpl() {
        scanner?.stop()
        scanner = null
    }

    fun startScanCompat(context: Context) = scanImpl(context)

    // ------------------------------------------------ 房主操作

    override fun hostSetAiLevel(level: Int) {
        prefsAiLevel = level
        _aiLevelUi.value = level
        host?.setAiLevel(level)
    }

    override fun hostStartGame() {
        _gSnapshot.value = null
        host?.startGame()
    }

    fun hostPlay(ids: List<Int>) { host?.hostPlay(ids) }
    fun hostPass() { host?.hostPass() }
    fun hostClaim(suit: CardSuit?) { host?.hostClaim(suit) }
    fun hostNextHand() { host?.hostNextHand() }
    fun hostRestart() { host?.hostRestart() }
    fun hostSettle() { host?.hostSettle() }
    fun hostAutoBury() { host?.hostAutoBury() }

    // ------------------------------------------------ 客户端操作

    fun sendPlay(ids: List<Int>) { client?.play(ids) }
    fun sendPass() { client?.pass() }
    fun sendClaim(suit: CardSuit?) { client?.claim(suit) }
    fun sendNextHand() { client?.nextHand() }
    fun sendRestart() { client?.restart() }

    fun chat(text: String, sound: Int) {
        if (isHost) host?.hostChat(text, sound) else client?.chat(text, sound)
    }

    // ------------------------------------------------ 挂机偏好（由 UI 注入）

    /** 房主 AI 难度（房间页读写） */
    @Volatile
    var prefsAiLevel: Int = 1
        set(value) {
            field = value
            _aiLevelUi.value = value
        }

    @Volatile
    var prefsNickname: String = ""

    @Volatile
    var prefsAvatar: Int = 0

    // ------------------------------------------------ WiFi 低延迟锁（v19 同款）

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wifi = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "laotable_lowlat")
            } else {
                @Suppress("DEPRECATION")
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "laotable_perf")
            }
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        }
    }

    private fun releaseWifiLock() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    /** 应用上下文（MainActivity onCreate 时注入，用于 MulticastLock/WifiLock） */
    @Volatile
    var appContext: Context? = null
}
