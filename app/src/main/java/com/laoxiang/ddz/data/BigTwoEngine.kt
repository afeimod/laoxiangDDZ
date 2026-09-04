package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 锄大地（Big Two / 大老二）引擎 —— 纯 Kotlin，无 Android 依赖
 *
 * 规则（联众/欢乐斗地主锄大地通行版）：
 * · 4 人各 13 张，52 张无王
 * · 单张大小：3<4<…<K<A<2（2 最大）；同点比花色 ♦<♣<♥<♠
 * · 牌型：单张 / 对子 / 三张 / 五张型（顺子<同花<葫芦<铁支<同花顺）
 * · 顺子范围：23456 最小 … 10JQKA 最大（2 只能在 23456 中作小牌，JQKA2 不合法）
 * · 五张型同类别比最大牌（同点比花色）；葫芦比三条、铁支比四条
 * · 持方块3者先出，第一手必须包含方块3；此后每轮可自由过牌
 * · 三家全过 → 最后出牌者重新领出；先出完者胜，一局即分胜负
 * · 计分：输家各付 10 + 剩牌数×2，赢家全收
 */
enum class BtType(val label: String, val count: Int) {
    SINGLE("单张", 1),
    PAIR("对子", 2),
    TRIO("三张", 3),
    STRAIGHT("顺子", 5),
    FLUSH("同花", 5),
    FULLHOUSE("葫芦", 5),
    QUAD("铁支", 5),
    STRAIGHT_FLUSH("同花顺", 5);

    /** 五张型类别强度（越大越大；1/2/3 张型为 0） */
    val catRank: Int get() = when (this) {
        STRAIGHT -> 1; FLUSH -> 2; FULLHOUSE -> 3; QUAD -> 4; STRAIGHT_FLUSH -> 5; else -> 0
    }
}

/** 花色大小：♦<♣<♥<♠（同点单张比大小用） */
fun suitPower(s: CardSuit): Int = when (s) {
    CardSuit.SPADE -> 4; CardSuit.HEART -> 3; CardSuit.CLUB -> 2; CardSuit.DIAMOND -> 1
    else -> 0
}

@kotlinx.serialization.Serializable
data class BtMove(
    val cards: List<Card>,
    val type: BtType,
    /** 主牌点（顺子/同花顺为最大端点；23456 的端点是 6） */
    val mainRank: Int,
    /** 主牌花色（同点比较时用） */
    val mainSuit: Int
) {
    /** 能否压过 [last]（null=领出，任意合法型可出） */
    fun beats(last: BtMove?): Boolean {
        last ?: return true
        if (cards.size != last.cards.size) return false
        if (type.catRank != last.type.catRank) return type.catRank > last.type.catRank
        if (mainRank != last.mainRank) return mainRank > last.mainRank
        return mainSuit > last.mainSuit
    }

    companion object {
        /** 牌型识别；不合法返回 null */
        fun of(input: List<Card>): BtMove? {
            if (input.isEmpty()) return null
            val cs = input.sortedBy { it.rank }
            val n = cs.size
            val counts = cs.groupBy { it.rank }.mapValues { it.value.size }
            val top = cs.maxBy { it.rank }

            when (n) {
                1 -> return BtMove(cs, BtType.SINGLE, top.rank, suitPower(top.suit))
                2 -> if (counts.size == 1) {
                    val hi = cs.maxBy { suitPower(it.suit) }
                    return BtMove(cs, BtType.PAIR, top.rank, suitPower(hi.suit))
                }
                3 -> if (counts.size == 1) return BtMove(cs, BtType.TRIO, top.rank, suitPower(top.suit))
                5 -> {
                    val flush = cs.all { it.suit == cs[0].suit }
                    val run = runTop(cs)
                    if (run != null) {
                        val topCard = cs.filter { it.rank == run.second }.maxBy { suitPower(it.suit) }
                        val tp = if (flush) BtType.STRAIGHT_FLUSH else BtType.STRAIGHT
                        return BtMove(cs, tp, run.second, suitPower(topCard.suit))
                    }
                    if (flush) {
                        return BtMove(cs, BtType.FLUSH, top.rank, suitPower(top.suit))
                    }
                    if (counts.size == 2 && counts.values.sorted() == listOf(2, 3)) {
                        val trioRank = counts.filterValues { it == 3 }.keys.first()
                        return BtMove(cs, BtType.FULLHOUSE, trioRank, 0)
                    }
                    if (counts.size == 2 && counts.values.sorted() == listOf(1, 4)) {
                        val quadRank = counts.filterValues { it == 4 }.keys.first()
                        return BtMove(cs, BtType.QUAD, quadRank, 0)
                    }
                }
            }
            return null
        }

        /**
         * 判断 5 张是否构成顺子；返回 (是否顺子, 最大端点)。
         * 合法：34567..10JQKA 与 23456（2 作小牌）；JQKA2 / QKA23 不合法。
         */
        private fun runTop(cs: List<Card>): Pair<Boolean, Int>? {
            val ranks = cs.map { it.rank }.sorted()
            // 23456 特例："2"=rank15（锄大地牌库无 rank2）
            if (ranks == listOf(3, 4, 5, 6, 15)) return true to 6
            val ok = ranks.zipWithNext().all { (a, b) -> b - a == 1 } && ranks.last() <= 14
            return if (ok) true to ranks.last() else null
        }
    }
}

