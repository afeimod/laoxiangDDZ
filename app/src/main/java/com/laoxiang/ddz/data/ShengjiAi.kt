package com.laoxiang.ddz.data

/**
 * 升级 AI：三档难度（V1 基础策略）
 * · 领出：副牌长门 A/大对/拖拉机先走；主牌强时调主清主
 * · 跟牌：有花色跟最小；无花色且圈里有分→最小能赢的主牌杀；没分就垫
 * · 扣底策略在引擎内（保主保分）
 */
class ShengjiAi(private val seat: Int, private val level: AiLevel) {

    data class Ctx(
        val hand: List<Card>,
        val trumpSuit: CardSuit,
        val levelRank: Int,
        val dealer: Int,
        val lastLed: SjPlay?,
        val trickPlays: List<Pair<Int, List<Card>>>,
        val oppPoints: Int
    )

    private fun isTrump(c: Card, ctx: Ctx) = SjRules.isTrump(c, ctx.trumpSuit, ctx.levelRank)

    fun chooseMove(ctx: Ctx): List<Card>? {
        val hand = ctx.hand
        return if (ctx.lastLed == null) lead(hand, ctx) else follow(hand, ctx)
    }

    // ------------------------------------------------ 领出

    private fun lead(hand: List<Card>, ctx: Ctx): List<Card> {
        val trumpCount = hand.count { isTrump(it, ctx) }
        val sideBySuit = hand.filter { !isTrump(it, ctx) }.groupBy { it.suit }

        // 拖拉机优先（副牌长门）
        if (level != AiLevel.EASY) {
            sideBySuit.forEach { (_, cs) ->
                ShengjiAi.findTractor(cs, ctx, trump = false)?.let { return it }
            }
            // 主牌拖拉机
            ShengjiAi.findTractor(hand.filter { isTrump(it, ctx) }, ctx, trump = true)?.let {
                if (trumpCount >= 8) return it
            }
        }
        // 副牌 A 或最大对
        sideBySuit.values.maxByOrNull { it.size }?.let { cs ->
            val aces = cs.filter { it.rank == 14 }
            if (aces.isNotEmpty()) return listOf(aces.first())
            val pairs = cs.groupBy { it.rank }.filterValues { it.size >= 2 }
            if (pairs.isNotEmpty()) {
                return pairs.maxByOrNull { it.key }!!.value.take(2)
            }
            return listOf(cs.maxBy { it.rank })
        }
        // 主牌
        val trumps = hand.filter { isTrump(it, ctx) }
        if (trumps.isNotEmpty()) {
            // 先出大王/小王
            trumps.maxByOrNull { SjRules.trumpIndex(it, ctx.trumpSuit, ctx.levelRank) }?.let { return listOf(it) }
        }
        return listOf(hand.first())
    }

    // ------------------------------------------------ 跟牌

