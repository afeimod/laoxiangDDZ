package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 升级（拖拉机 / 80分）引擎 —— 双副牌 V2，纯 Kotlin
 *
 * · 4 人 2 队（0+2 一队 1+3 一队），双副牌 108 张一起发（不分先后），每人 25 张、底牌 8 张
 * · 发牌后进入 BIDDING 定主阶段（UI 慢速发牌逐张揭示，玩家随发随亮）：
 *   抢主优先级 对大王(无主) > 对小王(无主) > 对级牌 > 单张级牌；后亮者须严格更高才反主
 * · 主牌 = 双王(4) + 级牌(8) [+ 主花色(24)]；主牌序：主花色2..A(跳级牌)<副级牌<主级牌<小王<大王
 * · 牌型：单张 / 对子 / 拖拉机（同花色或主牌的相邻连续对子，≥2 对）
 * · 跟牌（v17 修正）：领出副牌时，有该花色（非主牌）必须跟足张数；无该花色可任意垫/主杀；
 *   领出主牌时，有主必须跟主。级牌属主牌，不算领出花色的跟牌（修主杀被误禁）
 * · 扣底（v17 新增阶段）：定主后庄家捡起 8 张底牌（手牌 33），由庄家手动扣回 8 张后开局
 * · 一圈最大：杀过副；主牌比序；同点先出为大；最大方收圈
 * · 分牌：5=5分、10=10分、K=10分（每副共 200 分）；闲家收分
 * · 底牌 8 张最后一圈归闲家时底分×2 计入
 * · 升级表（闲家得分 p）：p<5 庄升3；5~35 庄升2；40~75 庄升1；80~115 闲上台；
 *   120~155/160~195/200~235/240+ → 闲上台+1/+2/+3/+4
 * · A 必打：升过 A 停在 A，打 A 成功（庄或闲以 A 为级赢）→ 终局
 */
object SjRules {

    /** 是否主牌 */
    fun isTrump(c: Card, trumpSuit: CardSuit, levelRank: Int): Boolean =
        c.suit == CardSuit.JOKER || c.rank == levelRank || c.suit == trumpSuit

    /**
     * 主牌内部序（越大越大）：
     * 主花色 2..A 跳过级牌 → idx=rank；副10 → 15；主10 → 16；小王 → 17；大王 → 18
     */
    fun trumpIndex(c: Card, trumpSuit: CardSuit, levelRank: Int): Int = when {
        c.rank == 17 -> 18
        c.rank == 16 -> 17
        c.rank == levelRank -> if (c.suit == trumpSuit) 16 else 15
        else -> c.rank
    }

    /** 花色权重（同序比花色）：♠>♥>♣>♦ */
    fun suitRank(s: CardSuit): Int = when (s) {
        CardSuit.SPADE -> 4; CardSuit.HEART -> 3; CardSuit.CLUB -> 2; CardSuit.DIAMOND -> 1
        else -> 0
    }

    fun cardScore(c: Card): Int = when (c.rank) {
        5 -> 5
        10 -> 10
        13 -> 10
        else -> 0
    }

    fun isPoint(c: Card): Boolean = cardScore(c) > 0
}

/** 出牌牌型（THROW=同花色/主牌混合组合，可出但不能争圈，除非内含目标同型组） */
@kotlinx.serialization.Serializable
enum class SjType(val label: String) {
    SINGLE("单张"), PAIR("对子"), TRACTOR("拖拉机"), THROW("组合")
}

@kotlinx.serialization.Serializable
data class SjPlay(
    val cards: List<Card>,
    val type: SjType,
    /** 领出花色（甩牌 V1 不支持；拖拉机为构成花色） */
    val suit: CardSuit,
    /** 组单元数（单1/对2/拖拉机对数） */
    val unit: Int,
    /** 主导牌力（拖拉机=最大对的主牌序；副牌=rank） */
    val power: Int
) {
    val count: Int get() = cards.size
}

