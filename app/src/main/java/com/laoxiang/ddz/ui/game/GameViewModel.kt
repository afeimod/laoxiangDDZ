package com.laoxiang.ddz.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoxiang.ddz.LaoXiangApp
import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.net.*
import com.laoxiang.ddz.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.random.Random

/** 游戏模式 */
enum class GameMode { SINGLE, HOST, CLIENT }

/** 统一的视觉/音效事件 */
sealed class Fx {
    object Shuffle : Fx()
    object Redeal : Fx()
    data class BidCall(val seat: Int) : Fx()
    data class BidPass(val seat: Int) : Fx()
    data class Rob(val seat: Int) : Fx()
    data class RobPass(val seat: Int) : Fx()
    data class LandlordSet(val seat: Int) : Fx()
    data class Played(val seat: Int) : Fx()
    data class Pass(val seat: Int) : Fx()
    data class NewRound(val leader: Int) : Fx()
    data class Bomb(val seat: Int, val rocket: Boolean) : Fx()
    data class Plane(val seat: Int) : Fx()
    data class GameOver(val landlordWon: Boolean, val spring: Boolean) : Fx()
    data class ChatBubble(val seat: Int, val text: String) : Fx()
    data class Turn(val seat: Int) : Fx()
}

/** 聊天气泡数据 */
data class ChatBubble(val seat: Int, val text: String, val at: Long)

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val sound = (app as LaoXiangApp).sound
    val prefs = Prefs(app)

    // ------------------------------------------------ 公开状态

    val mode = MutableStateFlow(GameMode.SINGLE)

    /** 当前快照（任意模式统一） */
    val snapshot = MutableStateFlow<GameSnapshot?>(null)

    /** 我的座位（-1 = 未上桌；防止加入牌局页误判为已加入） */
    val mySeat = MutableStateFlow(-1)

    /** 选中的牌 id */
    val selected = MutableStateFlow<Set<Int>>(emptySet())

    /** 当前提示（循环） */
    private var hintIndex = -1
    val hintCards = MutableStateFlow<List<Card>?>(null)

    /** 记牌器 */
    val cardCounter = MutableStateFlow<Map<Int, Int>>(emptyMap())

    /** 一次性特效/音效事件流 */
    val fx = MutableSharedFlow<Fx>(extraBufferCapacity = 64)

    /** 聊天气泡 */
    val chatBubbles = MutableStateFlow<List<ChatBubble>>(emptyList())

    /** 房间状态（联机） */
    val roomSeats = MutableStateFlow<List<NetMsg.SeatInfo>>(emptyList())
    val roomStarted = MutableStateFlow(false)
    val hostIp = MutableStateFlow<String?>(null)
    val connectState = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)

    /** 发现的房间 */
    val foundRooms = MutableStateFlow<List<RoomBroadcast>>(emptyList())

    /** 战报（本局分数，正负） */
    val lastScore = MutableStateFlow(0)

    // ------------------------------------------------ 内部

    private val engine = GameEngine()                     // 单机模式
    private var host: LanHost? = null
    private var client: LanClient? = null
    private var scanner: RoomScanner? = null

    private fun soundEnabled() = prefs.soundEnabled

    private fun emit(f: Fx) {
        fx.tryEmit(f)
        // 同步音效
        when (f) {
            is Fx.Shuffle -> sound.play("shuffle")
            is Fx.BidCall -> sound.play("jiaofen")
            is Fx.BidPass -> sound.play("buqiang")
            is Fx.Rob -> sound.play("qiang")
            is Fx.RobPass -> sound.play("buqiang")
            is Fx.Played -> sound.play("play")
            is Fx.Pass -> sound.play("pass")
            // 王炸（双王）才用王炸音效；普通炸弹用专用爆炸音效
            is Fx.Bomb -> sound.play(if (f.rocket) "wangzha" else "bomb")
            is Fx.Plane -> sound.play("plane")
            is Fx.GameOver -> sound.play(
                if ((f.landlordWon && mySeat.value == snapshot.value?.landlord) ||
                    (!f.landlordWon && mySeat.value != snapshot.value?.landlord)) "win" else "lose"
            )
            is Fx.ChatBubble -> {}
            else -> {}
        }
    }

    /** 单机 AI 出牌兜底链：提议牌 → 过牌 → 最小领出，杜绝卡局 */
    private fun aiActSafe(actor: Int, move: List<Card>?): Boolean {
        if (move != null && engine.play(actor, move)) return true
        if (engine.pass(actor)) return true
        val leads = MoveGen.genLeads(engine.myHand(actor))
        if (leads.isNotEmpty()) return engine.play(actor, leads.first().cards)
        return false
    }

    // ================================================= 单机模式

    fun startSingle(aiLevel: Int) {
        mode.value = GameMode.SINGLE
        prefs.aiLevel = aiLevel
        selected.value = emptySet()
        hintCards.value = null
        hintIndex = -1
        val level = when (aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }
        val infos = listOf(
            PlayerInfo(0, prefs.nickname.ifBlank { "我" }, prefs.avatar, false),
            PlayerInfo(1, "赶集人${level.label}", 5, true, level),
            PlayerInfo(2, "老烟枪${level.label}", 2, true, level)
        )
        engine.newGame(infos)
        publishSingle(effects = true)
        sound.startBgm()
        pumpSingleAi()
    }

    private fun publishSingle(effects: Boolean = false) {
        snapshot.value = engine.snapshotFor(0)
        mySeat.value = 0
        cardCounter.value = engine.remainingCounts(0)
        if (effects) engine.events.mapNotNull { it.toFx() }.forEach { emit(it) }
        handleResultForScore()
    }

    private fun GameEngine.GameEvent.toFx(): Fx? = when (this) {
        is GameEngine.GameEvent.Shuffle -> Fx.Shuffle
        is GameEngine.GameEvent.Redeal -> Fx.Redeal
        is GameEngine.GameEvent.TurnTo -> null   // 回合切换不播音效
        is GameEngine.GameEvent.BidCall -> Fx.BidCall(seat)
        is GameEngine.GameEvent.BidPass -> Fx.BidPass(seat)
        is GameEngine.GameEvent.Rob -> Fx.Rob(seat)
        is GameEngine.GameEvent.RobPass -> Fx.RobPass(seat)
        is GameEngine.GameEvent.LandlordSet -> Fx.LandlordSet(seat)
        is GameEngine.GameEvent.Played -> Fx.Played(seat)
        is GameEngine.GameEvent.Pass -> Fx.Pass(seat)
        is GameEngine.GameEvent.NewRound -> Fx.NewRound(leader)
        is GameEngine.GameEvent.Bomb -> Fx.Bomb(seat, rocket)
        is GameEngine.GameEvent.Plane -> Fx.Plane(seat)
        is GameEngine.GameEvent.GameOver -> Fx.GameOver(landlordWon, spring || antiSpring)
    }

    /** 单机：驱动 AI 叫抢/出牌（链式） */
    private fun pumpSingleAi() {
        if (mode.value != GameMode.SINGLE) return
        val snap = engine.snapshotFor(0)
        val actor = when (engine.phase) {
            Phase.BIDDING -> snap.bidCursor
            Phase.ROBBING -> snap.robCursor
            Phase.PLAYING -> engine.currentTurn
            else -> return
        }
        if (actor <= 0) return
        val player = engine.players.getOrNull(actor) ?: return
        if (!player.info.isAi) return

        val ctx = AiContext(
            seat = actor,
            hand = engine.myHand(actor),
            lastMove = engine.lastMove,
            lastMoveSeat = engine.lastMoveSeat,
            landlord = engine.landlord,
            handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
            playedCards = engine.players.flatMap { it.played }
        )
        val ai = AiPlayer(actor, player.info.aiLevel ?: AiLevel.MEDIUM)
        val think = (when (player.info.aiLevel) {
            AiLevel.EASY -> 700; AiLevel.HARD -> 1300; else -> 1000
        }) + Random.nextLong(500)

        viewModelScope.launch {
            delay(think)
            if (mode.value != GameMode.SINGLE) return@launch
            engine.events.clear()
            var acted = false
            when {
                engine.phase == Phase.BIDDING && actor == engine.snapshotFor(0).bidCursor -> {
                    acted = engine.callLandlord(actor, ai.shouldCall(ctx))
                }
                engine.phase == Phase.ROBBING && actor == engine.snapshotFor(0).robCursor -> {
                    acted = engine.robLandlord(actor, ai.shouldRob(ctx))
                }
                engine.phase == Phase.PLAYING && actor == engine.currentTurn -> {
                    val move = ai.chooseMove(ctx)
                    acted = aiActSafe(actor, move)
                }
            }
            if (acted) {
                publishSingle(effects = true)
                // AI 偶尔催促/嘲讽
                if (engine.phase == Phase.PLAYING && engine.currentTurn == 0 && Random.nextFloat() < 0.12f) {
                    pushChat(1, "快点吧，我等到花儿都谢了")
                    sound.play("kuaidian", 0.8f)
                }
                pumpSingleAi()
            }
        }
    }

    // ================================================= 房主模式

    fun startHost() {
        mode.value = GameMode.HOST
        mySeat.value = 0
        val h = LanHost(
            hostName = prefs.nickname.ifBlank { "房主" },
            hostAvatar = prefs.avatar,
            scope = viewModelScope
        )
        host = h
        h.roomSeats.onEach { roomSeats.value = it }.launchIn(viewModelScope)
        h.hostSnapshot.onEach { snapMsg ->
            snapshot.value = snapMsg?.snapshot
            mySeat.value = 0
            cardCounter.value = h.remainingCounts()
            snapMsg?.effects?.forEach { e -> emit(e.toFx()) }
            handleResultForScore()
        }.launchIn(viewModelScope)
        h.chatFlow.onEach { chat ->
            chat?.let { (seat, text, _) -> pushChat(seat, text) }
        }.launchIn(viewModelScope)
        h.noticeFlow.onEach { notice.value = it }.launchIn(viewModelScope)
        h.setAiLevel(prefs.aiLevel)
        h.start()
        hostIp.value = NetUtils.localIpAddress()
        connectState.value = null
        sound.startBgm()
    }

    fun hostSetAiLevel(level: Int) {
        prefs.aiLevel = level
        host?.setAiLevel(level)
    }

    fun hostStartGame() {
        host?.startGame()
        snapshot.value = null
    }

    fun hostRestart() {
        selected.value = emptySet()
        hintCards.value = null
        host?.restart()
    }

    fun hostBackToRoom() {
        sound.stopBgm()
        host?.backToRoom()
        snapshot.value = null
    }

    private fun NetMsg.Effect.toFx(): Fx = when (type) {
        "shuffle" -> Fx.Shuffle
        "redeal" -> Fx.Redeal
        "bid_call" -> Fx.BidCall(seat)
        "bid_pass" -> Fx.BidPass(seat)
        "rob" -> Fx.Rob(seat)
        "rob_pass" -> Fx.RobPass(seat)
        "landlord_set" -> Fx.LandlordSet(seat)
        "played" -> Fx.Played(seat)
        "pass" -> Fx.Pass(seat)
        "new_round" -> Fx.NewRound(seat)
        "turn" -> Fx.Turn(seat)
        "bomb" -> Fx.Bomb(seat, rocket)
        "plane" -> Fx.Plane(seat)
        "game_over" -> Fx.GameOver(landlordWon, false)
        else -> Fx.NewRound(seat)
    }

    // ================================================= 客户端模式

    fun startClient(ip: String) {
        mode.value = GameMode.CLIENT
        val c = LanClient(viewModelScope)
        client = c
        c.mySeat.onEach { mySeat.value = it }.launchIn(viewModelScope)
        c.roomState.onEach { r ->
            r?.let {
                roomSeats.value = it.seats
                roomStarted.value = it.started
                hostIp.value = it.hostIp
            }
        }.launchIn(viewModelScope)
        c.snapshot.onEach { snapMsg ->
            snapMsg?.let { m ->
                snapshot.value = m.snapshot
                cardCounter.value = c.remainingCounts()
                m.effects.forEach { e -> emit(e.toFx()) }
                handleResultForScore()
                if (m.snapshot.phase in setOf(Phase.PLAYING, Phase.BIDDING, Phase.ROBBING)) {
                    sound.startBgm()
                }
            }
        }.launchIn(viewModelScope)
        c.chatFlow.onEach { chat ->
            chat?.let { (seat, text, _) -> pushChat(seat, text) }
        }.launchIn(viewModelScope)
        c.kicked.onEach {
            if (it) {
                notice.value = "你已被请出房间"
                leaveGame()
            }
        }.launchIn(viewModelScope)
        c.connectError.onEach { err ->
            connectState.value = err
        }.launchIn(viewModelScope)
        c.connect(ip, prefs.nickname.ifBlank { "老乡" }, prefs.avatar)
    }

    /** 开始扫描房间 */
    fun startScan(context: android.content.Context) {
        stopScan()
        scanner = RoomScanner(context, viewModelScope) { rooms ->
            // 剔除自己开的房
            foundRooms.value = rooms.filter { it.ip != NetUtils.localIpAddress() }
        }
        scanner?.start()
    }

    fun stopScan() {
        scanner?.stop()
        scanner = null
    }

    // ================================================= 通用操作（本地玩家）

    fun toggleSelect(cardId: Int) {
        val cur = selected.value
        selected.value = if (cardId in cur) cur - cardId else cur + cardId
        if (selected.value.isNotEmpty()) sound.play("select", 0.6f)
        hintCards.value = null
        hintIndex = -1
    }

    /** 滑动多选：扫过的牌只加不减，带轻点音效 */
    fun selectCards(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val cur = selected.value
        val add = ids.filter { it !in cur }
        if (add.isEmpty()) return
        selected.value = cur + add
        sound.play("select", 0.45f)
        hintCards.value = null
        hintIndex = -1
    }

    fun clearSelection() {
        selected.value = emptySet()
        hintCards.value = null
        hintIndex = -1
    }

    /** 出牌；返回错误信息给 UI 提示（null=成功） */
    fun playSelected(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        val ids = selected.value
        if (ids.isEmpty()) return "请先选牌"
        val hand = snap.seats.first { it.seat == mySeat.value }.hand
        val cards = hand.filter { it.id in ids }
        val move = Move.of(cards) ?: return "不是有效牌型"
        if (!move.beats(snap.lastMove)) return "压不过上家"

        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                val ok = engine.play(0, cards)
                if (!ok) return "出牌无效"
                clearSelection()
                publishSingle(effects = true)
                pumpSingleAi()
            }
            GameMode.HOST -> {
                host?.hostPlay(ids.toList())
                clearSelection()
            }
            GameMode.CLIENT -> {
                client?.play(ids.toList())
                clearSelection()
            }
        }
        return null
    }

    /** 过牌 */
    fun passTurn(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        if (snap.lastMove == null) return "轮到你先出，不能不要"
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                val ok = engine.pass(0)
                if (!ok) return "不能过牌"
                clearSelection()
                publishSingle(effects = true)
                pumpSingleAi()
            }
            GameMode.HOST -> host?.hostPass()
            GameMode.CLIENT -> client?.pass()
        }
        clearSelection()
        return null
    }

    /** 叫 / 抢 */
    fun bid(yes: Boolean): String? {
        val snap = snapshot.value ?: return "尚未开局"
        val my = mySeat.value
        if (snap.phase == Phase.BIDDING && snap.bidCursor != my) return "还没轮到你"
        if (snap.phase == Phase.ROBBING && snap.robCursor != my) return "还没轮到你"
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                val ok = if (snap.phase == Phase.BIDDING) engine.callLandlord(0, yes)
                else engine.robLandlord(0, yes)
                if (!ok) return "现在不能操作"
                publishSingle(effects = true)
                pumpSingleAi()
            }
            GameMode.HOST -> host?.hostBid(yes)
            GameMode.CLIENT -> client?.bid(yes)
        }
        return null
    }

    /** 提示：循环给出可压过上家的最小牌型 */
    fun hint(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        val hand = snap.seats.first { it.seat == mySeat.value }.hand
        val all = MoveGen.genBeats(hand, snap.lastMove)
        if (all.isEmpty()) return "没有能大过上家的牌"
        val nonBomb = all.filter { !it.type.isBombLike }
        val ordered = nonBomb.ifEmpty { all }.sortedBy { it.mainRank }
        hintIndex = (hintIndex + 1) % ordered.size
        val pick = ordered[hintIndex]
        selected.value = pick.cards.map { it.id }.toSet()
        hintCards.value = pick.cards
        return null
    }

    /** 快捷聊天 */
    fun sendChat(text: String) {
        when (mode.value) {
            GameMode.SINGLE -> {
                pushChat(0, text)
                if (text.contains("快点")) sound.play("kuaidian", 0.8f)
            }
            GameMode.HOST -> {
                host?.hostChat(text, if (text.contains("快点")) 1 else 0)
                pushChat(0, text)
            }
            GameMode.CLIENT -> client?.chat(text, if (text.contains("快点")) 1 else 0)
        }
    }

    private fun pushChat(seat: Int, text: String) {
        val now = System.currentTimeMillis()
        chatBubbles.value = (chatBubbles.value + ChatBubble(seat, text, now))
            .filter { now - it.at < 4000 }
            .takeLast(6)
    }

    /** 清理过期气泡（UI 定时调用） */
    fun trimBubbles() {
        val now = System.currentTimeMillis()
        chatBubbles.value = chatBubbles.value.filter { now - it.at < 3500 }
    }

    // ================================================= 退出/清理

    fun leaveGame() {
        sound.stopBgm()
        when (mode.value) {
            GameMode.HOST -> {
                host?.stop()
                host = null
            }
            GameMode.CLIENT -> {
                client?.disconnect()
                client = null
            }
            GameMode.SINGLE -> {}
        }
        stopScan()
        snapshot.value = null
        roomSeats.value = emptyList()
        roomStarted.value = false
        chatBubbles.value = emptyList()
        selected.value = emptySet()
        connectState.value = null
        mySeat.value = -1          // 下桌后回到“未上桌”状态，重新进加入页可先选牌局
    }

    override fun onCleared() {
        sound.stopBgm()
        host?.stop()
        client?.disconnect()
        scanner?.stop()
        super.onCleared()
    }

    // ================================================= 结算

    private var scoredRound = -1
    private var seenRound = -1

    private fun handleResultForScore() {
        val snap = snapshot.value ?: return
        if (snap.round != seenRound) {           // 新一局：解除去重锁
            seenRound = snap.round
            scoredRound = -1
        }
        val r = snap.result ?: return
        if (scoredRound == snap.round) return   // 本局已记过分
        scoredRound = snap.round
        val my = mySeat.value
        val iAmLandlord = my == snap.landlord
        val iWon = if (iAmLandlord) r.landlordWon else !r.landlordWon
        val myScore = if (iAmLandlord) r.landlordScore else -r.landlordScore / 2
        lastScore.value = if (iWon) kotlin.math.abs(myScore) else -kotlin.math.abs(myScore)
        if (mode.value == GameMode.SINGLE) {
            prefs.addResult(iWon, lastScore.value)
        } else if (mode.value == GameMode.HOST) {
            prefs.addResult(iWon, lastScore.value)
        }
    }

    fun soundSettings(on: Boolean) {
        prefs.soundEnabled = on
        sound.soundEnabled = on
    }

    /** UI 层直接播放短音效（发牌逐张等） */
    fun sfx(key: String, vol: Float = 1f) = sound.play(key, vol)

    fun musicSettings(on: Boolean) {
        prefs.musicEnabled = on
        sound.musicEnabled = on
        if (on) sound.startBgm() else sound.stopBgm()
    }
}
