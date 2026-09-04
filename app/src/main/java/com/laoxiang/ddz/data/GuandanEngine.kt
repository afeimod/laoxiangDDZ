package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 掼蛋引擎（江苏省掼蛋竞赛规则 · V1 简化版）—— 纯 Kotlin
 *
 * · 4 人 2 队（0+2 一队，1+3 一队），双副牌 108 张，每人 27 张
 * · 级牌：各队从 2 打起；本副级牌 = 上游队当前级；A 必打（打到 A 再赢一副即过关）
 * · 逢人配：红桃级牌可当任意牌（不含大小王）参与组合（顺子/同花顺/炸弹/对子…）
 * · 单张大小：2<3<…<A<级牌<小王<大王（级牌在组合中按原点数参与，仅单张比较时升级）
 * · 牌型：单张/对子/三张/三带二/顺子(恰5张,A2345与10JQKA)/三连对(木板)/二连三(钢板)
 *        /炸弹(≥4同点,张数多者大)/同花顺(压≤5张炸弹)/天王炸(4王,最大)
 * · 首副随机先出；此后头游先出。V1 无进贡还贡
 * · 同型同量比大小；炸弹压一切非炸；全部自由过牌
 * · 三家全过→最后出牌者领出；其若已出完→对家接风领出
 * · 名次：头游/二游/三游/末游。头游方升级：双下(头游二游同队)+3，头游+三游同队+2，+1
 * · 任一队打到 A 且赢一副 → 终局
 */
enum class GdTeam { A, B }

fun gdTeamOf(seat: Int): GdTeam = if (seat % 2 == 0) GdTeam.A else GdTeam.B
fun gdPartner(seat: Int): Int = (seat + 2) % 4

object GdRules {
    /** 掼蛋牌力：2..K 原值，A=14，级牌=16，小王=17，大王=18 */
    fun power(rank: Int, levelRank: Int): Int = when (rank) {
        16 -> 17
        17 -> 18
        levelRank -> 16
        else -> rank
    }

    /** 组合内用原点数（顺子/连对/钢板中级牌按原点参与） */
    const val MAX_BOMB = 8
}

@kotlinx.serialization.Serializable
enum class GdType(val label: String) {
    SINGLE("单张"), PAIR("对子"), TRIO("三张"), TRIO_PAIR("三带二"),
    BANZI("三连对"), GANGBAN("二连三"), STRAIGHT("顺子"),
    BOMB("炸弹"), STRAIGHT_FLUSH("同花顺"), ROCKET("天王炸");

    val isBombLike: Boolean get() = this == BOMB || this == STRAIGHT_FLUSH || this == ROCKET
}