@kotlinx.serialization.Serializable
data class SjHandResult(
    val dealerTeam: GdTeam,
    /** 闲家总得分（含抠底） */
    val oppPoints: Int,
    val winnerTeam: GdTeam,
    /** 赢方升级数（0=仅上台不升级） */
    val upgrade: Int,
    val newLevels: Map<GdTeam, Int>,
    /** 本副新庄（赢方队伍） */
    val newDealerTeam: GdTeam,
    val finalWin: Boolean,
    /** 闲家是否上台（换庄） */
    val switched: Boolean
)

@kotlinx.serialization.Serializable
data class SjSeatView(
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
    /** 定主阶段：观局者的真实发牌顺序手牌（供逐张发牌动画） */
    val handDealOrder: List<Card> = emptyList()
)

@kotlinx.serialization.Serializable
data class SjSnapshot(
    val phase: Phase,
    val handNo: Int,
    val levelRank: Int,
    /** 定主完成后才有值；BIDDING 期间为 null */
    val trumpSuit: CardSuit?,
    val dealer: Int,
    val teamLevels: Map<GdTeam, Int>,
    val turn: Int,
    val lastPlaySeat: Int,
    val lastPlay: SjPlay?,
    /** 本圈各家出的牌（含已出） */
    val trickPlays: List<Pair<Int, List<Card>>>,
    /** 闲家已得分 */
    val oppPoints: Int,
    val seats: List<SjSeatView>,
    val result: SjHandResult?,
    /** 定主进度：已亮主者座位（-1=未亮）；claimSuit=null 表示亮无主 */
    val claimSeat: Int = -1,
    val claimSuit: CardSuit? = null,
    val claimNT: Boolean = false,
    val claimTier: Int = 0,
    /** 扣底阶段：庄家捡起的 8 张底牌（其他阶段不暴露） */
    val kitty: List<Card> = emptyList()
)

class ShengjiEngine(private val randomSeed: Long? = null) {

    private val rng = randomSeed?.let { Random(it) } ?: Random.Default

    val players = ArrayList<PlayerState>(4)
    var phase = Phase.WAITING
        private set
    var currentTurn = -1
        private set
    var handNo = 0
        private set
    var dealer = 0
        private set
    var trumpSuit: CardSuit = CardSuit.SPADE
        private set
    val teamLevels = mutableMapOf(GdTeam.A to 2, GdTeam.B to 2)
    var levelRank = 2
        private set

    var lastPlay: SjPlay? = null
        private set
    var lastPlaySeat = -1
        private set

    /** 本圈各家出的牌 */
    val trickPlays = ArrayList<Pair<Int, List<Card>>>()
    private var trickLeader = -1
    private var trickLed: SjPlay? = null
    private var trickLedSuit = CardSuit.SPADE
    private var trickUnit = 1
    private var trickType = SjType.SINGLE

    /** 闲家累计得分 */
    var oppPoints = 0
        private set

    var result: SjHandResult? = null
        private set
    private var nextDealerTeam: GdTeam = GdTeam.A

    val events = ArrayList<SjEvent>()

    sealed class SjEvent {
        object Shuffle : SjEvent()
        data class Played(val seat: Int, val play: SjPlay) : SjEvent()
        data class TrickWon(val winner: Int, val points: Int) : SjEvent()
        data class NewRoundSJ(val leader: Int) : SjEvent()
        data class HandOver(val result: SjHandResult) : SjEvent()
    }

    fun dealerTeam(): GdTeam = gdTeamOf(dealer)
    fun oppTeam(): GdTeam = if (dealerTeam() == GdTeam.A) GdTeam.B else GdTeam.A

    fun myHand(s: Int): List<Card> = sortForDisplay(players[s].hand, displayTrump())

    /** 显示用主花色：定主/扣底后用已定主花色（无则已亮主，再无 null=中性排序） */
    fun displayTrump(): CardSuit? =
        if (phase == Phase.PLAYING || phase == Phase.GAME_OVER || phase == Phase.BURYING)
            trumpSuit else claimedTrump

