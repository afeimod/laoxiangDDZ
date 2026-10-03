package com.laoxiang.ddz.net

import com.laoxiang.ddz.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.random.Random

/**
 * 四个游戏的房主侧驱动（v20 全系列联机）
 *
 * 每个驱动 = 该游戏单机 ViewModel 的「权威版」：
 * 引擎 + AI 泵（延时思考后行动再 trigger 广播）+ 事件→网络特效映射。
 * 全部方法只在房主的游戏单线程上调用（GameHost 已保证），与单机逻辑一一对应。
 */
object GameDrivers {

    /** 按游戏 id 创建驱动；seatCount 用于跑得快三人/四人（其余游戏校验用） */
    fun create(
        gameId: String,
        gameScope: CoroutineScope,
        trigger: () -> Unit,
        seatCount: Int = 4
    ): HostDriver = when (gameId) {
        "guandan" -> {
            check(seatCount == 4) { "掼蛋需要4名玩家" }
            GuandanDriver(gameScope, trigger)
        }
        "shengji" -> {
            check(seatCount == 4) { "升级需要4名玩家" }
            ShengjiDriver(gameScope, trigger)
        }
        "pdk" -> PdkDriver(gameScope, trigger, seatCount)
        "bigtwo" -> {
            check(seatCount == 4) { "锄大地需要4名玩家" }
            BigTwoDriver(gameScope, trigger)
        }
        "mjdazhong" -> {
            check(seatCount == 4) { "麻将需要4名玩家" }
            MjDriver(MjMode.DAZHONG, gameScope, trigger)
        }
        "mjlaizi" -> {
            check(seatCount == 4) { "麻将需要4名玩家" }
            MjDriver(MjMode.LAIZI, gameScope, trigger)
        }
        "mjsichuan" -> {
            check(seatCount == 4) { "麻将需要4名玩家" }
            MjDriver(MjMode.SICHUAN, gameScope, trigger)
        }
        else -> throw IllegalArgumentException("未知游戏：$gameId")
    }
}

// ============================================================
// 公共小工具
// ============================================================

/** AI 思考时长（与各单机 ViewModel 一致） */
private fun thinkMs(level: AiLevel?): Long =
    (when (level) { AiLevel.EASY -> 700L; AiLevel.HARD -> 1200L; else -> 950L }) + Random.nextLong(450)

private val handCountsOf: (List<PlayerState>) -> Map<Int, Int> =
    { ps -> ps.map { it.info.seat to it.hand.size }.toMap() }

// ============================================================
// 掼蛋
// ============================================================

private class GuandanDriver(
    private val gameScope: CoroutineScope,
    private val trigger: () -> Unit
) : HostDriver {

    override val gameId = "guandan"
    private val engine = GuandanEngine()
    private var pumpJob: Job? = null

    override fun newGame(infos: List<PlayerInfo>) {
        engine.newMatch(infos)
    }

    override fun aiNameFor(seat: Int): String =
        listOf("蛋炒饭", "老掼手", "小钢炮", "板凳哥")[((seat - 1).coerceAtLeast(0)) % 4]

    override fun aiAvatarFor(seat: Int): Int = listOf(1, 10, 3, 11)[((seat - 1).coerceAtLeast(0)) % 4]

    override fun detachToAi(seat: Int, level: AiLevel) {
        engine.players.getOrNull(seat)?.let { p ->
            if (!p.info.isAi) p.info = p.info.copy(isAi = true, aiLevel = level)
        }
    }

    override fun play(seat: Int, ids: List<Int>): Boolean {
        if (engine.phase != Phase.PLAYING || engine.currentTurn != seat) return false
        return engine.play(seat, Deck.doubleByIds(ids))
    }

    override fun pass(seat: Int): Boolean = engine.pass(seat)

    override fun claim(seat: Int, suit: CardSuit?): Boolean = false

    override fun nextHand(): Boolean {
        if (engine.phase != Phase.GAME_OVER) return false
        return engine.nextHandIfPossible()
    }

    override fun restart(): Boolean = false

    override fun snapshotFor(seat: Int): JsonElement =
        netJson.encodeToJsonElement(GdSnapshot.serializer(), engine.snapshotFor(seat))

    override fun drainEffects(): List<NetMsg.Effect> = engine.events.mapNotNull { ev ->
        when (ev) {
            is GuandanEngine.GdEvent.Shuffle -> NetMsg.Effect("shuffle")
            is GuandanEngine.GdEvent.Played -> NetMsg.Effect("played", seat = ev.seat)
            is GuandanEngine.GdEvent.Pass -> NetMsg.Effect("pass", seat = ev.seat)
            is GuandanEngine.GdEvent.NewRound -> NetMsg.Effect("new_round", seat = ev.leader)
            is GuandanEngine.GdEvent.Bomb -> NetMsg.Effect(
                "bomb", seat = ev.seat, rocket = ev.rocket
            )
            is GuandanEngine.GdEvent.HandOver -> NetMsg.Effect(
                // landlordWon 中性语义 = A 队获胜；客户端按自己队伍换算
                "game_over", landlordWon = gdTeamOf(ev.result.headSeat) == GdTeam.A
            )
        }
    }

    override fun pumpAi() {
        pumpJob?.cancel()
        if (engine.phase != Phase.PLAYING) return
        val actor = engine.currentTurn
        if (actor <= 0) return
        val p = engine.players.getOrNull(actor) ?: return
        if (!p.info.isAi) return

        val ctx = GuandanAi.Ctx(
            hand = engine.myHand(actor),
            lastMove = engine.lastMove,
            lastMoveSeat = engine.lastMoveSeat,
            handCounts = handCountsOf(engine.players),
            finishedSeats = engine.players.filter { it.hand.isEmpty() }.map { it.info.seat }
        )
        val ai = GuandanAi(actor, p.info.aiLevel ?: AiLevel.MEDIUM, engine.levelRank)
        val wait = thinkMs(p.info.aiLevel)
        pumpJob = gameScope.launch {
            delay(wait)
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
            if (acted) trigger()
        }
    }

    override fun cancelJobs() { pumpJob?.cancel(); pumpJob = null }
}

