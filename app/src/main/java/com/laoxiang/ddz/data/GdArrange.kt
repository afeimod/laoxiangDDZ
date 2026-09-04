package com.laoxiang.ddz.data

/**
 * 掼蛋智能理牌（纯 Kotlin，可单测）——对标主流掼蛋「一键理」：
 * 把手牌自动切成带标签的组合列：天王炸 / 炸弹 / 同花顺 / 钢板(二连三) /
 * 木板(三连对) / 顺子 / 三张 / 对子 / 单张，牌力强的组排前面。
 *
 * 贪心次序（先拆价值低的保价值高的）：
 *  1. 天王炸（4 王）        2. 同花顺（恰 5 张同花，含 A2345）
 *  3. 炸弹（≥4 同点，多副牌同点最多 8 张，拆成 4 张列展示时并列）
 *  4. 钢板 → 5. 木板 → 6. 顺子（循环提取）→ 7. 剩余按 三张/对子/单张 归组
 */
object GdArrange {

    /** 一个理牌列：label 为列底标签（空串不显示），cards 已按牌力降序 */
    data class GdCombo(
        val label: String,
        val cards: List<Card>,
        /** 展示排序权重：越大越靠前 */
        val order: Int,
        /** 是否成牌型（列间加大间隙） */
        val major: Boolean
    )

    private fun pw(c: Card, lr: Int) = GdRules.power(c.rank, lr)

    private fun sortDesc(cs: List<Card>, lr: Int) =
        cs.sortedWith(compareByDescending<Card> { pw(it, lr) }.thenByDescending { SjRules.suitRank(it.suit) })

    fun arrange(hand: List<Card>, levelRank: Int): List<GdCombo> {
        if (hand.isEmpty()) return emptyList()
        val pool = hand.toMutableList()
        val out = ArrayList<GdCombo>()

        fun takeWhere(pred: (Card) -> Boolean, count: Int): List<Card> {
            val got = ArrayList<Card>(count)
            val it = pool.iterator()
            while (it.hasNext() && got.size < count) {
                val c = it.next()
                if (pred(c)) { got += c; it.remove() }
            }
            return got
        }
        fun remainingRanks(): Map<Int, Int> =
            pool.filter { it.suit != CardSuit.JOKER }.groupBy { it.rank }.mapValues { it.value.size }

        // ---- 1. 天王炸
        val jokers = pool.filter { it.suit == CardSuit.JOKER }
        if (jokers.size == 4) {
            out += GdCombo("天王炸", sortDesc(takeWhere({ it.suit == CardSuit.JOKER }, 4), levelRank), 9000, true)
        }

        // ---- 2. 同花顺（循环提取）
        while (true) {
            val sf = findStraightFlush(pool, levelRank) ?: break
            sf.forEach { c -> pool.removeIf { it.id == c.id } }
            out += GdCombo("同花顺", sortDesc(sf, levelRank), 7900, true)
        }

        // ---- 3. 炸弹（张数多者优先，同级先出大点数；展示序高于同花顺；3 同点 + 逢人配 = 炸弹）
        while (true) {
            var counts = remainingRanks().filterValues { it >= 4 }
            val wild = pool.firstOrNull { GdMove.isFengrenpei(it, levelRank) }
            if (counts.isEmpty() && wild != null) {
                // 逢人配辅助：恰 3 张同点 + 红桃级牌 → 成炸
                val triple = remainingRanks().filterValues { it == 3 }.keys.maxOrNull()
                if (triple != null) {
                    counts = mapOf(triple to 4)
                    val cs = takeWhere({ it.rank == triple }, 3) + takeWhere(
                        { GdMove.isFengrenpei(it, levelRank) }, 1
                    )
                    out += GdCombo("炸弹", sortDesc(cs, levelRank), 8000 + 4 * 30 + triple, true)
                    continue
                }
            }
            if (counts.isEmpty()) break
            val rank = counts.entries.maxWithOrNull(
                compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }
            )!!.key
            val cs = takeWhere({ it.rank == rank }, counts.getValue(rank))
            out += GdCombo("炸弹", sortDesc(cs, levelRank), 8000 + counts.getValue(rank) * 30 + rank, true)
        }

