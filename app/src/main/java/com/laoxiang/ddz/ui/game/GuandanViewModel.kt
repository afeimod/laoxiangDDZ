package com.laoxiang.ddz.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoxiang.ddz.LaoXiangApp
import com.laoxiang.ddz.audio.VoiceMap
import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.net.NetLobby
import com.laoxiang.ddz.net.NetMsg
import com.laoxiang.ddz.net.netJson
import com.laoxiang.ddz.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.random.Random

/**
 * 掼蛋视图模型：单机 vs AI（4人 2v2），多副牌连续对局，双上过 A 终局。
 * v20：支持本地联机（HOST=房主权威 / CLIENT=回显快照），网络层走 NetLobby。
 */
class GuandanViewModel(app: Application) : AndroidViewModel(app) {

    private val sound = (app as LaoXiangApp).sound
    val prefs = Prefs(app)

    val snapshot = MutableStateFlow<GdSnapshot?>(null)
    val selected = MutableStateFlow<Set<Int>>(emptySet())
    val lastScore = MutableStateFlow(0)
    val fx = MutableSharedFlow<Fx>(extraBufferCapacity = 64)

    /** 游戏模式（v20） */
    val mode = MutableStateFlow(GameMode.SINGLE)

    /** 我的座位（联机由房主分配；单机恒 0） */
    val mySeat = MutableStateFlow(0)

    /** 联机操作未送达提示（回声监测超时） */
    val opNotice = MutableStateFlow<String?>(null)

    fun clearOpNotice() { opNotice.value = null }

    private val engine = GuandanEngine()
    private var hintIndex = -1

    /** AI 泵链代数：开局/下一副时作废旧残留的思考协程（VM 为 Activity 级单例，
     *  中途退出重进后旧协程若仅靠 turn==actor 守卫可能在新一副里瞬间乱出牌） */
    private var pumpEpoch = 0

    // ------------------------------------------------ 联机绑定（v20）

    private val netJobs = ArrayList<kotlinx.coroutines.Job>()
    private var netBound = false

    /** 进入联机对局页前调用（幂等）：把 NetLobby 快照/座位/喊话接入本 VM */
    fun bindNet() {
        if (netBound) return
        netBound = true
        mode.value = if (NetLobby.isHost && NetLobby.activeGame == "guandan") GameMode.HOST else GameMode.CLIENT
        mySeat.value = NetLobby.mySeat.value
        netJobs += NetLobby.mySeat.onEach { mySeat.value = it }.launchIn(viewModelScope)
        netJobs += NetLobby.gSnapshot.onEach { msg ->
            if (msg?.game != "guandan") return@onEach
            val snap = runCatching {
                netJson.decodeFromJsonElement(GdSnapshot.serializer(), msg.payload)
            }.getOrNull() ?: return@onEach
            snapshot.value = snap
            msg.effects.forEach { emitEffect(it) }
            handleScore()
        }.launchIn(viewModelScope)
        netJobs += NetLobby.chatFlow.onEach { c ->
            c?.let { (_, _, code) -> VoiceMap.forChat(code)?.let { sound.play(it, 0.95f) } }
        }.launchIn(viewModelScope)
    }

    private fun emitEffect(e: NetMsg.Effect) {
        when (e.type) {
            "shuffle" -> emit(Fx.Shuffle)
            "played" -> emit(Fx.Played(e.seat))
            "pass" -> emit(Fx.Pass(e.seat))
            "new_round" -> emit(Fx.NewRound(e.seat))
            "bomb" -> emit(Fx.Bomb(e.seat, e.rocket))
            "game_over" -> emit(
                Fx.GameOver(
                    landlordWon = e.landlordWon == (gdTeamOf(mySeat.value) == GdTeam.A),
                    spring = false
                )
            )
        }
    }

    /** 客户端回声监测：2.5s 无快照前进则重发一次，仍无则提示（v19 同款） */
    private fun awaitEcho(what: String, before: GdSnapshot, resend: () -> Unit) {
        if (mode.value != GameMode.CLIENT) return
        viewModelScope.launch {
            val first = withTimeoutOrNull(2500) {
                snapshot.first { s -> s != null && echoChanged(before, s) }
            }
            if (first != null) return@launch
            resend()
            val second = withTimeoutOrNull(2200) {
                snapshot.first { s -> s != null && echoChanged(before, s) }
            }
            if (second == null) opNotice.value = "网络不稳定，$what 没有送达，请稍候再试"
        }
    }