// ============================================================
// 升级
// ============================================================

private class ShengjiDriver(
    private val gameScope: CoroutineScope,
    private val trigger: () -> Unit
) : HostDriver {

    override val gameId = "shengji"
    private val engine = ShengjiEngine()
    private var bidJobs = ArrayList<Job>()
    private var buryJob: Job? = null
    private var pumpJob: Job? = null
    private var settleJob: Job? = null
    private var dealtAt = 0L

    override fun newGame(infos: List<PlayerInfo>) {
        engine.newMatch(infos)
        scheduleBidding()
        scheduleSettleFallback()
    }

    override fun aiNameFor(seat: Int): String =
        listOf("二舅", "三姨", "老支书", "翠花婶")[((seat - 1).coerceAtLeast(0)) % 4]

    override fun aiAvatarFor(seat: Int): Int = listOf(2, 6, 12, 8)[((seat - 1).coerceAtLeast(0)) % 4]

    override fun detachToAi(seat: Int, level: AiLevel) {
        engine.players.getOrNull(seat)?.let { p ->
            if (!p.info.isAi) p.info = p.info.copy(isAi = true, aiLevel = level)
        }
    }

    override fun play(seat: Int, ids: List<Int>): Boolean {
        val cards = Deck.doubleByIds(ids)
        if (cards.isEmpty()) return false
        if (engine.phase == Phase.BURYING) return engine.buryCards(seat, cards)
        if (engine.phase != Phase.PLAYING || engine.currentTurn != seat) return false
        return engine.play(seat, cards)
    }

    override fun pass(seat: Int): Boolean = false   // 升级无过牌

    override fun claim(seat: Int, suit: CardSuit?): Boolean {
        val ok = engine.claimTrump(seat, suit)
        return ok
    }

    /** 定主收口（幂等）：房主 UI 与兜底定时器都会调 */
    override fun settle(): Boolean {
        if (engine.phase != Phase.BIDDING) return false
        finishBiddingNow()
        return true
    }

    private fun finishBiddingNow() {
        bidJobs.forEach { it.cancel() }; bidJobs.clear()
        settleJob?.cancel(); settleJob = null
        engine.finishBidding()
        scheduleBury()
    }

    override fun nextHand(): Boolean {
        if (engine.phase != Phase.GAME_OVER) return false
        return engine.nextHandIfPossible().also { if (it) {
            scheduleBidding()
            scheduleSettleFallback()
        } }
    }

    override fun autoBury(): Boolean {
        if (engine.phase != Phase.BURYING) return false
        return engine.buryCards(engine.dealer, engine.autoBuryChoice())
    }

    override fun restart(): Boolean = false

    override fun snapshotFor(seat: Int): JsonElement =
        netJson.encodeToJsonElement(SjSnapshot.serializer(), engine.snapshotFor(seat))

    override fun drainEffects(): List<NetMsg.Effect> = engine.events.mapNotNull { ev ->
        when (ev) {
            is ShengjiEngine.SjEvent.Shuffle -> NetMsg.Effect("shuffle")
            is ShengjiEngine.SjEvent.Played -> NetMsg.Effect("played", seat = ev.seat)
            is ShengjiEngine.SjEvent.NewRoundSJ -> null
            is ShengjiEngine.SjEvent.TrickWon -> null
            is ShengjiEngine.SjEvent.HandOver -> NetMsg.Effect(
                "game_over", landlordWon = ev.result.winnerTeam == GdTeam.A
            )
        }
    }

    /** AI 亮主：发牌窗口内随机时机（与单机 VM scheduleBidding 一致） */
    private fun scheduleBidding() {
        bidJobs.forEach { it.cancel() }
        bidJobs.clear()
        if (engine.phase != Phase.BIDDING) return
        val handNo = engine.handNo
        for (seat in 1..3) {
            val options = engine.claimOptions(seat)
            if (options.isEmpty()) continue
            val best = options.maxByOrNull { it.value }!!
            val lv = engine.players[seat].info.aiLevel ?: AiLevel.MEDIUM
            val p = when (lv) { AiLevel.EASY -> 0.45; AiLevel.HARD -> 0.85; else -> 0.7 }
            if (Random.nextDouble() > p) continue
            val delayMs = Random.nextLong(2600, 8600)
            bidJobs += gameScope.launch {
                delay(delayMs)
                if (engine.phase != Phase.BIDDING || engine.handNo != handNo) return@launch
                if (engine.claimTrump(seat, best.key)) trigger()
            }
        }
    }

    /** 兜底：发牌揭示窗口（约 9.5s）+1.5s 亮主末窗后仍未定主则自动收口 */
    private fun scheduleSettleFallback() {
        settleJob?.cancel()
        if (engine.phase != Phase.BIDDING) return
        dealtAt = System.currentTimeMillis()
        val handNo = engine.handNo
        settleJob = gameScope.launch {
            delay(11500)
            if (engine.phase == Phase.BIDDING && engine.handNo == handNo) {
                finishBiddingNow()
                trigger()
            }
        }
    }

    /** AI 庄延时自动扣底（与单机 VM scheduleBury 一致） */
    private fun scheduleBury() {
        buryJob?.cancel()
        if (engine.phase != Phase.BURYING) return
        val d = engine.dealer
        val p = engine.players.getOrNull(d) ?: return
        if (!p.info.isAi) return
        val handNo = engine.handNo
        buryJob = gameScope.launch {
            delay(1400 + Random.nextLong(900))
            if (engine.phase != Phase.BURYING || engine.handNo != handNo) return@launch
            if (engine.buryCards(d, engine.autoBuryChoice())) trigger()
        }
    }

    override fun pumpAi() {
        pumpJob?.cancel()
        if (engine.phase != Phase.PLAYING) return
        val actor = engine.currentTurn
        if (actor <= 0) return
        val p = engine.players.getOrNull(actor) ?: return
        if (!p.info.isAi) return

        val ctx = ShengjiAi.Ctx(
            hand = engine.myHand(actor),
            trumpSuit = engine.trumpSuit,
            levelRank = engine.levelRank,
            dealer = engine.dealer,
            lastLed = engine.ledPlay(),
            trickPlays = engine.trickPlays.toList(),
            oppPoints = engine.oppPoints
        )
        val ai = ShengjiAi(actor, p.info.aiLevel ?: AiLevel.MEDIUM)
        val wait = thinkMs(p.info.aiLevel)
        pumpJob = gameScope.launch {
            delay(wait)
            if (engine.phase != Phase.PLAYING || engine.currentTurn != actor) return@launch
            engine.events.clear()
            var acted = false
            val move = ai.chooseMove(ctx)
            if (move != null) acted = engine.play(actor, move)
            if (!acted) {
                val hand = engine.myHand(actor)
                val led = engine.ledPlay()
                val fallback: List<Card>? = if (led == null) {
                    hand.lastOrNull()?.let { listOf(it) }
                } else {
                    val n = led.count
                    val inSuit = if (led.suit == CardSuit.JOKER)
                        hand.filter { SjRules.isTrump(it, engine.trumpSuit, engine.levelRank) }
                    else hand.filter {
                        it.suit == led.suit && !SjRules.isTrump(it, engine.trumpSuit, engine.levelRank)
                    }
                    if (inSuit.size >= n) inSuit.sortedBy { it.rank }.take(n)
                    else {
                        val rest = hand.filter { c -> inSuit.none { it.id == c.id } }.sortedBy { it.rank }
                        (inSuit + rest).take(n)
                    }
                }
                if (fallback != null) acted = engine.play(actor, fallback)
            }
            if (acted) trigger()
        }
    }

    override fun cancelJobs() {
        bidJobs.forEach { it.cancel() }; bidJobs.clear()
        buryJob?.cancel(); buryJob = null
        pumpJob?.cancel(); pumpJob = null
        settleJob?.cancel(); settleJob = null
    }
}

