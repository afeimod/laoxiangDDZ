package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 斗地主游戏引擎（权威逻辑，纯 Kotlin，无 Android 依赖）
 * 单机模式本地驱动；局域网房主模式在同一引擎上运行并广播状态快照。
 *
 * 叫抢规则：随机首叫 → 依次「叫地主/不叫」，首个叫者为候选（其余人跳过叫牌）；
 *           从候选下家起其余两家各一次「抢地主/不抢」，每抢一次倍数×2；
 *           最终候选当地主并收底牌。无人叫 → 流局自动重发。
 * 计分规则：底分 100 × 倍数（抢地主 / 炸弹王炸 / 春天各×2）。
 *           地主赢：地主 +2倍，两家农民各 -1倍；地主输反之。
 */
enum class Phase { WAITING, DEALING, BIDDING, ROBBING, PLAYING, GAME_OVER }

@kotlinx.serialization.Serializable
enum class AiLevel(val label: String) { EASY("简单"), MEDIUM("中等"), HARD("困难") }

@kotlinx.serialization.Serializable
data class PlayerInfo(
    val seat: Int,
    val name: String,
    val avatar: Int,
    val isAi: Boolean = false,
    val aiLevel: AiLevel? = null
)

/** 玩家最近动作（驱动"不出/要不起"气泡与桌面牌展示） */
enum class LastActionType { NONE, PLAYED, PASSED }

data class PlayerState(
    var info: PlayerInfo,
    val hand: MutableList<Card> = mutableListOf(),
    var lastPlayed: List<Card> = emptyList(),
    var lastActionType: LastActionType = LastActionType.NONE,
    val played: MutableList<Card> = mutableListOf()
)

@kotlinx.serialization.Serializable
data class GameResult(
    val winnerSeat: Int,
    val landlordWon: Boolean,
    val isSpring: Boolean,
    val isAntiSpring: Boolean,
    val multiplier: Int,
    val landlordScore: Int,
    val baseScore: Int = 100
)

@kotlinx.serialization.Serializable
data class SeatView(
    val seat: Int,
    val name: String,
    val avatar: Int,
    val isAi: Boolean,
    val isLandlord: Boolean,
    val isTurn: Boolean,
    val handCount: Int,
    val hand: List<Card> = emptyList(),
    val lastPlayed: List<Card> = emptyList(),
    val lastActionType: LastActionType = LastActionType.NONE
)

class GameEngine(private val randomSeed: Long? = null) {

    private val rng = randomSeed?.let { Random(it) } ?: Random.Default
    private var redealCount = 0

    val players = ArrayList<PlayerState>(3)
    var phase = Phase.WAITING
        private set

    var bottomCards: List<Card> = emptyList()
        private set
    var bottomRevealed = false
        private set

    var landlord: Int = -1
        private set
    var currentTurn: Int = -1
        private set

    var lastMove: Move? = null
        private set
    var lastMoveSeat: Int = -1
        private set
    private var passStreak = 0

    private var bidCandidate = -1
    private var bidCursor = -1
    private var bidAsked = 0

    private val robQueue = ArrayDeque<Int>()
    private var robCount = 0

    var multiplier = 1
        private set

    var result: GameResult? = null
        private set

    var roundNumber = 0
        private set

    val events = ArrayList<GameEvent>()

    // ------------------------------------------------ 查询

    fun seat(s: Int): PlayerState = players[s]

    fun myHand(s: Int): List<Card> = players[s].hand.sorted()

    fun isMyTurn(s: Int): Boolean = phase == Phase.PLAYING && currentTurn == s

    /** 视角快照：隐藏他人手牌 */
    fun snapshotFor(s: Int): GameSnapshot {
        val seatViews = players.map { p ->
            SeatView(
                seat = p.info.seat,
                name = p.info.name,
                avatar = p.info.avatar,
                isAi = p.info.isAi,
                isLandlord = p.info.seat == landlord,
                isTurn = currentTurn == p.info.seat &&
                        phase in setOf(Phase.PLAYING, Phase.BIDDING, Phase.ROBBING),
                handCount = p.hand.size,
                hand = if (p.info.seat == s) p.hand.sorted() else emptyList(),
                lastPlayed = p.lastPlayed,
                lastActionType = p.lastActionType
            )
        }
        val playedRanks = players.flatMap { it.played }
            .groupBy { it.rank }
            .mapValues { it.value.size }
        return GameSnapshot(
            phase = phase,
            playedRanks = playedRanks,
            round = roundNumber,
            turn = currentTurn,
            landlord = landlord,
            bidCursor = if (phase == Phase.BIDDING) bidCursor else -1,
            robCursor = if (phase == Phase.ROBBING) robQueue.firstOrNull() ?: -1 else -1,
            bidCandidate = bidCandidate,
            robCount = robCount,
            multiplier = multiplier,
            bottomCards = if (bottomRevealed) bottomCards else emptyList(),
            bottomHidden = !bottomRevealed,
            lastMoveSeat = lastMoveSeat,
            lastMove = lastMove,
            seats = seatViews,
            result = result
        )
    }