    private fun echoChanged(before: GdSnapshot, s: GdSnapshot): Boolean =
        s.phase != before.phase || s.turn != before.turn || s.handNo != before.handNo ||
                s.lastMoveSeat != before.lastMoveSeat ||
                s.seats.any { sv ->
                    before.seats.firstOrNull { it.seat == sv.seat }?.handCount != sv.handCount
                }

    fun start() {
        selected.value = emptySet()
        hintIndex = -1
        val level = when (prefs.aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }
        val aiNames = listOf("蛋炒饭", "老掼手", "小钢炮", "板凳哥")
        val aiAvatars = listOf(1, 10, 3, 11)
        val infos = (0 until 4).map { i ->
            if (i == 0) PlayerInfo(0, prefs.nickname.ifBlank { "我" }, prefs.avatar, false)
            else PlayerInfo(i, aiNames[i - 1] + level.label, aiAvatars[i - 1], true, level)
        }
        engine.newMatch(infos)
        publish(effects = true)
        sound.startBgm()
        pumpEpoch++
        pumpAi()
    }

    /** 结算后下一副 */
    fun nextHand() {
        selected.value = emptySet()
        hintIndex = -1
        when (mode.value) {
            GameMode.SINGLE -> if (engine.nextHandIfPossible()) {
                publish(effects = true)
                pumpEpoch++
                pumpAi()
            }
            GameMode.HOST -> NetLobby.hostNextHand()
            GameMode.CLIENT -> NetLobby.sendNextHand()
        }
    }

    private fun publish(effects: Boolean = false) {
        snapshot.value = engine.snapshotFor(0)
        if (effects) engine.events.mapNotNull { it.toFx() }.forEach { emit(it) }
        handleScore()
    }

    private fun GuandanEngine.GdEvent.toFx(): Fx? = when (this) {
        is GuandanEngine.GdEvent.Shuffle -> Fx.Shuffle
        is GuandanEngine.GdEvent.Played -> Fx.Played(seat)
        is GuandanEngine.GdEvent.Pass -> Fx.Pass(seat)
        is GuandanEngine.GdEvent.NewRound -> Fx.NewRound(leader)
        is GuandanEngine.GdEvent.Bomb -> Fx.Bomb(seat, rocket = rocket || straightFlush)
        is GuandanEngine.GdEvent.HandOver -> Fx.GameOver(
            landlordWon = gdTeamOf(result.headSeat) == GdTeam.A,
            spring = false
        )
    }

    private fun emit(f: Fx) {
        fx.tryEmit(f)
        when (f) {
            is Fx.Shuffle -> sound.play("shuffle")
            is Fx.Played -> {
                val voice = VoiceMap.forGuandan(snapshot.value?.lastMove, snapshot.value?.levelRank ?: 2)
                if (voice != null) sound.play(voice, 0.95f)
                sound.play("play", 0.35f)
            }
            is Fx.Pass -> sound.play("pass")
            is Fx.Bomb -> sound.play(if (f.rocket) "wangzha" else "bomb")
            is Fx.GameOver -> sound.play(if (f.landlordWon) "win" else "lose")
            else -> {}
        }
    }

    private fun pumpAi() {
        if (engine.phase != Phase.PLAYING) return
        val actor = engine.currentTurn
        if (actor <= 0) return
        val player = engine.players.getOrNull(actor) ?: return
        if (!player.info.isAi) return

        val ctx = GuandanAi.Ctx(
            hand = engine.myHand(actor),
            lastMove = engine.lastMove,
            lastMoveSeat = engine.lastMoveSeat,
            handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
            finishedSeats = engine.players.filter { it.hand.isEmpty() }.map { it.info.seat }
        )
        val ai = GuandanAi(actor, player.info.aiLevel ?: AiLevel.MEDIUM, engine.levelRank)
        val think = (when (player.info.aiLevel) {
            AiLevel.EASY -> 700; AiLevel.HARD -> 1200; else -> 950
        }) + Random.nextLong(450)

        val epoch = pumpEpoch
        viewModelScope.launch {
            delay(think)
            if (epoch != pumpEpoch) return@launch
            if (engine.phase != Phase.PLAYING || engine.currentTurn != actor) return@launch
            engine.events.clear()
            var acted = false
            val move = ai.chooseMove(ctx)
            if (move != null) acted = engine.play(actor, move)
            if (!acted && engine.lastMove != null) acted = engine.pass(actor)
            if (!acted) {
                val hand = engine.myHand(actor)
                if (hand.isNotEmpty()) acted = engine.play(actor, listOf(hand.first()))
            }
            if (acted) {
                publish(effects = true)
                pumpAi()
            }
        }
    }