    /**
     * 显示排序：
     * trump != null → 主牌（王/级牌/主花色，序内降序）在左，副牌按花色分组（♠>♥>♣>♦）组内点数降序
     * trump == null → 王 > 级牌 > 其余按花色分组（♠>♥>♣>♦）组内点数降序（自动分为四花色+主牌）
     * 同点同花色两副本相邻（双副牌混发不分先后）
     */
    fun sortForDisplay(hand: List<Card>, trump: CardSuit?): List<Card> {
        val tr = trump
        return hand.sortedWith(
            compareByDescending<Card> { tr != null && SjRules.isTrump(it, tr, levelRank) }
                .thenBy { c ->
                    if (tr != null && SjRules.isTrump(c, tr, levelRank))
                        -SjRules.trumpIndex(c, tr, levelRank)
                    else 0
                }
                .thenByDescending { if (it.suit == CardSuit.JOKER) 5 else 0 }
                .thenByDescending { it.rank == levelRank }
                .thenByDescending { SjRules.suitRank(it.suit) }
                .thenByDescending { it.rank }
                .thenBy { it.id }
        )
    }

    fun snapshotFor(s: Int): SjSnapshot {
        val tr = displayTrump()
        val bidding = phase == Phase.BIDDING
        val views = players.map { p ->
            SjSeatView(
                seat = p.info.seat, name = p.info.name, avatar = p.info.avatar,
                isAi = p.info.isAi,
                isTurn = phase == Phase.PLAYING && currentTurn == p.info.seat,
                team = gdTeamOf(p.info.seat),
                handCount = p.hand.size,
                hand = if (p.info.seat == s) sortForDisplay(p.hand, tr) else emptyList(),
                lastPlayed = p.lastPlayed,
                lastActionType = p.lastActionType,
                handDealOrder = if (bidding && p.info.seat == s) p.hand.toList() else emptyList()
            )
        }
        return SjSnapshot(
            phase = phase, handNo = handNo, levelRank = levelRank, trumpSuit = tr,
            dealer = dealer, teamLevels = teamLevels.toMap(), turn = currentTurn,
            lastPlaySeat = lastPlaySeat, lastPlay = lastPlay,
            trickPlays = trickPlays.toList(), oppPoints = oppPoints,
            seats = views, result = result,
            claimSeat = claimSeat, claimSuit = claimedTrump, claimNT = claimNT, claimTier = claimTier,
            kitty = if (phase == Phase.BURYING) kitty else emptyList()
        )
    }

    fun newMatch(infos: List<PlayerInfo>) {
        require(infos.size == 4)
        teamLevels.clear(); teamLevels[GdTeam.A] = 2; teamLevels[GdTeam.B] = 2
        players.clear()
        infos.forEachIndexed { i, info -> players += PlayerState(info.copy(seat = i)) }
        dealer = 0
        nextDealerTeam = GdTeam.A
        newHand()
    }

    fun newHand() {
        handNo++
        dealer = nextDealerSeat()
        levelRank = teamLevels.getValue(dealerTeam())
        claimedTrump = null
        claimSeat = -1
        claimTier = 0
        claimNT = false
        players.forEach { p ->
            p.hand.clear(); p.lastPlayed = emptyList()
            p.lastActionType = LastActionType.NONE; p.played.clear()
        }
        trickPlays.clear(); trickLeader = -1
        lastPlay = null; lastPlaySeat = -1
        oppPoints = 0
        result = null
        events.clear()

        // 双副牌 108 张混洗后一起发（不分第一副第二副）：25×4 + 底 8
        val deck = Deck.doubleDeck().shuffled(rng)
        players.forEachIndexed { i, p ->
            p.hand += deck.subList(i * 25, (i + 1) * 25)
        }
        kitty = deck.subList(100, 108)

        // 进入定主阶段：UI 慢速发牌、玩家随发随亮；finishBidding() 后才开始出牌
        phase = Phase.BIDDING
        currentTurn = -1
        events += SjEvent.Shuffle
    }

    var kitty: List<Card> = emptyList()
        private set

    // ------------------------------------------------ 定主（亮主）竞标