    /**
     * 记牌器：每种牌剩余的"外界未见"数量
     * = 全副 4 张(王各 1) - 已打出 - 本人手中
     */
    fun remainingCounts(s: Int): Map<Int, Int> {
        val playedAll = players.flatMap { it.played }
        val mine = players[s].hand
        return (3..17).associateWith { r ->
            val total = if (r >= 16) 1 else 4
            total - playedAll.count { it.rank == r } - mine.count { it.rank == r }
        }
    }

    // ------------------------------------------------ 开局

    fun newGame(infos: List<PlayerInfo>) {
        require(infos.size == 3) { "斗地主需要三名玩家" }
        roundNumber++
        players.clear()
        infos.forEachIndexed { i, info -> players += PlayerState(info.copy(seat = i)) }
        deal()
    }

    private fun deal() {
        phase = Phase.DEALING
        lastMove = null
        lastMoveSeat = -1
        passStreak = 0
        landlord = -1
        multiplier = 1
        robCount = 0
        bidCandidate = -1
        bottomRevealed = false
        result = null
        events.clear()

        val deck = Deck.fullDeck().shuffled(rng)
        players.forEachIndexed { i, p ->
            p.hand += deck.subList(i * 17, (i + 1) * 17)
            p.hand.sort()
            p.lastPlayed = emptyList()
            p.lastActionType = LastActionType.NONE
            p.played.clear()
        }
        bottomCards = deck.subList(51, 54).sorted()
        events += GameEvent.Shuffle

        bidCursor = rng.nextInt(3)
        bidAsked = 0
        phase = Phase.BIDDING
        events += GameEvent.TurnTo(bidCursor, phase)
    }

    private fun redeal() {
        redealCount++
        if (redealCount >= 4) {
            // 防死循环：连续流局时按手牌强度强制指定地主
            forceBestHandLandlord()
            return
        }
        val infos = players.map { it.info }
        players.clear()
        roundNumber++
        infos.forEachIndexed { i, info -> players += PlayerState(info.copy(seat = i)) }
        deal()
        events += GameEvent.Redeal
    }

    private fun forceBestHandLandlord() {
        // 评估手牌强度，最高者直接成为地主
        val best = players.indices.maxBy { s -> handStrength(players[s].hand) }
        bidCandidate = best
        finishBidding()
    }

    // ------------------------------------------------ 叫地主 / 抢地主

    /** 返回 false 表示当前无权操作（UI/网络层已挡，双保险） */
    fun callLandlord(s: Int, call: Boolean): Boolean {
        if (phase != Phase.BIDDING || s != bidCursor) return false
        if (call) {
            bidCandidate = s
            events += GameEvent.BidCall(s)
            // 其余两家依次抢一次
            robQueue.clear()
            robQueue += nextSeat(s)
            robQueue += nextSeat(nextSeat(s))
            phase = Phase.ROBBING
            events += GameEvent.TurnTo(robQueue.first(), phase)
        } else {
            events += GameEvent.BidPass(s)
            bidAsked++
            if (bidAsked >= 3) {
                redeal()
                return true
            }
            bidCursor = nextSeat(s)
            events += GameEvent.TurnTo(bidCursor, phase)
        }
        return true
    }

    fun robLandlord(s: Int, rob: Boolean): Boolean {
        if (phase != Phase.ROBBING) return false
        val cursor = robQueue.firstOrNull() ?: return false
        if (s != cursor) return false
        robQueue.removeFirst()
        if (rob) {
            bidCandidate = s
            robCount++
            multiplier *= 2
            events += GameEvent.Rob(s)
        } else {
            events += GameEvent.RobPass(s)
        }
        if (robQueue.isEmpty()) {
            finishBidding()
        } else {
            events += GameEvent.TurnTo(robQueue.first(), phase)
        }
        return true
    }

    private fun finishBidding() {
        if (bidCandidate < 0) {
            redeal()
            return
        }
        landlord = bidCandidate
        val lp = players[landlord]
        lp.hand += bottomCards
        lp.hand.sort()
        bottomRevealed = true
        events += GameEvent.LandlordSet(landlord, bottomCards)
        currentTurn = landlord
        lastMove = null
        lastMoveSeat = -1
        passStreak = 0
        phase = Phase.PLAYING
        events += GameEvent.TurnTo(currentTurn, phase)
    }

    // ------------------------------------------------ 出牌

