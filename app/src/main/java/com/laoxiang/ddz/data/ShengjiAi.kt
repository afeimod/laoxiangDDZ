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
            if (combo.size == n) return combo
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

    /** 领出花色内凑出合法的 n 张（v20 结构化跟牌：对跟对、拖拉机跟拖拉机/全部对子） */
    private fun cheapestComboInSuit(inSuit: List<Card>, led: SjPlay, t: CardSuit, lr: Int): List<Card> =
        followSuggestion(inSuit, led, t, lr)

    private fun myTeam() = gdTeamOf(seat)

    private fun currentWinnerTeam(ctx: Ctx): GdTeam {
        val last = ctx.trickPlays.lastOrNull() ?: return myTeam()
        return gdTeamOf(last.first)
    }

    companion object {
        /**
         * v20 结构化跟牌建议（AI 与玩家「提示」共用；引擎 followStructureOk 的生成侧）：
         * · 领出对子（或甩牌含对 unit==2）：有对跟最小对（n>2 时补最小散牌）；无对跟散单
         * · 领出拖拉机（或甩牌含拖拉机 unit>=3）：有同长（k 对）拖拉机跟最低端 k 对；
         *   无拖拉机跟 min(k, 总对数) 个最小对 + 散单补足
         * · 单张 / 无结构甩牌：跟 n 张最小牌
         * 返回恰 n 张（调用方保证 pool.size >= n）
         */
        fun followSuggestion(pool: List<Card>, led: SjPlay, t: CardSuit, lr: Int): List<Card> {
            val n = led.count
            val shape: SjType? = when (led.type) {
                SjType.PAIR -> SjType.PAIR
                SjType.TRACTOR -> SjType.TRACTOR
                SjType.THROW -> when {
                    led.unit >= 3 -> SjType.TRACTOR
                    led.unit == 2 -> SjType.PAIR
                    else -> null
                }
                SjType.SINGLE -> null
            }
            val sorted = pool.sortedWith(
                compareBy { c: Card -> if (SjRules.isTrump(c, t, lr)) SjRules.trumpIndex(c, t, lr) else c.rank }
            )
            if (shape == null) return sorted.take(n)

            val byKey = pool.groupBy { sjComboKey(it, t, lr) }
            val pairEntries = byKey.filterValues { it.size >= 2 }
                .entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            val isTrumpLed = led.suit == CardSuit.JOKER

            if (shape == SjType.PAIR) {
                val best = pairEntries.firstOrNull()?.value
                if (best == null) return sorted.take(n)          // 无对：散单
                val pair = best.take(2)
                if (n == 2) return pair
                // 甩牌含对（n>2）：先对，再补最小散牌
                val rest = sorted.filter { c -> pair.none { it.id == c.id } }
                return pair + rest.take(n - 2)
            }
            // TRACTOR：k 对
            val k = n / 2
            // 1) 有同长拖拉机：跟最低端 k 对
            val runKeys = ArrayList<Pair<Int, Int>>()
            for (e in pairEntries) {
                if (runKeys.isNotEmpty() && !sjAdjacent(runKeys.last(), e.key, isTrumpLed)) runKeys.clear()
                runKeys.add(e.key)
                if (runKeys.size == k) {
                    return runKeys.flatMap { key -> byKey[key]!!.take(2) }
                }
            }
            // 2) 无同长拖拉机：min(k, 总对数) 个最小对优先，散单补足
            val totalPairs = pairEntries.sumOf { it.value.size / 2 }
            val picks = pairEntries.take(minOf(k, totalPairs))
                .flatMap { e -> e.value.chunked(2).take(e.value.size / 2) }
                .flatten()
            if (picks.size >= n) return picks.take(n)
            val rest = sorted.filter { c -> picks.none { it.id == c.id } }
            return picks + rest.take(n - picks.size)
        }

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
            // v19 花色感知分组：不同花色级牌（2♠2♦）不再误判成对（修「四个2」假拖拉机）
            val groups = cards.groupBy { sjComboKey(it, t, lr) }
            val keys = groups.filterValues { it.size >= 2 }.keys.sortedWith(compareBy({ it.first }, { it.second }))
            var best: List<Pair<Int, Int>>? = null
            var run = ArrayList<Pair<Int, Int>>()
            for (k in keys) {
                if (run.isNotEmpty() && !sjAdjacent(run.last(), k, allTrump)) run = ArrayList()
                run.add(k)
                val b = best
                if (run.size >= 2 && (b == null || run.size > b.size ||
                            (run.size == b.size && run.last().first > b.last().first))
                ) best = ArrayList(run)
            }
            val runKeys = best ?: return null
            return runKeys.flatMap { groups[it]!!.take(2) }
                .sortedByDescending { if (allTrump) SjRules.trumpIndex(it, t, lr) else it.rank }
        }
    }
}

/** 轻量主牌上下文（供 findTractor 复用） */
data class CtxLike(val trumpSuit: CardSuit, val levelRank: Int)