// ============================================================
// 跑得快（三人/四人由 seatCount 决定）
// ============================================================

private class PdkDriver(
    private val gameScope: CoroutineScope,
    private val trigger: () -> Unit,
    private val seatCount: Int
) : HostDriver {

    override val gameId = "pdk"
    private val engine = PdkEngine()
    private var pumpJob: Job? = null

    override fun newGame(infos: List<PlayerInfo>) {
        engine.newGame(infos, if (seatCount == 3) PdkMode.THREE else PdkMode.FOUR)
    }

    override fun aiNameFor(seat: Int): String =
        listOf("急脚鬼", "飞毛腿", "一阵风", "小旋风")[((seat - 1).coerceAtLeast(0)) % 4]

    override fun aiAvatarFor(seat: Int): Int = listOf(2, 5, 8, 3)[((seat - 1).coerceAtLeast(0)) % 4]

    override fun detachToAi(seat: Int, level: AiLevel) {
        engine.players.getOrNull(seat)?.let { p ->
            if (!p.info.isAi) p.info = p.info.copy(isAi = true, aiLevel = level)
        }
    }

    override fun play(seat: Int, ids: List<Int>): Boolean {
        if (engine.phase != Phase.PLAYING || engine.currentTurn != seat) return false
        return engine.play(seat, Deck.byIds(ids))
    }

    override fun pass(seat: Int): Boolean = engine.pass(seat)

    override fun claim(seat: Int, suit: CardSuit?): Boolean = false

    override fun nextHand(): Boolean = false

    override fun restart(): Boolean {
        if (engine.phase != Phase.GAME_OVER) return false
        engine.newGame(engine.players.map { it.info }, engine.mode)
        return true
    }

    override fun snapshotFor(seat: Int): JsonElement =
        netJson.encodeToJsonElement(PdkSnapshot.serializer(), engine.snapshotFor(seat))

    override fun drainEffects(): List<NetMsg.Effect> = engine.events.mapNotNull { ev ->
        when (ev) {
            is PdkEngine.PdkEvent.Shuffle -> NetMsg.Effect("shuffle")
            is PdkEngine.PdkEvent.TurnTo -> null
            is PdkEngine.PdkEvent.Played -> NetMsg.Effect("played", seat = ev.seat)
            is PdkEngine.PdkEvent.Pass -> NetMsg.Effect("pass", seat = ev.seat)
            is PdkEngine.PdkEvent.NewRound -> NetMsg.Effect("new_round", seat = ev.leader)
            is PdkEngine.PdkEvent.Bomb -> NetMsg.Effect("bomb", seat = ev.seat)
            is PdkEngine.PdkEvent.GameOver -> NetMsg.Effect("game_over", seat = ev.winner)
        }
    }

    override fun pumpAi() {
        pumpJob?.cancel()
        if (engine.phase != Phase.PLAYING) return
        val actor = engine.currentTurn
        if (actor <= 0) return
        val p = engine.players.getOrNull(actor) ?: return
        if (!p.info.isAi) return

        val ctx = PdkAi.Ctx(
            seat = actor,
            hand = engine.myHand(actor),
            lastMove = engine.lastMove,
            lastMoveSeat = engine.lastMoveSeat,
            handCounts = handCountsOf(engine.players),
            playedCards = engine.players.flatMap { it.played }
        )
        val ai = PdkAi(actor, p.info.aiLevel ?: AiLevel.MEDIUM)
        val wait = (when (p.info.aiLevel) {
            AiLevel.EASY -> 650L; AiLevel.HARD -> 1150L; else -> 900L
        }) + Random.nextLong(450)
        pumpJob = gameScope.launch {
            delay(wait)
            if (engine.phase != Phase.PLAYING || engine.currentTurn != actor) return@launch
            engine.events.clear()
            var acted = false
            val move = ai.chooseMove(ctx)
            if (move != null && engine.play(actor, move)) {
                acted = true
            } else if (engine.lastMove != null) {
                acted = engine.pass(actor)
                if (!acted) {
                    val beats = MoveGen.genBeats(engine.myHand(actor), engine.lastMove)
                    if (beats.isNotEmpty()) acted = engine.play(actor, beats.first().cards)
                }
            }
            if (acted) trigger()
        }
    }

    override fun cancelJobs() { pumpJob?.cancel(); pumpJob = null }
}