    fun play(s: Int, cards: List<Card>): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn || cards.isEmpty()) return false
        val hand = players[s].hand
        if (!cards.all { c -> hand.any { it.id == c.id } }) return false
        val move = Move.of(cards) ?: return false
        if (!move.beats(lastMove)) return false

        cards.forEach { c -> hand.removeAll { it.id == c.id } }
        players[s].played += cards
        players[s].lastPlayed = cards.sorted()
        players[s].lastActionType = LastActionType.PLAYED

        if (move.type == MoveType.ROCKET || move.type == MoveType.BOMB) {
            multiplier *= 2
            events += GameEvent.Bomb(s, move.type == MoveType.ROCKET)
        }
        if (move.type in setOf(MoveType.PLANE, MoveType.PLANE_SINGLE, MoveType.PLANE_PAIR)) {
            events += GameEvent.Plane(s)
        }

        lastMove = move
        lastMoveSeat = s
        passStreak = 0
        events += GameEvent.Played(s, move)

        if (hand.isEmpty()) {
            endGame(s)
            return true
        }
        advanceTurn()
        return true
    }

    /** 要不起 / 不出 */
    fun pass(s: Int): Boolean {
        if (phase != Phase.PLAYING || s != currentTurn) return false
        if (lastMove == null) return false   // 领出者不能过
        players[s].lastPlayed = emptyList()
        players[s].lastActionType = LastActionType.PASSED
        events += GameEvent.Pass(s)
        passStreak++
        if (passStreak >= 2) {
            // 两家不要 → 上家重新领出，清桌
            currentTurn = lastMoveSeat
            lastMove = null
            passStreak = 0
            players.forEach {
                if (it.info.seat != lastMoveSeat) {
                    it.lastPlayed = emptyList()
                    it.lastActionType = LastActionType.NONE
                }
            }
            events += GameEvent.NewRound(currentTurn)
        } else {
            advanceTurn()
        }
        return true
    }

    private fun advanceTurn() {
        currentTurn = nextSeat(currentTurn)
        events += GameEvent.TurnTo(currentTurn, phase)
    }

    private fun nextSeat(s: Int) = (s + 1) % 3

    // ------------------------------------------------ 结算

    private fun endGame(winnerSeat: Int) {
        phase = Phase.GAME_OVER
        val landlordWon = winnerSeat == landlord
        val farmersPlayed = players.filter { it.info.seat != landlord }.sumOf { it.played.size }
        val landlordPlays = players[landlord].played.size
        var spring = false
        var antiSpring = false
        if (landlordWon && farmersPlayed == 0) {
            spring = true; multiplier *= 2
        } else if (!landlordWon && landlordPlays <= 1) {
            antiSpring = true; multiplier *= 2
        }
        val total = 100 * multiplier
        val landlordScore = if (landlordWon) 2 * total else -2 * total
        result = GameResult(winnerSeat, landlordWon, spring, antiSpring, multiplier, landlordScore)
        events += GameEvent.GameOver(landlordWon, spring, antiSpring)
    }

    // ------------------------------------------------ 事件

    sealed class GameEvent {
        object Shuffle : GameEvent()
        object Redeal : GameEvent()
        data class TurnTo(val seat: Int, val phase: Phase) : GameEvent()
        data class BidCall(val seat: Int) : GameEvent()
        data class BidPass(val seat: Int) : GameEvent()
        data class Rob(val seat: Int) : GameEvent()
        data class RobPass(val seat: Int) : GameEvent()
        data class LandlordSet(val seat: Int, val bottom: List<Card>) : GameEvent()
        data class Played(val seat: Int, val move: Move) : GameEvent()
        data class Pass(val seat: Int) : GameEvent()
        data class NewRound(val leader: Int) : GameEvent()
        data class Bomb(val seat: Int, val rocket: Boolean) : GameEvent()
        data class Plane(val seat: Int) : GameEvent()
        data class GameOver(val landlordWon: Boolean, val spring: Boolean, val antiSpring: Boolean) : GameEvent()
    }

    companion object {
        /**
         * 手牌强度评估（叫抢决策与流局兜底用）
         * 王炸/炸弹/大牌/双王加权
         */
        fun handStrength(hand: List<Card>): Int {
            val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
            var score = 0
            val jokers = counts.keys.count { it >= 16 }
            if (jokers == 2) score += 22          // 王炸
            else if (jokers == 1) score += 10
            counts.forEach { (r, c) ->
                when {
                    c == 4 -> score += 16          // 炸弹
                    c == 3 -> score += if (r >= 14) 8 else 4
                    r == 15 -> score += c          // 2
                    r == 14 -> score += c / 2
                }
            }
            return score
        }
    }
}

/** 只读快照（跨线程 / 网络传输用） */
@kotlinx.serialization.Serializable
data class GameSnapshot(
    val phase: Phase,
    /** 已出牌的 rank 计数（记牌器用，公开信息） */
    val playedRanks: Map<Int, Int> = emptyMap(),
    val round: Int,
    val turn: Int,
    val landlord: Int,
    val bidCursor: Int,
    val robCursor: Int,
    val bidCandidate: Int,
    val robCount: Int,
    val multiplier: Int,
    val bottomCards: List<Card>,
    val bottomHidden: Boolean,
    val lastMoveSeat: Int,
    val lastMove: Move?,
    val seats: List<SeatView>,
    val result: GameResult?
)
