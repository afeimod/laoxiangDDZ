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
 * 升级（拖拉机）视图模型：单机 vs AI（4人 2v2），多副牌连续对局。
 * V2：慢速发牌 + 定主（亮主/反主）竞标，庄家自动扣底。
 * v20：支持本地联机（HOST=房主权威 / CLIENT=回显快照）。
 */
class ShengjiViewModel(app: Application) : AndroidViewModel(app) {

    private val sound = (app as LaoXiangApp).sound
    val prefs = Prefs(app)

    val snapshot = MutableStateFlow<SjSnapshot?>(null)
    val selected = MutableStateFlow<Set<Int>>(emptySet())
    val lastScore = MutableStateFlow(0)
    val fx = MutableSharedFlow<Fx>(extraBufferCapacity = 64)

    /** 提示信息（扣底等） */
    val notice = MutableStateFlow<String?>(null)

    /** 游戏模式（v20） */
    val mode = MutableStateFlow(GameMode.SINGLE)

    /** 我的座位（联机由房主分配；单机恒 0） */
    val mySeat = MutableStateFlow(0)

    /** 联机操作未送达提示 */
    val opNotice = MutableStateFlow<String?>(null)

    fun clearOpNotice() { opNotice.value = null }

    private val engine = ShengjiEngine()
    private var hintIndex = -1
    private val bidJobs = ArrayList<kotlinx.coroutines.Job>()
    private var settledHand = -1
    private var buryJob: kotlinx.coroutines.Job? = null

    // ------------------------------------------------ 联机绑定（v20）

    private val netJobs = ArrayList<kotlinx.coroutines.Job>()
    private var netBound = false