    // ------------------------------------------------ 本地玩家

    fun toggleSelect(cardId: Int) {
        val cur = selected.value
        selected.value = if (cardId in cur) cur - cardId else cur + cardId
        if (selected.value.isNotEmpty()) sound.play("select", 0.6f)
        hintIndex = -1
    }

    fun selectCards(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val cur = selected.value
        val add = ids.filter { it !in cur }
        if (add.isEmpty()) return
        selected.value = cur + add
        sound.play("select", 0.45f)
        hintIndex = -1
    }

    fun clearSelection() { selected.value = emptySet(); hintIndex = -1 }

    fun playSelected(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        val me = mySeat.value
        if (snap.phase != Phase.PLAYING || snap.turn != me) return "还没轮到你"
        val ids = selected.value
        if (ids.isEmpty()) return "请先选牌"
        val meView = snap.seats.first { it.seat == me }
        val cards = meView.hand.filter { it.id in ids }
        val move = GdMove.of(cards, snap.levelRank) ?: return "不是有效牌型"
        if (!move.beats(snap.lastMove, snap.levelRank)) return "压不过上家"
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                if (!engine.play(0, cards)) return "出牌无效"
                clearSelection()
                publish(effects = true)
                pumpAi()
            }
            GameMode.HOST -> {
                NetLobby.hostPlay(ids.toList())
                clearSelection()
            }
            GameMode.CLIENT -> {
                val idList = ids.toList()
                NetLobby.sendPlay(idList)
                awaitEcho("出牌", snap) { NetLobby.sendPlay(idList) }
                clearSelection()
            }
        }
        return null
    }

    fun passTurn(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        if (snap.lastMove == null) return "轮到你先出"
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                if (!engine.pass(0)) return "不能过牌"
                clearSelection()
                publish(effects = true)
                pumpAi()
            }
            GameMode.HOST -> NetLobby.hostPass()
            GameMode.CLIENT -> {
                NetLobby.sendPass()
                awaitEcho("不出", snap) { NetLobby.sendPass() }
            }
        }
        clearSelection()
        return null
    }

    fun hint(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        val hand = snap.seats.first { it.seat == mySeat.value }.hand
        val last = snap.lastMove
        val all: List<List<Card>> = if (last == null) {
            listOf(listOf(hand.first()))
        } else {
            GuandanAi.genGdBeats(hand, last, snap.levelRank, withBomb = false)
                .ifEmpty { GuandanAi.genGdBeats(hand, last, snap.levelRank, withBomb = true) }
        }
        if (all.isEmpty()) return "没有能出的牌"
        val ordered = all.sortedBy { it.sumOf { c -> c.rank } }
        hintIndex = (hintIndex + 1) % ordered.size
        selected.value = ordered[hintIndex].map { it.id }.toSet()
        return null
    }

    private var scoredHand = -1
    private fun handleScore() {
        val snap = snapshot.value ?: return
        val r = snap.result ?: return
        if (scoredHand == snap.handNo) return
        scoredHand = snap.handNo
        val iWon = gdTeamOf(mySeat.value) == r.winnerTeam
        lastScore.value = if (iWon) r.upgrade * 10 else -(r.upgrade * 10)
        // 战绩只在单机/房主端记录（与斗地主一致）
        if (mode.value == GameMode.SINGLE || mode.value == GameMode.HOST) {
            prefs.addResult(iWon, lastScore.value)
        }
    }

    fun exitGame() {
        if (mode.value != GameMode.SINGLE) {
            netJobs.forEach { it.cancel() }
            netJobs.clear()
            netBound = false
            NetLobby.leave()
        }
        mode.value = GameMode.SINGLE
        mySeat.value = 0
        scoredHand = -1
        sound.stopBgm()
        snapshot.value = null
        selected.value = emptySet()
    }

    /** 快捷喊话：单机本地播语音；联机走 NetLobby（气泡由界面层显示） */
    fun localChat(phrase: String) {
        val code = CHAT_PHRASES.indexOf(phrase) + 1
        when (mode.value) {
            GameMode.SINGLE -> VoiceMap.forChat(code)?.let { sound.play(it, 1f) }
            else -> NetLobby.chat(phrase, code)
        }
    }

    /** 智能理牌列整组选中/取消 */
    fun toggleGroup(ids: List<Int>) {
        val cur = selected.value
        selected.value = if (ids.all { it in cur }) cur - ids.toSet() else cur + ids
        sound.play("select", 0.6f)
        hintIndex = -1
    }

    fun sfx(key: String, vol: Float = 1f) = sound.play(key, vol)
}