    /** 当前已亮主者（-1=未亮） */
    var claimSeat = -1
        private set
    var claimedTrump: CardSuit? = null
        private set
    var claimNT = false
        private set
    var claimTier = 0
        private set

    /**
     * 抢主优先级（对标欢乐升级「抢主顺序：对大王>对小王>对主牌>单张」）：
     * tier 4 = 对大王（亮无主）；tier 3 = 对小王（亮无主）；tier 2 = 对级牌；tier 1 = 单张级牌
     */
    fun claimTierOf(seat: Int, suit: CardSuit?): Int {
        val hand = players.getOrNull(seat)?.hand ?: return 0
        return if (suit == null) {
            when {
                hand.count { it.rank == 17 } >= 2 -> 4      // 对大王
                hand.count { it.rank == 16 } >= 2 -> 3      // 对小王
                else -> 0
            }
        } else {
            val n = hand.count { it.rank == levelRank && it.suit == suit }
            when {
                n >= 2 -> 2                                  // 对级牌
                n == 1 -> 1                                  // 单张级牌
                else -> 0
            }
        }
    }

    /** 该玩家当前可亮的选项（含花色/无主与 tier） */
    fun claimOptions(seat: Int): Map<CardSuit?, Int> {
        val out = LinkedHashMap<CardSuit?, Int>()
        CardSuit.values().filter { it != CardSuit.JOKER }.forEach { s ->
            val t = claimTierOf(seat, s)
            if (t > 0) out[s] = t
        }
        val tnt = claimTierOf(seat, null)
        if (tnt > 0) out[null] = tnt
        return out
    }

    /**
     * 亮主/反主：后亮者 tier 必须严格大于当前才生效（首亮任意有效选项即可）。
     * suit=null 表示亮无主（主牌只剩王+级牌）。
     */
    fun claimTrump(seat: Int, suit: CardSuit?): Boolean {
        if (phase != Phase.BIDDING) return false
        val tier = claimTierOf(seat, suit)
        if (tier == 0) return false
        if (claimSeat >= 0 && tier <= claimTier) return false
        claimedTrump = suit ?: CardSuit.JOKER      // 无主用 JOKER 哨兵
        claimSeat = seat
        claimTier = tier
        claimNT = suit == null
        return true
    }

    /**
     * 定主结束：已亮主则用之；否则四家级牌最多的花色自动定主；亮主者坐庄。
     * 庄家捡起 8 张底牌（手牌 33 张）→ 进入 BURYING 扣底阶段，庄家手动扣回 8 张后开局。
     */
    fun finishBidding() {
        if (phase != Phase.BIDDING) return
        if (claimSeat >= 0) {
            trumpSuit = claimedTrump!!
            dealer = claimSeat
        } else {
            // 自动定主：级牌最多的花色（并列取先遇）
            val counts = LinkedHashMap<CardSuit, Int>().withDefault { 0 }
            players.forEach { p ->
                p.hand.filter { it.rank == levelRank }.forEach { counts[it.suit] = counts.getValue(it.suit) + 1 }
            }
            trumpSuit = counts.entries.maxByOrNull { it.value }?.key
                ?: CardSuit.values()[rng.nextInt(4)]
        }
        claimNT = claimedTrump == CardSuit.JOKER
        // 庄家捡 8 张底 → 扣底阶段（庄家手动扣 8 张，AI 由 ViewModel 自动代扣）
        players[dealer].hand.addAll(kitty)
        phase = Phase.BURYING
        currentTurn = dealer
    }

    // ------------------------------------------------ 扣底

    /** 庄家扣底：从 33 张手牌中扣 [cards]（恰 8 张）为新底牌；成功后进入 PLAYING，庄家领出 */
    fun buryCards(s: Int, cards: List<Card>): Boolean {
        if (phase != Phase.BURYING || s != dealer) return false
        val hand = players[s].hand
        if (cards.size != 8 || cards.distinctBy { it.id }.size != 8) return false
        if (!cards.all { c -> hand.any { it.id == c.id } }) return false
        kitty = cards
        cards.forEach { c -> hand.removeIf { it.id == c.id } }
        phase = Phase.PLAYING
        currentTurn = dealer
        trickLeader = dealer
        events += SjEvent.NewRoundSJ(dealer)
        return true
    }