@kotlinx.serialization.Serializable
data class BtResult(
    val winnerSeat: Int,
    val scoreDelta: Map<Int, Int>,
    val remain: Map<Int, Int>
)

/** 座位视图（复用跑得快的通用形状） */
typealias BtSeatView = PdkSeatView

@kotlinx.serialization.Serializable
data class BtSnapshot(
    val phase: Phase,
    val round: Int,
    val turn: Int,
    val lastMoveSeat: Int,
    val lastMove: BtMove?,
    val seats: List<BtSeatView>,
    val playedRanks: Map<Int, Int>,
    val result: BtResult?
)

class BigTwoEngine(private val randomSeed: Long? = null) {

    private val rng = randomSeed?.let { Random(it) } ?: Random.Default

    val players = ArrayList<PlayerState>(4)
    var phase = Phase.WAITING
        private set
    var currentTurn: Int = -1
        private set
    var lastMove: BtMove? = null
        private set
    var lastMoveSeat: Int = -1
        private set
    private var passStreak = 0
    private var diamond3Seat = -1
    private var firstLeadDone = false
    var roundNumber = 0
        private set
    var result: BtResult? = null
        private set
    private val finishOrder = ArrayList<Int>()

    val events = ArrayList<BtEvent>()

    sealed class BtEvent {
        object Shuffle : BtEvent()
        data class Played(val seat: Int, val move: BtMove) : BtEvent()
        data class Pass(val seat: Int) : BtEvent()
        data class NewRound(val leader: Int) : BtEvent()
        data class GameOver(val winner: Int) : BtEvent()
    }

    fun myHand(s: Int): List<Card> = players[s].hand.sortedWith(
        compareByDescending<Card> { it.rank }.thenByDescending { suitPower(it.suit) }
    )

    fun snapshotFor(s: Int): BtSnapshot {
        val views = players.map { p ->
            BtSeatView(
                seat = p.info.seat,
                name = p.info.name,
                avatar = p.info.avatar,
                isAi = p.info.isAi,
                isTurn = phase == Phase.PLAYING && currentTurn == p.info.seat,
                handCount = p.hand.size,
                hand = if (p.info.seat == s) myHand(s) else emptyList(),
                lastPlayed = p.lastPlayed,
                lastActionType = p.lastActionType,
                finishedRank = finishOrder.indexOf(p.info.seat).let { if (it >= 0) it + 1 else 0 }
            )
        }
        val playedRanks = players.flatMap { it.played }.groupBy { it.rank }.mapValues { it.value.size }
        return BtSnapshot(
            phase = phase, round = roundNumber, turn = currentTurn,
            lastMoveSeat = lastMoveSeat, lastMove = lastMove,
            seats = views, playedRanks = playedRanks, result = result
        )
    }

