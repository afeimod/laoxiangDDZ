package com.laoxiang.ddz.data

/**
 * 锄大地 AI：三档难度
 * · EASY：最便宜能压就压（拆牌无所谓），领出最小单/对
 * · MEDIUM：不轻易拆对/拆三，五张型留到后期；对手快出完时顶大牌
 * · HARD：MEDIUM 基础上记牌（外界还存在哪些大牌）、控制放走、卡对手报牌
 */
class BigTwoAi(private val seat: Int, private val level: AiLevel) {

    data class Ctx(
        val hand: List<Card>,
        val lastMove: BtMove?,
        val lastMoveSeat: Int,
        val handCounts: Map<Int, Int>,
        val playedCards: List<Card>
    )

    fun chooseMove(ctx: Ctx): List<Card>? {
        val hand = ctx.hand.sortedWith(compareByDescending<Card> { it.rank }.thenByDescending { suitPower(it.suit) })
        return if (ctx.lastMove == null) chooseLead(hand, ctx) else chooseFollow(hand, ctx)
    }

    // ------------------------------------------------ 领出

    private fun chooseLead(hand: List<Card>, ctx: Ctx): List<Card>? {
        val minOpp = ctx.handCounts.filterKeys { it != seat }.values.minOrNull() ?: 13
        // 对手只剩 1 张：绝不领小单张
        if (minOpp <= 1) {
            groups(hand).maxByOrNull { it.cards.size }?.let { return it.cards }
            val singles = hand.take(1)
            if (singles.first().rank >= 13) return singles
        }
        // 有五张型且手数多时先甩五张（减手数）
        if (hand.size >= 8) {
            val bestFive = fiveCombos(hand).maxByOrNull { combo -> BtMove.of(combo)?.type?.catRank ?: 0 }
            if (bestFive != null && level != AiLevel.EASY) return bestFive
        }
        val gs = groups(hand)
        // 优先出最长的组（先消化顺子/多张组），否则出最小单
        val nonSingle = gs.filter { it.cards.size >= 2 }
        if (nonSingle.isNotEmpty() && level != AiLevel.EASY) {
            return nonSingle.minByOrNull { it.cards.minOf { c -> c.rank } }!!.cards
        }
        return listOf(hand.last())    // 最小的单张
    }

    // ------------------------------------------------ 跟牌

    private fun chooseFollow(hand: List<Card>, ctx: Ctx): List<Card>? {
        val last = ctx.lastMove!!
        val options = genBtBeats(hand, last)
        if (options.isEmpty()) return null
        val leaderIsOpp = ctx.lastMoveSeat != partnerSeat()
        val minOpp = ctx.handCounts.filterKeys { it != seat }.values.minOrNull() ?: 13

        // 只剩能一次出完的牌 → 直接出
        options.firstOrNull { it.size == hand.size }?.let { return it }

        // EASY：出最便宜的第一手
        if (level == AiLevel.EASY) {
            return options.minByOrNull { comboCost(it, hand) }
        }

        // 对手快出完（剩 ≤2）：能顶就顶大牌，拦不住也用最小
        if (leaderIsOpp && minOpp <= 2) {
            return options.maxByOrNull { opt -> opt.sumOf { pc -> pc.rank } }
        }

        // MEDIUM/HARD：最便宜、不拆结构；HARD 保留大牌防守
        val sorted = options.sortedBy { comboCost(it, hand) }
        for (opt in sorted) {
            if (level == AiLevel.HARD && isKeyCard(opt, hand, minOpp)) continue
            return opt
        }
        return sorted.first()
    }

    /** 关键牌保护：大牌（2/A）不轻易跟小单对时浪费 */
    private fun isKeyCard(opt: List<Card>, hand: List<Card>, minOpp: Int): Boolean {
        if (minOpp <= 3) return false
        val top = opt.maxBy { it.rank }
        return top.rank >= 14 && opt.size <= 2 && hand.size > 5
    }

    private fun partnerSeat() = (seat + 2) % 4

    /** 组合代价：越小越优先（用出去不心疼） */
    private fun comboCost(opt: List<Card>, hand: List<Card>): Int {
        var cost = 0
        val byRank = hand.groupBy { it.rank }
        for (c in opt) {
            cost += (c.rank - 3) * 2
            val inHand = byRank[c.rank]?.size ?: 0
            var inOpt = 0
            for (pc in opt) if (pc.rank == c.rank) inOpt++
            if (inHand >= 2 && inOpt == 1) cost += 10          // 拆对
            if (inHand == 3 && inOpt in 1..2) cost += 14       // 拆三
        }
        return cost
    }