    /** 自动扣底策略（AI 用/玩家“自动扣”）：优先垫最小非分副牌，保主保分 */
    fun autoBuryChoice(): List<Card> {
        check(phase == Phase.BURYING) { "非扣底阶段" }
        val hand = players[dealer].hand
        check(hand.size == 33) { "扣底前庄家应有33张，实际 ${hand.size}" }
        val trumpCards = hand.filter { SjRules.isTrump(it, trumpSuit, levelRank) }
        val sidePoints = hand.filter { !SjRules.isTrump(it, trumpSuit, levelRank) && SjRules.isPoint(it) }
        val sidePlain = hand.filter { !SjRules.isTrump(it, trumpSuit, levelRank) && !SjRules.isPoint(it) }
            .sortedBy { it.rank }
        val bury = ArrayList<Card>(8)
        bury += sidePlain.take(8)
        if (bury.size < 8) bury += sidePoints.sortedBy { it.rank }.take(8 - bury.size)
        if (bury.size < 8) bury += trumpCards.sortedBy { SjRules.trumpIndex(it, trumpSuit, levelRank) }
            .take(8 - bury.size)
        return bury.take(8)
    }

    /** 下一副庄家：赢方队伍内轮换 */
    private fun nextDealerSeat(): Int {
        val t = nextDealerTeam
        return if (gdTeamOf(dealer) == t) gdPartner(dealer)
        else if (t == GdTeam.A) 0 else 1
    }

    // ------------------------------------------------ 牌型解析


    // ------------------------------------------------ 出牌

    /** 本圈领出（跟牌花色/牌型的依据；null=待领出）。lastPlay 是最后出牌，两者不同！ */
    fun ledPlay(): SjPlay? = trickLed

    /** [cards] 是否为当前玩家合法可出的牌 */
    fun validatePlay(s: Int, cards: List<Card>): SjPlay? {
        if (phase != Phase.PLAYING || s != currentTurn) return null
        val hand = players[s].hand
        if (cards.isEmpty() || !cards.all { c -> hand.any { it.id == c.id } }) return null
        val p = parsePlay(cards, trumpSuit, levelRank, allowMixed = trickLed != null) ?: return null
        val led = trickLed ?: return p
        // 数量必须等于领出数量
        if (p.count != led.count) return null
        // 跟牌约束（v17 修正）：
        // · 领出副牌 → 手中有该花色非主牌必须跟足（级牌属主牌，不算该花色）；
        //   无该花色/不足时可任意垫牌或用主牌杀
        // · 领出主牌 → 有主必须跟主
        val ledSuit = trickLedSuit
        fun isFollow(c: Card): Boolean =
            if (ledSuit == CardSuit.JOKER) SjRules.isTrump(c, trumpSuit, levelRank)
            else c.suit == ledSuit && !SjRules.isTrump(c, trumpSuit, levelRank)
        val inSuit = hand.count { isFollow(it) }
        val need = minOf(led.count, inSuit)
        val used = cards.count { isFollow(it) }
        if (used < need) return null
        return p
    }

    fun play(s: Int, cards: List<Card>): Boolean {
        val p = validatePlay(s, cards) ?: return false
        val hand = players[s].hand
        cards.forEach { c -> hand.removeIf { it.id == c.id } }
        players[s].played += cards
        players[s].lastPlayed = cards
        players[s].lastActionType = LastActionType.PLAYED

        if (trickLed == null) {
            trickLed = p
            trickLedSuit = p.suit
            trickUnit = p.unit
            trickType = p.type
        }
        trickPlays += s to cards
        lastPlay = p
        lastPlaySeat = s
        events += SjEvent.Played(s, p)

        if (trickPlays.size == 4) {
            resolveTrick()
        } else {
            var next = currentTurn
            var found = false
            repeat(4) {
                if (!found) {
                    next = (next + 1) % 4
                    if (players[next].hand.isNotEmpty()) { currentTurn = next; found = true }
                }
            }
        }
        return true
    }