    fun newGame(infos: List<PlayerInfo>) {
        require(infos.size == 4) { "锄大地需要4名玩家" }
        roundNumber++
        players.clear()
        infos.forEachIndexed { i, info -> players += PlayerState(info.copy(seat = i)) }
        deal()
    }

    private fun deal() {
        phase = Phase.PLAYING
        lastMove = null; lastMoveSeat = -1
        passStreak = 0; result = null
        finishOrder.clear(); firstLeadDone = false
        diamond3Seat = -1
        events.clear()

        val deck = Deck.fullDeck().filter { it.rank < 16 }.shuffled(rng)
        players.forEachIndexed { i, p ->
            p.hand += deck.subList(i * 13, (i + 1) * 13)
            p.hand.sort()
            p.lastPlayed = emptyList()
            p.lastActionType = LastActionType.NONE
            p.played.clear()
        }
        players.forEachIndexed { i, p ->
            if (p.hand.any { it.rank == 3 && it.suit == CardSuit.DIAMOND }) diamond3Seat = i
        }
        currentTurn = diamond3Seat.coerceAtLeast(0)
        events += BtEvent.Shuffle
    }

    /** 桌面是否有牌（有牌可自由过，无牌必须领出） */
    fun hasTable(): Boolean = lastMove != null

    fun play(s: Int, cards: List<Card>): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn || cards.isEmpty()) return false
        val hand = players[s].hand
        if (!cards.all { c -> hand.any { it.id == c.id } }) return false
        val move = BtMove.of(cards) ?: return false
        if (!move.beats(lastMove)) return false
        if (!firstLeadDone) {
            if (s != diamond3Seat) return false
            if (cards.none { it.rank == 3 && it.suit == CardSuit.DIAMOND }) return false
        }
        cards.forEach { c -> hand.removeAll { it.id == c.id } }
        players[s].played += cards
        players[s].lastPlayed = cards.sortedBy { it.rank }
        players[s].lastActionType = LastActionType.PLAYED
        firstLeadDone = true
        lastMove = move
        lastMoveSeat = s
        passStreak = 0
        events += BtEvent.Played(s, move)
        if (hand.isEmpty()) {
            finishOrder += s
            endGame()
            return true
        }
        advanceTurn()
        return true
    }

    /** 锄大地允许自由过牌（桌上有牌时） */
    fun pass(s: Int): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn) return false
        if (lastMove == null) return false      // 领出者不能过
        players[s].lastPlayed = emptyList()
        players[s].lastActionType = LastActionType.PASSED
        events += BtEvent.Pass(s)
        passStreak++
        if (passStreak >= players.size - 1) {
            currentTurn = lastMoveSeat
            lastMove = null
            passStreak = 0
            players.forEach {
                if (it.info.seat != lastMoveSeat) {
                    it.lastPlayed = emptyList()
                    it.lastActionType = LastActionType.NONE
                }
            }
            events += BtEvent.NewRound(currentTurn)
        } else {
            advanceTurn()
        }
        return true
    }

    private fun advanceTurn() {
        var next = currentTurn
        repeat(players.size) {
            next = (next + 1) % players.size
            if (players[next].hand.isNotEmpty()) { currentTurn = next; return }
        }
        endGame() // 全部出完（理论上先出完即结束，不会到这）
    }

    private fun endGame() {
        phase = Phase.GAME_OVER
        val winner = finishOrder[0]
        val delta = players.associate { p ->
            val seat = p.info.seat
            seat to if (seat == winner) {
                players.filter { it.info.seat != winner }.sumOf { 10 + it.hand.size * 2 }
            } else -(10 + p.hand.size * 2)
        }
        val remain = players.associate { it.info.seat to it.hand.size }
        result = BtResult(winner, delta, remain)
        events += BtEvent.GameOver(winner)
    }
}