    // ------------------------------------------------ 组合枚举

    private data class Group(val cards: List<Card>)

    /** 手牌全部基础组：单/对/三 + 五张型 */
    private fun groups(hand: List<Card>): List<Group> {
        val byRank = hand.groupBy { it.rank }
        val gs = ArrayList<Group>()
        byRank.values.forEach { g ->
            gs += Group(listOf(g.maxBy { suitPower(it.suit) }))
            if (g.size >= 2) gs += Group(g.sortedByDescending { suitPower(it.suit) }.take(2))
            if (g.size >= 3) gs += Group(g.sortedByDescending { suitPower(it.suit) }.take(3))
        }
        fiveCombos(hand).forEach { gs += Group(it) }
        return gs
    }

    /** 枚举所有五张型 */
    fun fiveCombos(hand: List<Card>): List<List<Card>> {
        val out = ArrayList<List<Card>>()
        if (hand.size < 5) return out
        // 同花
        hand.groupBy { it.suit }.values.forEach { suitCards ->
            if (suitCards.size >= 5) {
                // 同花（取最大5张）与同花顺
                val sorted = suitCards.sortedByDescending { it.rank }
                out += sorted.take(5)
                runWindows(suitCards).forEach { out += it }
            }
        }
        // 普通顺子
        runWindows(hand).forEach { run ->
            if (run.all { c -> hand.any { it.suit == c.suit } }) out += run
        }
        // 葫芦 / 铁支
        val byRank = hand.groupBy { it.rank }
        byRank.filterValues { it.size >= 3 }.forEach { (r3, trio) ->
            byRank.filterValues { it.size >= 2 }.forEach { (r2, pair) ->
                if (r3 != r2) out += trio.take(3) + pair.take(2)
            }
        }
        byRank.filterValues { it.size == 4 }.forEach { (_, quad) ->
            val kicker = hand.filter { it.rank != quad[0].rank }.maxByOrNull { it.rank }
            if (kicker != null) out += quad + kicker
        }
        return out.distinctBy { it.map { c -> c.id } }
    }

    /** 从手牌中枚举连续 5 张窗口（含 23456） */
    private fun runWindows(cards: List<Card>): List<List<Card>> {
        val out = ArrayList<List<Card>>()
        val byRank = cards.groupBy { it.rank }
        val ranks = byRank.keys.sorted()
        // 普通窗口 3..14
        for (start in 3..10) {
            if ((start..start + 4).all { it in byRank }) {
                out += (start..start + 4).map { r -> byRank[r]!!.maxBy { suitPower(it.suit) } }
            }
        }
        // 23456（"2"=rank15）
        if (listOf(3, 4, 5, 6, 15).all { it in byRank }) {
            out += listOf(3, 4, 5, 6, 15).map { r -> byRank[r]!!.maxBy { suitPower(it.suit) } }
        }
        return out
    }

    companion object {
        /** 枚举能压过 [last] 的所有组合（单/对/三/五张型） */
        fun genBtBeats(hand: List<Card>, last: BtMove): List<List<Card>> {
            val out = ArrayList<List<Card>>()
            val byRank = hand.groupBy { it.rank }
            val n = last.cards.size
            when (n) {
                1 -> {
                    byRank.forEach { (r, cs) ->
                        cs.filter { r > last.mainRank || (r == last.mainRank && suitPower(it.suit) > last.mainSuit) }
                            .forEach { out += listOf(it) }
                    }
                }
                2 -> {
                    byRank.forEach { (r, cs) ->
                        if (r > last.mainRank && cs.size >= 2) {
                            out += cs.sortedByDescending { suitPower(it.suit) }.take(2)
                        }
                    }
                }
                3 -> {
                    byRank.forEach { (r, cs) ->
                        if (r > last.mainRank && cs.size >= 3) {
                            out += cs.sortedByDescending { suitPower(it.suit) }.take(3)
                        }
                    }
                }
                5 -> {
                    val ai = BigTwoAi(0, AiLevel.MEDIUM)
                    ai.fiveCombos(hand).forEach { combo ->
                        val mv = BtMove.of(combo) ?: return@forEach
                        if (mv.beats(last)) out += combo
                    }
                }
            }
            return out.distinctBy { it.map { c -> c.id } }
        }
    }
}