@kotlinx.serialization.Serializable
data class GdMove(
    val cards: List<Card>,
    val type: GdType,
    /** 比较用主牌力（同型同量比这个；级牌相关已折算） */
    val mainPower: Int,
    /** 组数（炸弹张数 / 钢板 2 / 木板 3 / 顺子 5） */
    val length: Int
) {
    fun beats(last: GdMove?, levelRank: Int): Boolean {
        last ?: return true
        // 天王炸最大
        if (type == GdType.ROCKET) return true
        if (last.type == GdType.ROCKET) return false
        // 同花顺：压 ≤5 张炸弹与一切普通型
        if (type == GdType.STRAIGHT_FLUSH) {
            return when (last.type) {
                GdType.STRAIGHT_FLUSH -> mainPower > last.mainPower
                GdType.BOMB -> last.length <= 5 && mainPower > last.mainPower
                else -> true
            }
        }
        if (last.type == GdType.STRAIGHT_FLUSH) return type == GdType.BOMB && length >= 6
        // 炸弹
        if (type == GdType.BOMB) {
            if (last.type != GdType.BOMB) return true
            return length > last.length || (length == last.length && mainPower > last.mainPower)
        }
        if (last.type == GdType.BOMB) return false
        // 普通型：同型同量
        if (type != last.type || length != last.length) return false
        return mainPower > last.mainPower
    }

    companion object {
        /** 逢人配：红桃级牌（可当任意非王牌参与组合） */
        fun isFengrenpei(c: Card, levelRank: Int): Boolean =
            c.rank == levelRank && c.suit == CardSuit.HEART

        /** 识别牌型（[levelRank] 为本副级牌点数 2..14）；红桃级牌按逢人配自动展开取最优 */
        fun of(input: List<Card>, levelRank: Int): GdMove? {
            if (input.isEmpty()) return null
            val wilds = input.filter { isFengrenpei(it, levelRank) }
            if (wilds.isEmpty() || wilds.size == input.size) return ofBase(input, levelRank)
            // 逢人配展开：每张可替代 2..A 任意点数与花色（不能代替大小王），取最优牌型
            val others = input.filter { !isFengrenpei(it, levelRank) }
            val suits = CardSuit.values().filter { it != CardSuit.JOKER }
            var best: GdMove? = null
            fun rec(idx: Int, assign: List<Card>) {
                if (idx == wilds.size) {
                    val mv = ofBase(others + assign, levelRank) ?: return
                    if (best == null || wildBetter(mv, best!!)) best = mv
                    return
                }
                for (r in 2..14) for (s in suits) rec(idx + 1, assign + Card(-1 - idx, r, s))
            }
            rec(0, emptyList())
            return best
        }

        /** 展开候选取更优：炸弹类 > 同花顺 > 钢板/木板/顺子 > 三带二/三张/对/单，同级比主牌力 */
        private fun wildBetter(a: GdMove, b: GdMove): Boolean {
            fun w(m: GdMove): Int = when (m.type) {
                GdType.ROCKET -> 1000
                GdType.STRAIGHT_FLUSH -> 900
                GdType.BOMB -> 800 + m.length * 2
                GdType.GANGBAN -> 600
                GdType.BANZI -> 500
                GdType.STRAIGHT -> 400
                GdType.TRIO_PAIR -> 300
                GdType.TRIO -> 200
                GdType.PAIR -> 100
                GdType.SINGLE -> 0
            }
            val wa = w(a); val wb = w(b)
            return if (wa != wb) wa > wb else a.mainPower > b.mainPower
        }

        /** 基础识别（不含逢人配展开；逢人配在组合内按原点数参与） */
        private fun ofBase(input: List<Card>, levelRank: Int): GdMove? {
            if (input.isEmpty()) return null
            val cs = input.sortedBy { it.rank }
            val n = cs.size
            // 王
            val jokers = cs.filter { it.suit == CardSuit.JOKER }
            val others = cs.filter { it.suit != CardSuit.JOKER }

            // 天王炸：4 王
            if (n == 4 && jokers.size == 4) return GdMove(cs, GdType.ROCKET, 99, 4)
            if (jokers.isNotEmpty() && others.isNotEmpty()) return null   // 王不参与普通组合
            if (jokers.size == 2 && jokers.all { it.rank == jokers[0].rank } && n == 2) {
                return GdMove(cs, GdType.PAIR, GdRules.power(jokers[0].rank, levelRank), 2)
            }
            if (jokers.size == 1 && n == 1) {
                return GdMove(cs, GdType.SINGLE, GdRules.power(jokers[0].rank, levelRank), 1)
            }

            val counts = others.groupBy { it.rank }.mapValues { it.value.size }
            when (n) {
                1 -> return GdMove(cs, GdType.SINGLE, GdRules.power(others[0].rank, levelRank), 1)
                2 -> if (counts.size == 1) return GdMove(cs, GdType.PAIR, GdRules.power(others[0].rank, levelRank), 2)
                3 -> if (counts.size == 1) return GdMove(cs, GdType.TRIO, GdRules.power(others[0].rank, levelRank), 3)
                4 -> if (counts.size == 1) return GdMove(cs, GdType.BOMB, GdRules.power(others[0].rank, levelRank), 4)
                5 -> {
                    if (counts.size == 2 && counts.values.sorted() == listOf(2, 3)) {
                        val trio = counts.filterValues { it == 3 }.keys.first()
                        return GdMove(cs, GdType.TRIO_PAIR, GdRules.power(trio, levelRank), 5)
                    }
                    straightOf(others, levelRank)?.let { return it }
                }
                6 -> {
                    if (counts.size == 1) return GdMove(cs, GdType.BOMB, GdRules.power(others[0].rank, levelRank), 6)
                    // 三连对（木板）
                    if (counts.size == 3 && counts.values.all { it == 2 }) {
                        consecutiveRun(counts.keys.sorted(), 3)?.let { top ->
                            return GdMove(cs, GdType.BANZI, top, 3)
                        }
                    }
                    // 二连三（钢板）
                    if (counts.size == 2 && counts.values.all { it == 3 }) {
                        consecutiveRun(counts.keys.sorted(), 2)?.let { top ->
                            return GdMove(cs, GdType.GANGBAN, top, 2)
                        }
                    }
                }
                7, 8 -> if (counts.size == 1) {
                    return GdMove(cs, GdType.BOMB, GdRules.power(others[0].rank, levelRank), n)
                }
            }
            return null
        }

        /** 恰 5 张顺子（2..A，A2345 与 10JQKA 均可）；同花返回同花顺 */
        private fun straightOf(cs: List<Card>, levelRank: Int): GdMove? {
            if (cs.size != 5) return null
            val ranks = cs.map { it.rank }.sorted()
            val isRun = ranks == listOf(2, 3, 4, 5, 14) ||
                    (ranks.zipWithNext().all { (a, b) -> b - a == 1 } && ranks.last() <= 14)
            if (!isRun) return null
            val top = if (ranks == listOf(2, 3, 4, 5, 14)) 5 else ranks.last()
            val flush = cs.all { it.suit == cs[0].suit }
            return if (flush) GdMove(cs, GdType.STRAIGHT_FLUSH, top, 5)
            else GdMove(cs, GdType.STRAIGHT, top, 5)
        }

        /** 连续 [k] 组（点数升序）；返回最大端点 */
        private fun consecutiveRun(sortedRanks: List<Int>, k: Int): Int? {
            if (sortedRanks.size != k) return null
            if (!sortedRanks.zipWithNext().all { (a, b) -> b - a == 1 }) return null
            return sortedRanks.last()
        }
    }
}

