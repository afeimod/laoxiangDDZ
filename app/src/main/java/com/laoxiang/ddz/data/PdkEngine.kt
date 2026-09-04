package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 跑得快（Pao De Kuai / Run Fast）引擎 —— 纯 Kotlin，无 Android 依赖
 *
 * 玩法规则（主流通用规则）：
 * · 三人局：一副牌去掉大小王、三个 2（只留黑桃2）、黑桃A，共 48 张，每人 16 张
 * · 四人局：一副牌去掉大小王，共 52 张，每人 13 张
 * · 牌型：单张/对子/三张/三带一/三带二/顺子(≥5,到A)/连对(≥3)/飞机(可带)/四带二/炸弹；无王炸
 * · 首出：持黑桃3者先出，且第一手必须包含黑桃3
 * · 有牌必压：上家出牌后，手里有能压过的牌必须出（不能过）；压不过才能过
 * · 全家都过 → 最后出牌者重新领出
 * · 先出完手中牌者获胜，一局即分胜负
 * · 计分：底分 10 × 2^炸弹数（封顶 ×8）；赢家收 (人数-1) 份，输家各付 1 份
 */
enum class PdkMode(val players: Int, val label: String, val desc: String) {
    THREE(3, "跑得快 · 三人", "48张牌 · 每人16张 · 黑桃三先出"),
    FOUR(4, "跑得快 · 四人", "52张牌 · 每人13张 · 各自为战")
}

@kotlinx.serialization.Serializable
data class PdkResult(
    val winnerSeat: Int,
    /** 各座位分数变动（正=赢） */
    val scoreDelta: Map<Int, Int>,
    /** 各座位剩牌数 */
    val remain: Map<Int, Int>,
    val multiplier: Int
)

/** 跑得快座位视图（隐藏他人手牌） */
@kotlinx.serialization.Serializable
data class PdkSeatView(
    val seat: Int,
    val name: String,
    val avatar: Int,
    val isAi: Boolean,
    val isTurn: Boolean,
    val handCount: Int,
    val hand: List<Card> = emptyList(),
    val lastPlayed: List<Card> = emptyList(),
    val lastActionType: LastActionType = LastActionType.NONE,
    /** 名次：0=未定，1=第一名…（先出完的标记） */
    val finishedRank: Int = 0
)

/** 跑得快只读快照 */
@kotlinx.serialization.Serializable
data class PdkSnapshot(
    val mode: PdkMode,
    val phase: Phase,
    val round: Int,
    val turn: Int,
    val lastMoveSeat: Int,
    val lastMove: Move?,
    val seats: List<PdkSeatView>,
    val playedRanks: Map<Int, Int>,
    val result: PdkResult?
)

class PdkEngine(private val randomSeed: Long? = null) {

    private val rng = randomSeed?.let { Random(it) } ?: Random.Default

    val players = ArrayList<PlayerState>(4)
    var mode: PdkMode = PdkMode.THREE
        private set
    var phase = Phase.WAITING
        private set

    var currentTurn: Int = -1
        private set
    var lastMove: Move? = null
        private set
    var lastMoveSeat: Int = -1
        private set
    private var passStreak = 0

    /** 首出限制：第一手必须包含黑桃3 */
    private var spade3Seat = -1
    private var firstLeadDone = false

    var bombCount = 0
        private set
    var multiplier = 1
        private set

    var result: PdkResult? = null
        private set
    var roundNumber = 0
        private set

    /** 已出完的座位按名次记录 */
    private val finishOrder = ArrayList<Int>()

    val events = ArrayList<PdkEvent>()

    // ------------------------------------------------ 查询

    fun myHand(s: Int): List<Card> = players[s].hand.sorted()

    fun snapshotFor(s: Int): PdkSnapshot {
        val views = players.map { p ->
            PdkSeatView(
                seat = p.info.seat,
                name = p.info.name,
                avatar = p.info.avatar,
                isAi = p.info.isAi,
                isTurn = phase == Phase.PLAYING && currentTurn == p.info.seat,
                handCount = p.hand.size,
                hand = if (p.info.seat == s) p.hand.sorted() else emptyList(),
                lastPlayed = p.lastPlayed,
                lastActionType = p.lastActionType,
                finishedRank = finishOrder.indexOf(p.info.seat).let { if (it >= 0) it + 1 else 0 }
            )
        }
        val playedRanks = players.flatMap { it.played }
            .groupBy { it.rank }
            .mapValues { it.value.size }
        return PdkSnapshot(
            mode = mode,
            phase = phase,
            round = roundNumber,
            turn = currentTurn,
            lastMoveSeat = lastMoveSeat,
            lastMove = lastMove,
            seats = views,
            playedRanks = playedRanks,
            result = result
        )
    }

    /** 记牌器：外界剩余（3..15，无王） */
    fun remainingCounts(s: Int): Map<Int, Int> {
        val playedAll = players.flatMap { it.played }
        val mine = players[s].hand
        return (3..15).associateWith { r ->
            4 - playedAll.count { it.rank == r } - mine.count { it.rank == r }
        }
    }

    // ------------------------------------------------ 开局

    fun newGame(infos: List<PlayerInfo>, mode: PdkMode) {
        require(infos.size == mode.players) { "跑得快${mode.label}需要${mode.players}名玩家" }
        this.mode = mode
        roundNumber++
        players.clear()
        infos.forEachIndexed { i, info -> players += PlayerState(info.copy(seat = i)) }
        deal()
    }

