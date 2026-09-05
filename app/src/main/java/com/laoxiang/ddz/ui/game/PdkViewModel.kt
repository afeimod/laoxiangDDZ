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
 * 跑得快视图模型：单机 vs AI（三人/四人），与斗地主 GameViewModel 平行、互不干扰。
 * v20：支持本地联机（HOST=房主权威 / CLIENT=回显快照）。
 */
class PdkViewModel(app: Application) : AndroidViewModel(app) {

    private val sound = (app as LaoXiangApp).sound
    val prefs = Prefs(app)

    val snapshot = MutableStateFlow<PdkSnapshot?>(null)
    val selected = MutableStateFlow<Set<Int>>(emptySet())
    val lastScore = MutableStateFlow(0)

    /** 一次性特效/音效事件（复用 Fx 中与跑得快相关的类型） */
    val fx = MutableSharedFlow<Fx>(extraBufferCapacity = 64)

    /** 游戏模式（v20） */
    val mode = MutableStateFlow(GameMode.SINGLE)

    /** 我的座位（联机由房主分配；单机恒 0） */
    val mySeat = MutableStateFlow(0)

    /** 联机操作未送达提示 */
    val opNotice = MutableStateFlow<String?>(null)

    fun clearOpNotice() { opNotice.value = null }

    private val engine = PdkEngine()
    private var hintIndex = -1

    // ------------------------------------------------ 联机绑定（v20）

    private val netJobs = ArrayList<kotlinx.coroutines.Job>()
    private var netBound = false