    /** 判一圈大小并结算 */
    private fun resolveTrick() {
        val led = trickLed!!
        var bestSeat = trickPlays[0].first
        var bestKey: Triple<Int, Int, Int>? = playWinKey(trickPlays[0].second, led)
        for (i in 1 until trickPlays.size) {
            val key = playWinKey(trickPlays[i].second, led) ?: continue
            if (bestKey == null || keyGreater(key, bestKey)) {
                bestKey = key
                bestSeat = trickPlays[i].first
            }
        }
        val pts = trickPlays.sumOf { it.second.sumOf { c -> SjRules.cardScore(c) } }
        if (pts > 0 && gdTeamOf(bestSeat) == oppTeam()) {
            oppPoints += pts
        }
        events += SjEvent.TrickWon(bestSeat, if (gdTeamOf(bestSeat) == oppTeam()) pts else 0)

        trickPlays.clear()
        trickLed = null
        players.forEach {
            it.lastPlayed = emptyList()
            if (it.info.seat != bestSeat) it.lastActionType = LastActionType.NONE
        }
        lastPlay = null
        lastPlaySeat = -1

        if (players.all { it.hand.isEmpty() }) {
            endHand(bestSeat)
        } else {
            currentTurn = bestSeat
            trickLeader = bestSeat
            events += SjEvent.NewRoundSJ(bestSeat)
        }
    }

    /** 争圈键三元组比较 */
    private fun keyGreater(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Boolean =
        a.first > b.first || (a.first == b.first && (a.second > b.second ||
                (a.second == b.second && a.third > b.third)))

    /** 一手牌对本圈的"争圈键"：null=无同型组不能争圈；越大越大 */
    private fun playWinKey(cards: List<Card>, led: SjPlay): Triple<Int, Int, Int>? {
        val t = trumpSuit; val lr = levelRank
        var best: Triple<Int, Int, Int>? = null
        val shape = when (led.type) {
            SjType.PAIR -> SjType.PAIR
            SjType.TRACTOR -> SjType.TRACTOR
            SjType.THROW -> when {
                led.unit == 2 -> SjType.PAIR
                led.unit >= 3 -> SjType.TRACTOR
                else -> null
            }
            else -> null
        }
        if (shape == null) {
            // 目标为单张：领出单张或全单组合
            cards.forEach { c ->
                val key = cardWinKey(c, led.suit, t, lr) ?: return@forEach
                if (best == null || keyGreater(key, best!!)) best = key
            }
        } else {
            groupKeys(cards, shape, led.suit, t, lr).forEach { key ->
                if (best == null || keyGreater(key, best!!)) best = key
            }
        }
        return best
    }

    /** 单张能否争圈：(是否主, 序, 花色权重)；副牌须为领出花色 */
    private fun cardWinKey(c: Card, ledSuit: CardSuit, t: CardSuit, lr: Int): Triple<Int, Int, Int>? {
        return when {
            SjRules.isTrump(c, t, lr) -> Triple(1, SjRules.trumpIndex(c, t, lr), SjRules.suitRank(c.suit))
            c.suit == ledSuit -> Triple(0, c.rank, SjRules.suitRank(c.suit))
            else -> null
        }
    }

    /** 对/拖拉机同型组键（副牌组必须为领出花色 [ledSuit] 才能争圈） */
    private fun groupKeys(
        cards: List<Card>,
        type: SjType,
        ledSuit: CardSuit,
        t: CardSuit,
        lr: Int
    ): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        val trumpCards = cards.filter { SjRules.isTrump(it, t, lr) }
        val sideCards = cards.filter { !SjRules.isTrump(it, t, lr) && ledSuit != CardSuit.JOKER && it.suit == ledSuit }
        // 副牌组（须同花色且为领出花色才有效——调用处用 ledSuit 过滤）
        listOf(trumpCards to true, sideCards to false).forEach { (cs, isTrumpGroup) ->
            val pairs = collectPairs(cs)
            if (type == SjType.PAIR) {
                pairs.forEach { (rankKey, _) ->
                    out += groupKeyOf(isTrumpGroup, rankKey.first, rankKey.second)
                }
            } else {
                tractorsOf(pairs).forEach { tract ->
                    out += groupKeyOf(isTrumpGroup, tract.second, tract.third)
                }
            }
        }
        return out
    }

