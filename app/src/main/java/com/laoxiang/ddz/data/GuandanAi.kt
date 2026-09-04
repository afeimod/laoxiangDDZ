package com.laoxiang.ddz.data

/**
 * 掼蛋 AI：三档难度（基础策略 + 队友配合 + 炸弹时机）
 * · 领出：出最长弱组合的小端；对家快出完时喂小牌接风
 * · 跟牌：同型最便宜压；对家已是最大且对手都过→让牌
 * · 炸弹：留给对方头游冲刺/本圈有级牌大牌时；天王炸最后底牌
 */
class GuandanAi(private val seat: Int, private val level: AiLevel, private val levelRank: Int) {

    data class Ctx(
        val hand: List<Card>,
        val lastMove: GdMove?,
        val lastMoveSeat: Int,
        val handCounts: Map<Int, Int>,
        val finishedSeats: List<Int>
    )

    private fun partner() = gdPartner(seat)

    fun chooseMove(ctx: Ctx): List<Card>? {
        val hand = ctx.hand.sortedByDescending { GdRules.power(it.rank, levelRank) }
        return if (ctx.lastMove == null) lead(hand, ctx) else follow(hand, ctx)
    }

    // ------------------------------------------------ 领出

    private fun lead(hand: List<Card>, ctx: Ctx): List<Card> {
        val partnerCount = ctx.handCounts[partner()] ?: 27
        val oppMin = ctx.handCounts.filterKeys { gdTeamOf(it) != gdTeamOf(seat) }.values.minOrNull() ?: 27
        // 对家只剩 1-2 张：喂最小的单/对
        if (partnerCount <= 2 && oppMin > 2) {
            val singles = hand.groupBy { it.rank }.filterValues { it.size == 1 }
            if (partnerCount == 1 && singles.isNotEmpty()) {
                return listOf(singles.values.minBy { GdRules.power(it[0].rank, levelRank) }[0])
            }
            if (partnerCount == 2) {
                val pairs = hand.groupBy { it.rank }.filterValues { it.size == 2 }
                if (pairs.isNotEmpty()) {
                    return pairs.values.minBy { GdRules.power(it[0].rank, levelRank) }
                }
            }
        }
        // 自己剩一手能出完 → 直接走
        GdMove.of(hand, levelRank)?.let { if (it.type != GdType.BOMB || hand.size == it.cards.size) return hand }
        // 拆最小端：优先三带二/木板/钢板消化小牌，否则最小单张
        val groups = ArrayList<List<Card>>()
        val byRank = hand.groupBy { it.rank }
        // 钢板
        byRank.filterValues { it.size >= 3 }.keys.sorted().windowed(2).forEach { (a, b) ->
            if (b == a + 1) groups += byRank[a]!!.take(3) + byRank[b]!!.take(3)
        }
        // 三带二
        byRank.filterValues { it.size >= 3 }.forEach { (r3, trio) ->
            byRank.filterValues { it.size == 2 }.minByOrNull { GdRules.power(it.key, levelRank) }?.let { pair ->
                groups += trio.take(3) + pair.value
            }
        }
        // 顺子
        (2..10).forEach { s ->
            if ((s..s + 4).all { it in byRank }) {
                groups += (s..s + 4).map { r -> byRank[r]!!.first() }
            }
        }
        (2..5).forEach { s ->
            if (listOf(s, s + 1, s + 2, s + 3, 14).all { it in byRank }) {
                groups += listOf(s, s + 1, s + 2, s + 3, 14).map { r -> byRank[r]!!.first() }
            }
        }
        if (groups.isNotEmpty() && level != AiLevel.EASY) {
            return groups.minBy { g -> g.sumOf { it.rank } }
        }
        val singles = hand.groupBy { it.rank }.filterValues { it.size == 1 }
        if (singles.isNotEmpty()) {
            return listOf(singles.values.minBy { GdRules.power(it[0].rank, levelRank) }[0])
        }
        val pairs = hand.groupBy { it.rank }.filterValues { it.size >= 2 }
        return pairs.values.minBy { GdRules.power(it[0].rank, levelRank) }.take(2)
    }

    // ------------------------------------------------ 跟牌

    private fun follow(hand: List<Card>, ctx: Ctx): List<Card>? {
        val last = ctx.lastMove!!
        val nonBomb = genGdBeats(hand, last, levelRank, withBomb = false)
        val bombs = genGdBeats(hand, last, levelRank, withBomb = true).filter {
            it !in nonBomb && (GdMove.of(it, levelRank)!!.type != last.type ||
                    GdMove.of(it, levelRank)!!.length != last.length)
        }
        val partnerIsMaster = ctx.lastMoveSeat == partner()
        val oppAlive = ctx.handCounts.filterKeys { gdTeamOf(it) != gdTeamOf(seat) && (ctx.handCounts[it] ?: 0) > 0 }
        val oppMin = oppAlive.values.minOrNull() ?: 0
        // 对家出的且已无人能压（简化：对家出大牌）→ 让牌
        if (partnerIsMaster && nonBomb.isEmpty()) return null
        if (partnerIsMaster && last.mainPower >= 15 && level != AiLevel.EASY) return null
        // 普通能压 → 最便宜
        if (nonBomb.isNotEmpty()) {
            // 自己只差一手且能直接出完 → 出
            nonBomb.firstOrNull { it.size == hand.size }?.let { return it }
            return nonBomb.minBy { it.sumOf { c -> (GdRules.power(c.rank, levelRank) - 2) * 2 } }
        }
        // 需要炸弹吗：对手快冲刺 或 桌面是对手的牌 → 拦
        val shouldBomb = when {
            level == AiLevel.EASY -> false
            !partnerIsMaster && oppMin <= 6 -> true
            !partnerIsMaster && last.mainPower >= 14 -> true
            else -> false
        }
        if (shouldBomb && bombs.isNotEmpty()) {
            return bombs.minBy { it.size * 10 + it.sumOf { c -> GdRules.power(c.rank, levelRank) } }
        }
        return null
    }