    /** 进入联机对局页前调用（幂等） */
    fun bindNet() {
        if (netBound) return
        netBound = true
        mode.value = if (NetLobby.isHost && NetLobby.activeGame == "shengji") GameMode.HOST else GameMode.CLIENT
        mySeat.value = NetLobby.mySeat.value
        netJobs += NetLobby.mySeat.onEach { mySeat.value = it }.launchIn(viewModelScope)
        netJobs += NetLobby.gSnapshot.onEach { msg ->
            if (msg?.game != "shengji") return@onEach
            val snap = runCatching {
                netJson.decodeFromJsonElement(SjSnapshot.serializer(), msg.payload)
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
            "game_over" -> emit(
                Fx.GameOver(
                    landlordWon = e.landlordWon == (gdTeamOf(mySeat.value) == GdTeam.A),
                    spring = false
                )
            )
        }
    }

    private fun awaitEcho(what: String, before: SjSnapshot, resend: () -> Unit) {
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

    private fun echoChanged(before: SjSnapshot, s: SjSnapshot): Boolean =
        s.phase != before.phase || s.turn != before.turn || s.handNo != before.handNo ||
                s.claimSeat != before.claimSeat || s.claimTier != before.claimTier ||
                s.claimNT != before.claimNT || s.dealer != before.dealer ||
                s.trickPlays.size != before.trickPlays.size ||
                s.seats.any { sv ->
                    before.seats.firstOrNull { it.seat == sv.seat }?.handCount != sv.handCount
                }

    fun start() {
        mode.value = GameMode.SINGLE
        mySeat.value = 0
        selected.value = emptySet()
        hintIndex = -1
        val level = when (prefs.aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }
        val aiNames = listOf("二舅", "三姨", "老支书", "翠花婶")
        val aiAvatars = listOf(2, 6, 12, 8)
        val infos = (0 until 4).map { i ->
            if (i == 0) PlayerInfo(0, prefs.nickname.ifBlank { "我" }, prefs.avatar, false)
            else PlayerInfo(i, aiNames[i - 1] + level.label, aiAvatars[i - 1], true, level)
        }
        engine.newMatch(infos)
        settledHand = -1
        publish(effects = true)
        sound.startBgm()
        scheduleBidding()
    }

    fun nextHand() {
        selected.value = emptySet()
        hintIndex = -1
        when (mode.value) {
            GameMode.SINGLE -> if (engine.nextHandIfPossible()) {
                settledHand = -1
                publish(effects = true)
                scheduleBidding()
            }
            GameMode.HOST -> NetLobby.hostNextHand()
            GameMode.CLIENT -> NetLobby.sendNextHand()
        }
    }

    // ------------------------------------------------ 定主（亮主）

    /** AI 亮主调度：各自在发牌窗口内随机时机亮主（有资格才亮） */
    private fun scheduleBidding() {
        bidJobs.forEach { it.cancel() }
        bidJobs.clear()
        if (engine.phase != Phase.BIDDING) return
        val handNo = engine.handNo
        for (seat in 1..3) {
            val options = engine.claimOptions(seat)
            if (options.isEmpty()) continue
            val best = options.maxByOrNull { it.value }!!
            val aiLevel = engine.players[seat].info.aiLevel ?: AiLevel.MEDIUM
            val p = when (aiLevel) { AiLevel.EASY -> 0.45; AiLevel.HARD -> 0.85; else -> 0.7 }
            if (Random.nextDouble() > p) continue
            val delayMs = Random.nextLong(2600, 8600)
            bidJobs += viewModelScope.launch {
                delay(delayMs)
                if (engine.phase != Phase.BIDDING || engine.handNo != handNo) return@launch
                if (engine.claimTrump(seat, best.key)) publish()
            }
        }
    }

    /** 玩家亮主：suit=null 亮无主（需对王）；联机时路由到房主 */
    fun claimTrump(suit: CardSuit?): String? {
        when (mode.value) {
            GameMode.HOST -> {
                NetLobby.hostClaim(suit)
                return null
            }
            GameMode.CLIENT -> {
                val snap = snapshot.value ?: return "尚未开局"
                NetLobby.sendClaim(suit)
                awaitEcho(if (suit == null) "亮无主" else "亮主", snap) { NetLobby.sendClaim(suit) }
                return null
            }
            GameMode.SINGLE -> {}
        }
        if (engine.phase != Phase.BIDDING) return "现在不能亮主"
        if (engine.claimSeat == 0) return "你已亮主，等待更高反主"
        engine.events.clear()
        if (!engine.claimTrump(0, suit)) {
            return if (suit == null) "需一对大王/小王才能亮无主" else "需持有该花色级牌（已被更高反主则需更大）"
        }
        publish()
        return null
    }

    /** 发牌揭示完毕：留出末段亮主窗口后定主，庄家捡底 → 进入扣底阶段
     *  （联机：房主端驱动定主；客户端无需动作，房主侧有兑底定时器） */
    fun settleBidding() {
        when (mode.value) {
            GameMode.HOST -> {
                NetLobby.hostSettle()
                return
            }
            GameMode.CLIENT -> return
            GameMode.SINGLE -> {}
        }
        if (engine.phase != Phase.BIDDING || settledHand == engine.handNo) return
        settledHand = engine.handNo
        viewModelScope.launch {
            delay(1500)
            bidJobs.forEach { it.cancel() }
            bidJobs.clear()
            if (engine.phase == Phase.BIDDING) {
                engine.events.clear()
                engine.finishBidding()
                publish(effects = false)
                scheduleBury()
            }
        }
    }

    // ------------------------------------------------ 扣底阶段

    /** 庄家扣底调度：AI 庄延时自动扣；玩家庄等待手动扣（界面提供“自动扣”） */
    private fun scheduleBury() {
        buryJob?.cancel()
        if (engine.phase != Phase.BURYING) return
        val d = engine.dealer
        val p = engine.players.getOrNull(d) ?: return
        if (!p.info.isAi) return          // 玩家庄：等 UI 手动扣底
        val handNo = engine.handNo
        buryJob = viewModelScope.launch {
            delay(1400 + Random.nextLong(900))
            if (engine.phase != Phase.BURYING || engine.handNo != handNo) return@launch
            engine.events.clear()
            if (engine.buryCards(d, engine.autoBuryChoice())) {
                publish(effects = false)
                pumpAi()
            }
        }
    }

    /** 玩家扣底：须恰选 8 张（联机：庄是我 → 发牌 id 给房主） */
    fun burySelected(): String? {
        val ids = selected.value
        if (ids.size != 8) return "请选 8 张扣为底牌（已选 ${ids.size}/8）"
        val snap = snapshot.value ?: return "尚未开局"
        val me = mySeat.value
        when (mode.value) {
            GameMode.HOST -> {
                NetLobby.hostPlay(ids.toList())
                clearSelection()
                return null
            }
            GameMode.CLIENT -> {
                if (snap.phase != Phase.BURYING || snap.dealer != me) return "还没轮到你扣底"
                val idList = ids.toList()
                NetLobby.sendPlay(idList)
                awaitEcho("扣底", snap) { NetLobby.sendPlay(idList) }
                clearSelection()
                return null
            }
            GameMode.SINGLE -> {}
        }
        if (engine.phase != Phase.BURYING) return "现在不能扣底"
        if (engine.dealer != 0) return "还没轮到你扣底"
        val meView = snap.seats.first { it.seat == 0 }
        val cards = meView.hand.filter { it.id in ids }
        engine.events.clear()
        if (!engine.buryCards(0, cards)) return "扣底失败，请重试"
        clearSelection()
        publish(effects = false)
        pumpAi()
        return null
    }

    /** 玩家一键自动扣底（联机：房主端引擎计算后广播） */
    fun autoBury(): String? {
        when (mode.value) {
            GameMode.HOST -> {
                if (snapshot.value?.phase != Phase.BURYING) return "现在不能扣底"
                NetLobby.hostAutoBury()
                clearSelection()
                return null
            }
            GameMode.CLIENT -> {
                val snap = snapshot.value ?: return "尚未开局"
                if (snap.phase != Phase.BURYING || snap.dealer != mySeat.value) return "现在不能扣底"
                // 客户端庄：本地用同等策略选 8 张（垫最小非主牌），房主引擎校验
                val hand = snap.seats.first { it.seat == mySeat.value }.hand
                val trump = snap.trumpSuit
                val lr = snap.levelRank
                val nonTrump = hand.filter { c ->
                    trump == null || (c.suit != trump && c.suit != CardSuit.JOKER && c.rank != lr)
                }
                val pick = (nonTrump.ifEmpty { hand }).sortedBy { it.rank }.take(8)
                if (pick.size < 8) return "手牌不足 8 张"
                val idList = pick.map { it.id }
                NetLobby.sendPlay(idList)
                awaitEcho("扣底", snap) { NetLobby.sendPlay(idList) }
                clearSelection()
                return null
            }
            GameMode.SINGLE -> {}
        }
        if (engine.phase != Phase.BURYING || engine.dealer != 0) return "现在不能扣底"
        engine.events.clear()
        if (!engine.buryCards(0, engine.autoBuryChoice())) return "扣底失败，请重试"
        clearSelection()
        publish(effects = false)
        pumpAi()
        return null
    }

    private fun publish(effects: Boolean = false) {
        snapshot.value = engine.snapshotFor(0)
        if (effects) engine.events.mapNotNull { it.toFx() }.forEach { emit(it) }
        handleScore()
    }

    private fun ShengjiEngine.SjEvent.toFx(): Fx? = when (this) {
        is ShengjiEngine.SjEvent.Shuffle -> Fx.Shuffle
        is ShengjiEngine.SjEvent.Played -> Fx.Played(seat)
        is ShengjiEngine.SjEvent.NewRoundSJ -> null
        is ShengjiEngine.SjEvent.TrickWon -> null
        is ShengjiEngine.SjEvent.HandOver -> Fx.GameOver(
            landlordWon = gdTeamOf(0) == result.winnerTeam,
            spring = false
        )
    }

    private fun emit(f: Fx) {
        fx.tryEmit(f)
        when (f) {
            is Fx.Shuffle -> sound.play("shuffle")
            is Fx.Played -> {
                val voice = VoiceMap.forShengji(snapshot.value?.lastPlay, snapshot.value?.levelRank ?: 2)
                if (voice != null) sound.play(voice, 0.9f)
                sound.play("play", 0.3f)
            }
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

        val snap = engine.snapshotFor(actor)
        val ctx = ShengjiAi.Ctx(
            hand = engine.myHand(actor),
            trumpSuit = engine.trumpSuit,
            levelRank = engine.levelRank,
            dealer = engine.dealer,
            lastLed = engine.ledPlay(),
            trickPlays = engine.trickPlays.toList(),
            oppPoints = engine.oppPoints
        )
        val ai = ShengjiAi(actor, player.info.aiLevel ?: AiLevel.MEDIUM)
        val think = (when (player.info.aiLevel) {
            AiLevel.EASY -> 700; AiLevel.HARD -> 1200; else -> 950
        }) + Random.nextLong(450)

        viewModelScope.launch {
            delay(think)
            if (engine.phase != Phase.PLAYING || engine.currentTurn != actor) return@launch
            engine.events.clear()
            var acted = false
            val move = ai.chooseMove(ctx)
            if (move != null) acted = engine.play(actor, move)
            if (!acted) {
                // 兜底：跟足领出（级牌属主不算该花色），不足则全带 + 任意凑数
                val hand = engine.myHand(actor)
                val led = engine.ledPlay()
                val fallback: List<Card>? = if (led == null) {
                    listOf(hand.lastOrNull() ?: return@launch)
                } else {
                    val n = led.count
                    val inSuit = if (led.suit == CardSuit.JOKER)
                        hand.filter { SjRules.isTrump(it, engine.trumpSuit, engine.levelRank) }
                    else hand.filter {
                        it.suit == led.suit && !SjRules.isTrump(it, engine.trumpSuit, engine.levelRank)
                    }
                    if (inSuit.size >= n) {
                        inSuit.sortedBy { it.rank }.take(n)
                    } else {
                        val rest = hand.filter { c -> inSuit.none { it.id == c.id } }
                            .sortedBy { it.rank }
                        (inSuit + rest).take(n)
                    }
                }
                if (fallback != null) acted = engine.play(actor, fallback)
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
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        val ids = selected.value
        if (ids.isEmpty()) return "请先选牌"
        val meView = snap.seats.first { it.seat == mySeat.value }
        val cards = meView.hand.filter { it.id in ids }
        if (!snap.trickPlays.any { it.first == mySeat.value } && snap.lastPlay != null &&
            cards.size != snap.lastPlay!!.count) {
            return "必须出 ${snap.lastPlay!!.count} 张"
        }
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                val ok = engine.play(0, cards)
                if (!ok) {
                    val trump = snap.trumpSuit
                    return if (trump != null && snap.lastPlay != null && snap.lastPlay!!.suit == CardSuit.JOKER &&
                        cards.none { SjRules.isTrump(it, trump, snap.levelRank) })
                        "领出的是主牌，有主须跟主"
                    else
                        "跟牌不合法：有该花色（非主牌）须跟足 ${snap.lastPlay?.count ?: cards.size} 张；无该花色可主杀/垫牌"
                }
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

    fun hint(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        val hand = snap.seats.first { it.seat == mySeat.value }.hand
        val t = snap.trumpSuit ?: CardSuit.SPADE
        val lr = snap.levelRank
        val led = when (mode.value) {
            GameMode.SINGLE -> engine.ledPlay()
            else -> snap.lastPlay?.takeIf { snap.trickPlays.none { tp -> tp.first == mySeat.value } }
        }
        val pick: List<Card>? = if (led == null) {
            ShengjiAi.findTractor(hand, CtxLike(t, lr), trump = false)
                ?: listOf(hand.first())
        } else {
            // 跟牌池：领出副牌=该花色非主牌；领出主牌=主牌（级牌属主不算该花色）
            val pool = if (led.suit == CardSuit.JOKER) hand.filter { SjRules.isTrump(it, t, lr) }
            else hand.filter { it.suit == led.suit && !SjRules.isTrump(it, t, lr) }
            when {
                pool.size >= led.count -> pool.sortedBy { it.rank }.take(led.count)
                pool.isEmpty() && led.count == 1 -> {
                    // 无该花色：优先建议最大主杀，否则垫最小牌
                    val trumps = hand.filter { SjRules.isTrump(it, t, lr) }
                    if (trumps.isNotEmpty()) listOf(trumps.maxBy { SjRules.trumpIndex(it, t, lr) })
                    else listOf(hand.minBy { it.rank })
                }
                else -> {
                    val rest = hand.filter { c -> pool.none { it.id == c.id } }.sortedBy { it.rank }
                    (pool + rest).take(led.count)
                }
            }
        }
        pick ?: return "没有能出的牌"
        selected.value = pick.map { it.id }.toSet()
        return null
    }

    private var scoredHand = -1
    private fun handleScore() {
        val snap = snapshot.value ?: return
        val r = snap.result ?: return
        if (scoredHand == snap.handNo) return
        scoredHand = snap.handNo
        val iWon = gdTeamOf(mySeat.value) == r.winnerTeam
        lastScore.value = if (iWon) (r.upgrade + 1) * 10 else -(r.upgrade * 10 + 10)
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
        bidJobs.forEach { it.cancel() }
        bidJobs.clear()
        buryJob?.cancel()
        buryJob = null
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

    fun sfx(key: String, vol: Float = 1f) = sound.play(key, vol)
}