    private fun deal() {
        phase = Phase.PLAYING
        lastMove = null
        lastMoveSeat = -1
        passStreak = 0
        bombCount = 0
        multiplier = 1
        result = null
        finishOrder.clear()
        firstLeadDone = false
        spade3Seat = -1
        events.clear()

        val deck = buildDeck(mode).shuffled(rng)
        val n = mode.players
        val per = deck.size / n
        players.forEachIndexed { i, p ->
            p.hand += deck.subList(i * per, (i + 1) * per)
            p.hand.sort()
            p.lastPlayed = emptyList()
            p.lastActionType = LastActionType.NONE
            p.played.clear()
        }

        // 持黑桃3者先出
        val spade3 = Card(0, 3, CardSuit.SPADE)   // id 0 = ♠3（Deck 顺序 3♠ 为 id0）
        players.forEachIndexed { i, p ->
            if (p.hand.any { it.rank == 3 && it.suit == CardSuit.SPADE }) spade3Seat = i
        }
        currentTurn = spade3Seat.coerceAtLeast(0)
        events += PdkEvent.Shuffle
        events += PdkEvent.TurnTo(currentTurn)
    }

    /** 构建牌库：三人 48 张（去王/三个2留黑桃2/去黑桃A），四人 52 张（去王） */
    private fun buildDeck(mode: PdkMode): List<Card> {
        val all = Deck.fullDeck()
        val noJoker = all.filter { it.rank < 16 }
        return if (mode == PdkMode.THREE) {
            noJoker.filter {
                !(it.rank == 15 && it.suit != CardSuit.SPADE) &&   // 只留黑桃2
                        !(it.rank == 14 && it.suit == CardSuit.SPADE) // 去黑桃A
            }
        } else noJoker
    }

    // ------------------------------------------------ 出牌 / 过牌

    /** 当前该谁出牌时，[s] 是否有能压过 lastMove 的招（决定"不能过"） */
    fun hasBeat(s: Int): Boolean {
        if (lastMove == null) return false
        return MoveGen.genBeats(myHand(s), lastMove).isNotEmpty()
    }

    fun play(s: Int, cards: List<Card>): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn || cards.isEmpty()) return false
        val hand = players[s].hand
        if (!cards.all { c -> hand.any { it.id == c.id } }) return false
        val move = Move.of(cards) ?: return false

        if (lastMove != null && !move.beats(lastMove)) return false
        // 第一手必须带黑桃3
        if (!firstLeadDone) {
            if (s != spade3Seat) return false
            if (cards.none { it.rank == 3 && it.suit == CardSuit.SPADE }) return false
        }

        cards.forEach { c -> hand.removeAll { it.id == c.id } }
        players[s].played += cards
        players[s].lastPlayed = cards.sorted()
        players[s].lastActionType = LastActionType.PLAYED
        firstLeadDone = true

        if (move.type == MoveType.BOMB) {
            bombCount++
            multiplier = (multiplier * 2).coerceAtMost(8)
            events += PdkEvent.Bomb(s)
        }

        lastMove = move
        lastMoveSeat = s
        passStreak = 0
        events += PdkEvent.Played(s, move)

        if (hand.isEmpty()) {
            finishOrder += s
            endGame()
            return true
        }
        advanceTurn()
        return true
    }

    /** 过牌：仅当桌上有牌且自己无牌可压时合法（有牌必压） */
    fun pass(s: Int): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn) return false
        if (lastMove == null) return false          // 领出者不能过
        if (hasBeat(s)) return false                // 有牌必压，压得过不能过
        players[s].lastPlayed = emptyList()
        players[s].lastActionType = LastActionType.PASSED
        events += PdkEvent.Pass(s)
        passStreak++
        if (passStreak >= players.size - 1) {
            // 其余全过 → 最后出牌者重新领出
            currentTurn = lastMoveSeat
            lastMove = null
            passStreak = 0
            players.forEach {
                if (it.info.seat != lastMoveSeat) {
                    it.lastPlayed = emptyList()
                    it.lastActionType = LastActionType.NONE
                }
            }
            events += PdkEvent.NewRound(currentTurn)
        } else {
            advanceTurn()
        }
        return true
    }

    private fun advanceTurn() {
        currentTurn = (currentTurn + 1) % players.size
        events += PdkEvent.TurnTo(currentTurn)
    }

    // ------------------------------------------------ 结算

    private fun endGame() {
        phase = Phase.GAME_OVER
        val n = players.size
        val base = 10 * multiplier
        val delta = players.associate { p ->
            val seat = p.info.seat
            seat to if (seat == finishOrder[0]) base * (n - 1) else -base
        }
        val remain = players.associate { it.info.seat to it.hand.size }
        result = PdkResult(finishOrder[0], delta, remain, multiplier)
        events += PdkEvent.GameOver(finishOrder[0])
    }

    // ------------------------------------------------ 事件

    sealed class PdkEvent {
        object Shuffle : PdkEvent()
        data class TurnTo(val seat: Int) : PdkEvent()
        data class Played(val seat: Int, val move: Move) : PdkEvent()
        data class Pass(val seat: Int) : PdkEvent()
        data class NewRound(val leader: Int) : PdkEvent()
        data class Bomb(val seat: Int) : PdkEvent()
        data class GameOver(val winner: Int) : PdkEvent()
    }
}