    private fun follow(hand: List<Card>, ctx: Ctx): List<Card>? {
        val led = ctx.lastLed ?: return null
        val n = led.count
        val t = ctx.trumpSuit
        val lr = ctx.levelRank
        val ledSuit = led.suit

        // 跟牌池（v17 修正）：领出副牌=该花色非主牌（级牌属主不算）；领出主牌=全部主牌
        val inSuit = if (ledSuit == CardSuit.JOKER) hand.filter { isTrump(it, ctx) }
        else hand.filter { it.suit == ledSuit && !isTrump(it, ctx) }
        if (inSuit.size >= n) {
            val combo = cheapestComboInSuit(inSuit, led, t, lr)
            if (combo != null) return combo
        }
        // 不足领出花色（或为空）：
        val trumps = hand.filter { isTrump(it, ctx) }.sortedBy { SjRules.trumpIndex(it, t, lr) }
        // 圈里有分且对方要收 → 用主杀（需能形成争圈组）
        val trickPts = ctx.trickPlays.sumOf { it.second.sumOf { c -> SjRules.cardScore(c) } }
        val winnerTeamNow = currentWinnerTeam(ctx)
        if (trickPts > 0 && winnerTeamNow != myTeam() && trumps.size >= n && inSuit.size < n && level != AiLevel.EASY) {
            when (led.type) {
                SjType.SINGLE -> return listOf(trumps.last())   // 最大主单杀
                SjType.PAIR -> {
                    val pair = parsePlay(trumps.takeLast(2), t, lr)
                    if (pair?.type == SjType.PAIR) return trumps.takeLast(2)
                }
                SjType.TRACTOR, SjType.THROW -> {
                    if (led.type == SjType.TRACTOR && n >= 4) {
                        val kill = trumps.takeLast(n)
                        if (parsePlay(kill, t, lr)?.type == SjType.TRACTOR) return kill
                    }
                }
            }
        }
        // 垫牌：必须带上全部领出花色的牌（规则），再补任意牌凑数
        val mustKeep = inSuit   // inSuit.size < n（否则上方已返回）
        val rest = hand.filter { c -> mustKeep.none { it.id == c.id } }
        val plainFirst = rest.sortedWith(
            compareBy<Card> { SjRules.isPoint(it) || isTrump(it, ctx) }.thenBy { it.rank }
        )
        return pickN(mustKeep + plainFirst, n)
    }

    private fun pickN(sorted: List<Card>, n: Int): List<Card> =
        if (sorted.size > n) sorted.take(n) else sorted

    /** 领出花色内凑出合法的 n 张（优先跟型：对跟对、拖拉机跟拖拉机） */
    private fun cheapestComboInSuit(inSuit: List<Card>, led: SjPlay, t: CardSuit, lr: Int): List<Card>? {
        val byRank = inSuit.groupBy { it.rank }
        return when (led.type) {
            SjType.PAIR -> {
                byRank.filterValues { it.size >= 2 }.minByOrNull { it.key }?.value?.take(2)
                    ?: pickN(inSuit.sortedBy { it.rank }, 2)
            }
            SjType.TRACTOR -> {
                findTractor(inSuit, CtxLike(t, lr), trump = false)
                    ?: pickN(inSuit.sortedBy { it.rank }, led.count)
            }
            else -> listOf(inSuit.minBy { it.rank })
        }
    }

    private fun myTeam() = gdTeamOf(seat)

    private fun currentWinnerTeam(ctx: Ctx): GdTeam {
        val last = ctx.trickPlays.lastOrNull() ?: return myTeam()
        return gdTeamOf(last.first)
    }

    companion object {
        /** 在一组同花色/主牌中找拖拉机 */
        fun findTractor(cards: List<Card>, ctx: Ctx, trump: Boolean): List<Card>? {
            return findTractorRaw(cards, ctx.trumpSuit, ctx.levelRank)
        }

        fun findTractor(cards: List<Card>, like: CtxLike, trump: Boolean): List<Card>? {
            return findTractorRaw(cards, like.trumpSuit, like.levelRank)
        }

        private fun findTractorRaw(cards: List<Card>, t: CardSuit, lr: Int): List<Card>? {
            val isTr = { c: Card -> SjRules.isTrump(c, t, lr) }
            val allTrump = cards.all(isTr)
            if (!allTrump && cards.any(isTr)) return null
            val byKey = if (allTrump) cards.groupBy { SjRules.trumpIndex(it, t, lr) }
            else cards.groupBy { it.rank }
            val pairRanks = byKey.filterValues { it.size >= 2 }.keys.sorted()
            for (i in 0..pairRanks.size - 2) {
                if (pairRanks[i + 1] == pairRanks[i] + 1) {
                    return (byKey[pairRanks[i]]!!.take(2) + byKey[pairRanks[i + 1]]!!.take(2))
                        .sortedBy { if (allTrump) SjRules.trumpIndex(it, t, lr) else it.rank }
                }
            }
            return null
        }
    }
}

/** 轻量主牌上下文（供 findTractor 复用） */
data class CtxLike(val trumpSuit: CardSuit, val levelRank: Int)