@kotlinx.serialization.Serializable
data class GdHandResult(
    val headSeat: Int,
    val secondSeat: Int,
    val winnerTeam: GdTeam,
    /** 头游方本副升级数（1~3） */
    val upgrade: Int,
    /** 本副结束时双方级牌 */
    val newLevels: Map<GdTeam, Int>,
    /** 终局（赢方打 A 成功） */
    val finalWin: Boolean
)

/** 座位视图（含队伍/等级信息） */
@kotlinx.serialization.Serializable
data class GdSeatView(
    val seat: Int,
    val name: String,
    val avatar: Int,
    val isAi: Boolean,
    val isTurn: Boolean,
    val team: GdTeam,
    val handCount: Int,
    val hand: List<Card> = emptyList(),
    val lastPlayed: List<Card> = emptyList(),
    val lastActionType: LastActionType = LastActionType.NONE,
    val finishedRank: Int = 0
)

@kotlinx.serialization.Serializable
data class GdSnapshot(
    val phase: Phase,
    val handNo: Int,
    /** 本副级牌点数（2..14） */
    val levelRank: Int,
    val teamLevels: Map<GdTeam, Int>,
    val turn: Int,
    val lastMoveSeat: Int,
    val lastMove: GdMove?,
    val seats: List<GdSeatView>,
    val result: GdHandResult?
)

class GuandanEngine(private val randomSeed: Long? = null) {

    private val rng = randomSeed?.let { Random(it) } ?: Random.Default

    val players = ArrayList<PlayerState>(4)
    var phase = Phase.WAITING
        private set
    var currentTurn = -1
        private set
    var lastMove: GdMove? = null
        private set
    var lastMoveSeat = -1
        private set
    private var passStreak = 0
    private var trickLeader = -1          // 本圈领出者
    private var trickLastSeat = -1        // 本圈最后出牌者