    private fun groupKeyOf(isTrumpGroup: Boolean, idx: Int, sRank: Int): Triple<Int, Int, Int> =
        Triple(if (isTrumpGroup) 1 else 0, idx, sRank)

    /** 收集对子：键=(序, 花色权重) → 张数 */
    private fun collectPairs(cs: List<Card>): Map<Pair<Int, Int>, List<Card>> {
        // 主牌按 trumpIndex 分组；副牌同花色同 rank 分组
        val t = trumpSuit; val lr = levelRank
        val groups = HashMap<Pair<Int, Int>, MutableList<Card>>()
        cs.groupBy { c ->
            if (SjRules.isTrump(c, t, lr)) {
                // 主牌：同 index 的两张（同花色）才算一对；主10/副10 花色不同需按花色分开
                Pair(SjRules.trumpIndex(c, t, lr), SjRules.suitRank(c.suit))
            } else {
                Pair(c.rank, SjRules.suitRank(c.suit))
            }
        }.forEach { (k, v) -> groups[k] = v.toMutableList() }
        return groups.filterValues { it.size >= 2 }
    }

    /** 从对子集合找拖拉机（同花色相邻），返回列表（最大对序, 对数） */
    private fun tractorsOf(pairs: Map<Pair<Int, Int>, List<Card>>): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        pairs.entries.groupBy { it.key.second }   // 按花色权重分组
            .forEach { (_, entries) ->
                val sorted = entries.map { it.key.first }.sorted()
                var run = 1
                for (i in 1 until sorted.size) {
                    if (sorted[i] == sorted[i - 1] + 1) run++ else {
                        if (run >= 2) out += Triple(sorted[i - 1], run, entries.first().key.second)
                        run = 1
                    }
                }
                if (run >= 2) out += Triple(sorted.last(), run, entries.first().key.second)
            }
        return out
    }

    // ------------------------------------------------ 结算

    private fun endHand(lastTrickWinner: Int) {
        phase = Phase.GAME_OVER
        // 抠底：最后收圈为闲家 → 底分×2
        var kittyPts = kitty.sumOf { SjRules.cardScore(it) }
        if (gdTeamOf(lastTrickWinner) == oppTeam()) {
            oppPoints += kittyPts * 2
        } else {
            kittyPts = 0
        }
        val p = oppPoints
        val dTeam = dealerTeam()
        val oTeam = oppTeam()
        val (winner, up, switched) = when {
            p < 5 -> Triple(dTeam, 3, false)
            p < 40 -> Triple(dTeam, 2, false)
            p < 80 -> Triple(dTeam, 1, false)
            p < 120 -> Triple(oTeam, 0, true)     // 上台不升级
            p < 160 -> Triple(oTeam, 1, true)
            p < 200 -> Triple(oTeam, 2, true)
            p < 240 -> Triple(oTeam, 3, true)
            else -> Triple(oTeam, 4, true)
        }
        val newLevels = teamLevels.toMutableMap()
        var finalWin = false
        if (teamLevels.getValue(winner) >= 14) {
            finalWin = true
        } else if (up > 0) {
            newLevels[winner] = (teamLevels.getValue(winner) + up).coerceAtMost(14)
        }
        // 提交等级（v17 修复：此前只进 result 未提交，导致永远打 2）
        teamLevels.clear(); teamLevels.putAll(newLevels)
        nextDealerTeam = winner
        result = SjHandResult(dTeam, p, winner, up, newLevels, winner, finalWin, switched)
        events += SjEvent.HandOver(result!!)
    }

    fun nextHandIfPossible(): Boolean {
        if (result?.finalWin == true) return false
        newHand()
        return true
    }
}