// ============================================================
// 锄大地
// ============================================================

private class BigTwoDriver(
    private val gameScope: CoroutineScope,
    private val trigger: () -> Unit
) : HostDriver {

    override val gameId = "bigtwo"
    private val engine = BigTwoEngine()
    private var pumpJob: Job? = null

    override fun newGame(infos: List<PlayerInfo>) {
        engine.newGame(infos)
    }

    override fun aiNameFor(seat: Int): String =
        listOf("铁锹叔", "山里红", "老埂头", "泥腿子")[((seat - 1).coerceAtLeast(0)) % 4]

    override fun aiAvatarFor(seat: Int): Int = listOf(4, 7, 9, 6)[((seat - 1).coerceAtLeast(0)) % 4]

    override fun detachToAi(seat: Int, level: AiLevel) {
        engine.players.getOrNull(seat)?.let { p ->
            if (!p.info.isAi) p.info = p.info.copy(isAi = true, aiLevel = level)
        }
    }

    override fun play(seat: Int, ids: List<Int>): Boolean {
        if (engine.phase != Phase.PLAYING || engine.currentTurn != seat) return false
        return engine.play(seat, Deck.byIds(ids))
    }

    override fun pass(seat: Int): Boolean = engine.pass(seat)

    override fun claim(seat: Int, suit: CardSuit?): Boolean = false

    override fun nextHand(): Boolean = false

    override fun restart(): Boolean {
        if (engine.phase != Phase.GAME_OVER) return false
        engine.newGame(engine.players.map { it.info })
        return true
    }

    override fun snapshotFor(seat: Int): JsonElement =
        netJson.encodeToJsonElement(BtSnapshot.serializer(), engine.snapshotFor(seat))

    override fun drainEffects(): List<NetMsg.Effect> = engine.events.mapNotNull { ev ->
        when (ev) {
            is BigTwoEngine.BtEvent.Shuffle -> NetMsg.Effect("shuffle")
            is BigTwoEngine.BtEvent.Played -> NetMsg.Effect("played", seat = ev.seat)
            is BigTwoEngine.BtEvent.Pass -> NetMsg.Effect("pass", seat = ev.seat)
            is BigTwoEngine.BtEvent.NewRound -> NetMsg.Effect("new_round", seat = ev.leader)
            is BigTwoEngine.BtEvent.GameOver -> NetMsg.Effect("game_over", seat = ev.winner)
        }
    }

    override fun pumpAi() {
        pumpJob?.cancel()
        if (engine.phase != Phase.PLAYING) return
        val actor = engine.currentTurn
        if (actor <= 0) return
        val p = engine.players.getOrNull(actor) ?: return
        if (!p.info.isAi) return

        val ctx = BigTwoAi.Ctx(
            hand = engine.myHand(actor),
            lastMove = engine.lastMove,
            lastMoveSeat = engine.lastMoveSeat,
            handCounts = handCountsOf(engine.players),
            playedCards = engine.players.flatMap { it.played }
        )
        val ai = BigTwoAi(actor, p.info.aiLevel ?: AiLevel.MEDIUM)
        val wait = (when (p.info.aiLevel) {
            AiLevel.EASY -> 650L; AiLevel.HARD -> 1150L; else -> 900L
        }) + Random.nextLong(450)
        pumpJob = gameScope.launch {
            delay(wait)
            if (engine.phase != Phase.PLAYING || engine.currentTurn != actor) return@launch
            engine.events.clear()
            var acted = false
            val move = ai.chooseMove(ctx)
            if (move != null) acted = engine.play(actor, move)
            if (!acted && engine.hasTable()) acted = engine.pass(actor)
            if (!acted) {
                val hand = engine.myHand(actor)
                if (hand.isNotEmpty()) acted = engine.play(actor, listOf(hand.last()))
            }
            if (acted) trigger()
        }
    }

    override fun cancelJobs() { pumpJob?.cancel(); pumpJob = null }
}