    val teamLevels = mutableMapOf(GdTeam.A to 2, GdTeam.B to 2)
    var levelRank = 2
        private set
    var handNo = 0
        private set
    private var nextLeader = -1           // 下副先出者
    var result: GdHandResult? = null
        private set
    private val finishOrder = ArrayList<Int>()

    val events = ArrayList<GdEvent>()

    sealed class GdEvent {
        object Shuffle : GdEvent()
        data class Played(val seat: Int, val move: GdMove) : GdEvent()
        data class Pass(val seat: Int) : GdEvent()
        data class NewRound(val leader: Int) : GdEvent()
        data class Bomb(val seat: Int, val rocket: Boolean, val straightFlush: Boolean) : GdEvent()
        data class HandOver(val result: GdHandResult) : GdEvent()
    }

    /**
     * 显示排序（两副牌混发不分先后）：
     * 牌力降序 → 同牌力按花色 ♠>♥>♣>♦ → 同花色两副本相邻（A♠A♠A♥A♥…，不出现“第一副A第二副A”）
     */
    fun myHand(s: Int): List<Card> = players[s].hand.sortedWith(
        compareByDescending<Card> { GdRules.power(it.rank, levelRank) }
            .thenByDescending { SjRules.suitRank(it.suit) }
            .thenBy { it.id }
    )

    fun snapshotFor(s: Int): GdSnapshot {
        val views = players.map { p ->
            GdSeatView(
                seat = p.info.seat, name = p.info.name, avatar = p.info.avatar,
                isAi = p.info.isAi,
                isTurn = phase == Phase.PLAYING && currentTurn == p.info.seat,
                team = gdTeamOf(p.info.seat),
                handCount = p.hand.size,
                hand = if (p.info.seat == s) myHand(s) else emptyList(),
                lastPlayed = p.lastPlayed,
                lastActionType = p.lastActionType,
                finishedRank = finishOrder.indexOf(p.info.seat).let { if (it >= 0) it + 1 else 0 }
            )
        }
        return GdSnapshot(
            phase = phase, handNo = handNo, levelRank = levelRank,
            teamLevels = teamLevels.toMap(),
            turn = currentTurn, lastMoveSeat = lastMoveSeat, lastMove = lastMove,
            seats = views, result = result
        )
    }

    /** 记牌器：外界剩余（按点数 2..14 + 王） */
    fun remainingCounts(s: Int): Map<Int, Int> {
        val playedAll = players.flatMap { it.played }
        val mine = players[s].hand
        return (2..17).associateWith { r ->
            val total = if (r == 16 || r == 17) 2 else if (r == levelRank) 8 else 8
            total - playedAll.count { it.rank == r } - mine.count { it.rank == r }
        }
    }

    fun newMatch(infos: List<PlayerInfo>) {
        require(infos.size == 4)
        teamLevels.clear()
        teamLevels[GdTeam.A] = 2
        teamLevels[GdTeam.B] = 2
        players.clear()
        infos.forEachIndexed { i, info -> players += PlayerState(info.copy(seat = i)) }
        nextLeader = -1
        newHand()
    }

    /** 开始新一副（级牌=上游队等级；上游=上副头游；首副随机） */
    fun newHand() {
        handNo++
        levelRank = teamLevels.getValue(gdTeamOf(if (nextLeader >= 0) nextLeader else 0))
        players.forEach { p ->
            p.hand.clear(); p.lastPlayed = emptyList()
            p.lastActionType = LastActionType.NONE; p.played.clear()
        }
        finishOrder.clear()
        lastMove = null; lastMoveSeat = -1
        passStreak = 0; trickLeader = -1; trickLastSeat = -1
        result = null
        events.clear()

        val deck = Deck.doubleDeck().shuffled(rng)
        players.forEachIndexed { i, p ->
            // 保留发牌顺序（UI 逐张发牌动画按真实发牌次序揭示）；显示层用 myHand 排序
            p.hand += deck.subList(i * 27, (i + 1) * 27)
        }
        currentTurn = if (nextLeader >= 0) nextLeader else rng.nextInt(4)
        trickLeader = currentTurn
        phase = Phase.PLAYING
        events += GdEvent.Shuffle
        events += GdEvent.NewRound(currentTurn)
    }