// ------------------------------------------------ 牌型解析（顶层，供引擎/AI/测试共用）

/**
 * 解析一手牌：领出必须同花色或全主牌；跟牌（allowMixed=true）允许任意混合垫牌。
 * 返回 SjPlay；不合法返回 null。主牌 suit=JOKER 作哨兵。
 */
fun parsePlay(cards: List<Card>, t: CardSuit, lr: Int, allowMixed: Boolean = false): SjPlay? {
    if (cards.isEmpty()) return null
    val allTrump = cards.all { SjRules.isTrump(it, t, lr) }
    val allSide = cards.none { SjRules.isTrump(it, t, lr) }
    val suit = if (allTrump) CardSuit.JOKER else cards[0].suit
    val homogeneous = allTrump || (allSide && cards.all { it.suit == suit })
    if (!homogeneous && !allowMixed) return null
    val effSuit = if (allTrump || !allSide) CardSuit.JOKER else suit

    if (cards.size == 1) {
        val c = cards[0]
        val pw = if (allTrump) SjRules.trumpIndex(c, t, lr) else c.rank
        return SjPlay(cards, SjType.SINGLE, effSuit, 1, pw)
    }
    if (cards.size == 2) {
        val (a, b) = cards
        val same = a.rank == b.rank && a.suit == b.suit
        if (same) {
            val pw = if (allTrump) SjRules.trumpIndex(a, t, lr) else a.rank
            return SjPlay(cards, SjType.PAIR, effSuit, 1, pw)
        }
        // 非对子：两张同花散牌或任意混合 → 垫牌 THROW（不能单独争圈）
        val (unit, pw) = biggestGroup(cards, t, lr)
        return SjPlay(cards, SjType.THROW, effSuit, unit, pw)
    }
    // 拖拉机：偶数张，成对且相邻（主牌按 trumpIndex，副牌按 rank）
    if (cards.size % 2 == 0) {
        val byKey = if (allTrump) {
            cards.groupBy { SjRules.trumpIndex(it, t, lr) }
        } else {
            cards.groupBy { it.rank }
        }
        if (byKey.values.all { it.size == 2 }) {
            val keys = byKey.keys.sorted()
            if (keys.zipWithNext().all { (a, b) -> b - a == 1 }) {
                return SjPlay(cards, SjType.TRACTOR, effSuit, keys.size, keys.last())
            }
        }
    }
    // 组合（垫/甩）：同花色/全主或任意混合，记录内含最大同型组作为争圈目标
    val (unit, pw) = biggestGroup(cards, t, lr)
    return SjPlay(cards, SjType.THROW, effSuit, unit, pw)
}

/**
 * 组合内最大同型组（争圈目标编码）：
 * 单张→unit=1；对子→unit=2；k 对拖拉机→unit=2+k（≥3）
 */
fun biggestGroup(cards: List<Card>, t: CardSuit, lr: Int): Pair<Int, Int> {
    val allTrump = cards.all { SjRules.isTrump(it, t, lr) }
    val byKey = if (allTrump) cards.groupBy { SjRules.trumpIndex(it, t, lr) }
    else cards.groupBy { it.rank }
    val pairRanks = byKey.filterValues { it.size >= 2 }.keys.sorted()
    var bestStart = -1; var bestLen = 1
    var run = 1
    for (i in 1 until pairRanks.size) {
        run = if (pairRanks[i] == pairRanks[i - 1] + 1) run + 1 else 1
        if (run > bestLen) { bestLen = run; bestStart = i - run + 1 }
    }
    if (bestLen >= 2) {
        val top = pairRanks[bestStart + bestLen - 1]
        return ((2 + bestLen) to top)
    }
    if (pairRanks.isNotEmpty()) return (2 to pairRanks.last())
    val maxPw = cards.maxOf { if (allTrump) SjRules.trumpIndex(it, t, lr) else it.rank }
    return (1 to maxPw)
}