    /** 进入联机对局页前调用（幂等） */
    fun bindNet() {
        if (netBound) return
        netBound = true
        mode.value = if (NetLobby.isHost && NetLobby.activeGame == "pdk") GameMode.HOST else GameMode.CLIENT
        mySeat.value = NetLobby.mySeat.value
        netJobs += NetLobby.mySeat.onEach { mySeat.value = it }.launchIn(viewModelScope)
        netJobs += NetLobby.gSnapshot.onEach { msg ->
            if (msg?.game != "pdk") return@onEach
            val snap = runCatching {
                netJson.decodeFromJsonElement(PdkSnapshot.serializer(), msg.payload)
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
            "bomb" -> emit(Fx.Bomb(e.seat, rocket = false))
            "game_over" -> emit(Fx.GameOver(landlordWon = e.seat == mySeat.value, spring = false))
        }
    }

    private fun awaitEcho(what: String, before: PdkSnapshot, resend: () -> Unit) {
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

    private fun echoChanged(before: PdkSnapshot, s: PdkSnapshot): Boolean =
        s.phase != before.phase || s.turn != before.turn || s.round != before.round ||
                s.lastMoveSeat != before.lastMoveSeat ||
                s.seats.any { sv ->
                    before.seats.firstOrNull { it.seat == sv.seat }?.handCount != sv.handCount
                }

    // ------------------------------------------------ 开局

    fun start(mode: PdkMode) {
        this.mode.value = GameMode.SINGLE
        this.mySeat.value = 0
        selected.value = emptySet()
        hintIndex = -1
        val level = when (prefs.aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }
        val aiNames = listOf("急脚鬼", "飞毛腿", "一阵风", "小旋风")
        val aiAvatars = listOf(2, 5, 8, 3)
        val infos = (0 until mode.players).map { i ->
            if (i == 0) {
                PlayerInfo(0, prefs.nickname.ifBlank { "我" }, prefs.avatar, false)
            } else {
                PlayerInfo(i, aiNames[i - 1] + level.label, aiAvatars[i - 1], true, level)
            }
        }
        engine.newGame(infos, mode)
        publish(effects = true)
        sound.startBgm()
        pumpAi()
    }

    private fun publish(effects: Boolean = false) {
        snapshot.value = engine.snapshotFor(0)
        if (effects) engine.events.mapNotNull { it.toFx() }.forEach { emit(it) }
        handleScore()
    }

    private fun PdkEngine.PdkEvent.toFx(): Fx? = when (this) {
        is PdkEngine.PdkEvent.Shuffle -> Fx.Shuffle
        is PdkEngine.PdkEvent.TurnTo -> null
        is PdkEngine.PdkEvent.Played -> Fx.Played(seat)
        is PdkEngine.PdkEvent.Pass -> Fx.Pass(seat)
        is PdkEngine.PdkEvent.NewRound -> Fx.NewRound(leader)
        is PdkEngine.PdkEvent.Bomb -> Fx.Bomb(seat, rocket = false)
        is PdkEngine.PdkEvent.GameOver -> Fx.GameOver(winner == 0, spring = false)
    }

    private fun emit(f: Fx) {
        fx.tryEmit(f)
        when (f) {
            is Fx.Shuffle -> sound.play("shuffle")
            is Fx.Played -> {
                val voice = VoiceMap.forMove(snapshot.value?.lastMove)
                if (voice != null) sound.play(voice, 0.95f)
                sound.play("play", 0.35f)
            }
            is Fx.Pass -> sound.play("pass")
            is Fx.Bomb -> sound.play("bomb")
            is Fx.GameOver -> sound.play(if (f.landlordWon) "win" else "lose")
            else -> {}
        }
    }

    // ------------------------------------------------ AI 驱动

    private fun pumpAi() {
        if (engine.phase != Phase.PLAYING) return
        val actor = engine.currentTurn
        if (actor <= 0) return
        val player = engine.players.getOrNull(actor) ?: return
        if (!player.info.isAi) return

        val ctx = PdkAi.Ctx(
            seat = actor,
            hand = engine.myHand(actor),
            lastMove = engine.lastMove,
            lastMoveSeat = engine.lastMoveSeat,
            handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
            playedCards = engine.players.flatMap { it.played }
        )
        val ai = PdkAi(actor, player.info.aiLevel ?: AiLevel.MEDIUM)
        val think = (when (player.info.aiLevel) {
            AiLevel.EASY -> 650; AiLevel.HARD -> 1150; else -> 900
        }) + Random.nextLong(450)

        viewModelScope.launch {
            delay(think)
            if (engine.phase != Phase.PLAYING || engine.currentTurn != actor) return@launch
            engine.events.clear()
            var acted = false
            val move = ai.chooseMove(ctx)
            if (move != null && engine.play(actor, move)) {
                acted = true
            } else if (engine.lastMove != null) {
                acted = engine.pass(actor)
                if (!acted) {
                    // 兜底：不能过 → 随便出最小能压的（规则保证有牌必压）
                    val beats = MoveGen.genBeats(engine.myHand(actor), engine.lastMove)
                    if (beats.isNotEmpty()) acted = engine.play(actor, beats.first().cards)
                }
            }
            if (acted) {
                publish(effects = true)
                pumpAi()
            }
        }
    }

    // ------------------------------------------------ 本地玩家操作

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

    fun clearSelection() {
        selected.value = emptySet()
        hintIndex = -1
    }

    /** 当前是否允许"不出"：桌上有牌且自己压不过 */
    fun canPass(): Boolean {
        val snap = snapshot.value ?: return false
        val me = mySeat.value
        if (snap.phase != Phase.PLAYING || snap.turn != me) return false
        return when (mode.value) {
            GameMode.SINGLE -> snap.lastMove != null && !engine.hasBeat(0)
            else -> {
                if (snap.lastMove == null) return false
                val hand = snap.seats.first { it.seat == me }.hand
                MoveGen.genBeats(hand, snap.lastMove).isEmpty()
            }
        }
    }

    fun playSelected(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        val me = mySeat.value
        if (snap.phase != Phase.PLAYING || snap.turn != me) return "还没轮到你"
        val ids = selected.value
        if (ids.isEmpty()) return "请先选牌"
        val meView = snap.seats.first { it.seat == me }
        val cards = meView.hand.filter { it.id in ids }
        val move = Move.of(cards) ?: return "不是有效牌型"
        if (snap.lastMove != null && !move.beats(snap.lastMove)) return "压不过上家"
        if (snap.playedRanks.values.all { it == 0 } &&
            cards.none { it.rank == 3 && it.suit == CardSuit.SPADE }) {
            return "第一手必须带黑桃3"
        }

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
        if (snap.lastMove == null) return "轮到你先出，不能不要"
        if (mode.value == GameMode.SINGLE && engine.hasBeat(0)) return "有牌必压，压得过必须出"
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

    /** 提示：循环给出合法招（能压的最小 / 领出的最小） */
    fun hint(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        val hand = snap.seats.first { it.seat == mySeat.value }.hand
        val all = if (snap.lastMove == null) {
            MoveGen.genLeads(hand)
        } else {
            MoveGen.genBeats(hand, snap.lastMove)
        }
        if (all.isEmpty()) return "没有能出的牌"
        val ordered = all.sortedBy { it.mainRank }
        hintIndex = (hintIndex + 1) % ordered.size
        val pick = ordered[hintIndex]
        selected.value = pick.cards.map { it.id }.toSet()
        return null
    }

    /** 结算后再来一局（联机走网络重开；单机原地重开） */
    fun again(mode: PdkMode) {
        when (this.mode.value) {
            GameMode.SINGLE -> start(mode)
            GameMode.HOST -> NetLobby.hostRestart()
            GameMode.CLIENT -> NetLobby.sendRestart()
        }
    }

    // ------------------------------------------------ 结算

    private var scoredRound = -1

    private fun handleScore() {
        val snap = snapshot.value ?: return
        val r = snap.result ?: return
        if (scoredRound == snap.round) return
        scoredRound = snap.round
        val myDelta = r.scoreDelta[mySeat.value] ?: 0
        lastScore.value = myDelta
        if (mode.value == GameMode.SINGLE || mode.value == GameMode.HOST) {
            prefs.addResult(myDelta > 0, myDelta)
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
        scoredRound = -1
        sound.stopBgm()
        snapshot.value = null
        selected.value = emptySet()
    }

    /** UI 层直接播放短音效（发牌逐张等） */

    /** 快捷喊话：单机本地播语音；联机走 NetLobby（气泡由界面层显示） */
    fun localChat(phrase: String) {
        val code = CHAT_PHRASES.indexOf(phrase) + 1
        when (mode.value) {
            GameMode.SINGLE -> VoiceMap.forChat(code)?.let { sound.play(it, 1f) }
            else -> NetLobby.chat(phrase, code)
        }
    }

    fun sfx(key: String, vol: Float = 1f) = sound.play(key, vol)
}