    fun play(s: Int, cards: List<Card>): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn || cards.isEmpty()) return false
        val hand = players[s].hand
        if (!cards.all { c -> hand.any { it.id == c.id } }) return false
        val move = GdMove.of(cards, levelRank) ?: return false
        if (!move.beats(lastMove, levelRank)) return false

        cards.forEach { c -> hand.removeAll { it.id == c.id } }
        players[s].played += cards
        players[s].lastPlayed = cards.sortedBy { it.rank }
        players[s].lastActionType = LastActionType.PLAYED
        lastMove = move
        lastMoveSeat = s
        trickLastSeat = s
        passStreak = 0
        events += GdEvent.Played(s, move)
        when {
            move.type == GdType.ROCKET -> events += GdEvent.Bomb(s, rocket = true, straightFlush = false)
            move.type == GdType.STRAIGHT_FLUSH -> events += GdEvent.Bomb(s, rocket = false, straightFlush = true)
            move.type == GdType.BOMB -> events += GdEvent.Bomb(s, rocket = false, straightFlush = false)
        }

        if (hand.isEmpty()) {
            finishOrder += s
            if (finishOrder.size >= 2) {
                endHand()
                return true
            }
        }
        advanceTurn()
        return true
    }

    fun pass(s: Int): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn) return false
        if (lastMove == null) return false       // 领出者不能过
        players[s].lastPlayed = emptyList()
        players[s].lastActionType = LastActionType.PASSED
        events += GdEvent.Pass(s)
        passStreak++
        if (passStreak >= 3) {
            // 一圈结束：领出权归最后出牌者（若已出完 → 对家接风）
            var leader = trickLastSeat
            if (players[leader].hand.isEmpty()) leader = gdPartner(leader)   // 接风
            if (players[leader].hand.isEmpty()) {
                // 接风对象也已出完（理论不可达，防御）→ 找下一个活人
                leader = (0..3).firstOrNull { players[it].hand.isNotEmpty() } ?: leader
            }
            currentTurn = leader
            trickLeader = leader
            lastMove = null
            lastMoveSeat = -1
            passStreak = 0
            players.forEach { if (it.info.seat != leader) { it.lastPlayed = emptyList(); it.lastActionType = LastActionType.NONE } }
            events += GdEvent.NewRound(leader)
        } else {
            advanceTurn()
        }
        return true
    }

    private fun advanceTurn() {
        var next = currentTurn
        repeat(4) {
            next = (next + 1) % 4
            if (players[next].hand.isNotEmpty()) { currentTurn = next; return }
        }
        // 全出完（理论不可达：二游出完即结束）
    }

    private fun endHand() {
        phase = Phase.GAME_OVER
        val head = finishOrder[0]
        val second = finishOrder[1]
        val winTeam = gdTeamOf(head)
        val sameTeam = gdTeamOf(second) == winTeam
        val upgrade = when {
            sameTeam -> 3                       // 双下
            finishOrder.getOrNull(2)?.let { gdTeamOf(it) == winTeam } == true -> 2
            else -> 1
        }
        val newLevels = teamLevels.toMutableMap()
        var finalWin = false
        if (teamLevels.getValue(winTeam) >= 14) {
            finalWin = true                     // 打 A 成功
        } else {
            newLevels[winTeam] = (teamLevels.getValue(winTeam) + upgrade).coerceAtMost(14)
        }
        // 提交等级（v17 修复：此前只进 result 未提交，导致永远打 2）
        teamLevels.clear(); teamLevels.putAll(newLevels)
        nextLeader = head
        result = GdHandResult(head, second, winTeam, upgrade, newLevels, finalWin)
        events += GdEvent.HandOver(result!!)
    }

    /** 结算后进入下一副（终局则不再开） */
    fun nextHandIfPossible(): Boolean {
        if (result?.finalWin == true) return false
        newHand()
        return true
    }
}