    companion object {
        /** 枚举能压过 [last] 的组合；withBomb=false 只找同型普通牌 */
        fun genGdBeats(
            hand: List<Card>,
            last: GdMove,
            levelRank: Int,
            withBomb: Boolean
        ): List<List<Card>> {
            val out = ArrayList<List<Card>>()
            val byRank = hand.filter { it.suit != CardSuit.JOKER }.groupBy { it.rank }
            val jokers = hand.filter { it.suit == CardSuit.JOKER }
            val n = last.cards.size

            // 普通同型
            fun addSameType() {
                when (last.type) {
                    GdType.SINGLE -> byRank.forEach { (r, cs) ->
                        if (GdRules.power(r, levelRank) > last.mainPower) cs.forEach { out += listOf(it) }
                    }
                    GdType.PAIR -> byRank.forEach { (r, cs) ->
                        if (cs.size >= 2 && GdRules.power(r, levelRank) > last.mainPower) out += cs.take(2)
                    }
                    GdType.TRIO -> byRank.forEach { (r, cs) ->
                        if (cs.size >= 3 && GdRules.power(r, levelRank) > last.mainPower) out += cs.take(3)
                    }
                    GdType.TRIO_PAIR -> byRank.forEach { (r, cs) ->
                        if (cs.size >= 3 && GdRules.power(r, levelRank) > last.mainPower) {
                            byRank.filterKeys { it != r }.filterValues { it.size >= 2 }
                                .minByOrNull { GdRules.power(it.key, levelRank) }
                                ?.let { out += cs.take(3) + it.value.take(2) }
                        }
                    }
                    GdType.STRAIGHT -> {
                        (2..10).forEach { s ->
                            if ((s..s + 4).all { it in byRank } && s + 4 > last.mainPower) {
                                out += (s..s + 4).map { r -> byRank[r]!!.first() }
                            }
                        }
                        // A2345（端点 5）
                        if (listOf(2, 3, 4, 5, 14).all { it in byRank } && 5 > last.mainPower) {
                            out += listOf(2, 3, 4, 5, 14).map { r -> byRank[r]!!.first() }
                        }
                    }
                    GdType.BANZI -> (2..12).forEach { s ->
                        if ((s..s + 2).all { r -> (byRank[r]?.size ?: 0) >= 2 } && s + 2 > last.mainPower) {
                            out += (s..s + 2).flatMap { r -> byRank[r]!!.take(2) }
                        }
                    }
                    GdType.GANGBAN -> (2..13).forEach { s ->
                        if ((s..s + 1).all { r -> (byRank[r]?.size ?: 0) >= 3 } && s + 1 > last.mainPower) {
                            out += (s..s + 1).flatMap { r -> byRank[r]!!.take(3) }
                        }
                    }
                    else -> {}
                }
            }
            if (last.type.isBombLike) {
                // 只能找炸弹/同花顺/天王炸压炸弹
            } else if (!withBomb || true) {
                addSameType()
            }
            // 炸弹
            if (withBomb || last.type.isBombLike) {
                byRank.forEach { (r, cs) ->
                    if (cs.size >= 4) {
                        val mv = GdMove.of(cs.take(cs.size), levelRank)
                        // 出全部同点（最多8）
                        if (mv != null && mv.beats(last, levelRank)) out += cs
                        else if (cs.size > 4) {
                            // 尝试较少张数
                            for (k in 4..cs.size) {
                                val m2 = GdMove.of(cs.take(k), levelRank)
                                if (m2 != null && m2.beats(last, levelRank)) { out += cs.take(k); break }
                            }
                        }
                    }
                }
                // 同花顺
                genStraightFlushes(hand, levelRank).forEach { sf ->
                    if (GdMove.of(sf, levelRank)!!.beats(last, levelRank)) out += sf
                }
                // 天王炸
                if (jokers.size == 4) out += jokers
            }
            return out.distinctBy { it.map { c -> c.id } }
        }

        /** 枚举同花顺（5 张同花连续） */
        fun genStraightFlushes(hand: List<Card>, levelRank: Int): List<List<Card>> {
            val out = ArrayList<List<Card>>()
            hand.filter { it.suit != CardSuit.JOKER }
                .groupBy { it.suit }
                .forEach { (_, cs) ->
                    val byRank = cs.groupBy { it.rank }
                    (2..10).forEach { s ->
                        if ((s..s + 4).all { it in byRank }) {
                            out += (s..s + 4).map { r -> byRank[r]!!.first() }
                        }
                    }
                    if (listOf(2, 3, 4, 5, 14).all { it in byRank }) {
                        out += listOf(2, 3, 4, 5, 14).map { r -> byRank[r]!!.first() }
                    }
                }
            return out
        }
    }
}