        // ---- 4. 钢板（二连三）→ 5. 木板（三连对）→ 6. 顺子，循环
        while (true) {
            var progressed = false
            // 钢板
            run {
                val trios = remainingRanks().filterValues { it >= 3 }.keys.sorted()
                for (i in 1 until trios.size) {
                    if (trios[i] == trios[i - 1] + 1 && trios[i] <= 14 && trios[i - 1] >= 2) {
                        val a = takeWhere({ it.rank == trios[i - 1] }, 3)
                        val b = takeWhere({ it.rank == trios[i] }, 3)
                        out += GdCombo("钢板", sortDesc(a + b, levelRank), 6000 + trios[i], true)
                        progressed = true
                        return@run
                    }
                }
            }
            if (progressed) continue
            // 木板（三连对）
            run {
                val pairs = remainingRanks().filterValues { it >= 2 }.keys.sorted()
                for (i in 2 until pairs.size) {
                    if (pairs[i] == pairs[i - 1] + 1 && pairs[i - 1] == pairs[i - 2] + 1 &&
                        pairs[i] <= 14 && pairs[i - 2] >= 2
                    ) {
                        val cs = (pairs[i - 2]..pairs[i]).flatMap { r -> takeWhere({ it.rank == r }, 2) }
                        out += GdCombo("木板", sortDesc(cs, levelRank), 5000 + pairs[i], true)
                        progressed = true
                        return@run
                    }
                }
            }
            if (progressed) continue
            // 顺子
            val st = findStraight(pool, levelRank)
            if (st != null) {
                st.forEach { c -> pool.removeIf { it.id == c.id } }
                out += GdCombo("顺子", sortDesc(st, levelRank), 4000 + (st.maxOf { it.rank }), true)
                continue
            }
            break
        }

        // ---- 7. 剩余：三张 / 对子 / 单张（级牌角标由 UI 层加）
        val rest = remainingRanks()
        rest.keys.sortedDescending().forEach { rank ->
            val cnt = rest.getValue(rank)
            val cs = takeWhere({ it.rank == rank && it.suit != CardSuit.JOKER }, cnt)
            when {
                cnt >= 3 -> out += GdCombo("三张", sortDesc(cs, levelRank), 3000 + rank, false)
                cnt == 2 -> out += GdCombo("", sortDesc(cs, levelRank), 2000 + rank, false)
                else -> out += GdCombo("", sortDesc(cs, levelRank), 1000 + rank, false)
            }
        }
        // 剩余的王（不足 4 张不成天王炸）
        pool.groupBy { it.rank }.toSortedMap(compareByDescending { it })
            .forEach { (_, cs) ->
                out += GdCombo("", sortDesc(cs, levelRank), 1500 + cs[0].rank, false)
            }
        out.sortByDescending { it.order }
        return out
    }

    /** 在池中找同花顺（恰 5 张同花色连续，含 A2345），找到即取一列 */
    private fun findStraightFlush(pool: List<Card>, lr: Int): List<Card>? {
        val bySuit = pool.filter { it.suit != CardSuit.JOKER }.groupBy { it.suit }
        for ((_, cs) in bySuit) {
            val byRank = cs.groupBy { it.rank }
            var found = straightIn(byRank.keys.sorted())
            if (found == null) {
                // A2345
                if (listOf(2, 3, 4, 5, 14).all { byRank.containsKey(it) }) found = listOf(2, 3, 4, 5, 14)
            }
            if (found != null) return found.map { r -> byRank.getValue(r).first() }
        }
        return null
    }

    /** 在池中找普通顺子（恰 5 张点数连续，花色不限，含 A2345） */
    private fun findStraight(pool: List<Card>, lr: Int): List<Card>? {
        val byRank = pool.filter { it.suit != CardSuit.JOKER }.groupBy { it.rank }
        val run = straightIn(byRank.keys.sorted())
        if (run != null) return run.map { r -> byRank.getValue(r).first() }
        if (listOf(2, 3, 4, 5, 14).all { byRank.containsKey(it) }) {
            return listOf(2, 3, 4, 5, 14).map { r -> byRank.getValue(r).first() }
        }
        return null
    }

    private fun straightIn(sortedRanks: List<Int>): List<Int>? {
        if (sortedRanks.size < 5) return null
        var run = listOf(sortedRanks[0])
        for (i in 1 until sortedRanks.size) {
            run = if (sortedRanks[i] == sortedRanks[i - 1] + 1) run + sortedRanks[i] else listOf(sortedRanks[i])
            if (run.size >= 5) return run.takeLast(5)
        }
        return null
    }
}
