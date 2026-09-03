package com.laoxiang.ddz.data

/**
 * 招法生成器：AI 决策与玩家「提示」共用。
 *
 * genBeats(hand, last)  —— 找出所有能压过 last 的候选（含炸弹/王炸），按代价升序
 * genLeads(hand)        —— 领出候选（按代价升序：小牌优先、少拆结构优先、多走牌优先）
 *
 * 代价设计：rank 为主；2/王/炸弹/王炸/破坏炸弹结构 均加权惩罚。
 */
object MoveGen {

    // ------------------------------------------------ 压牌候选

    fun genBeats(hand: List<Card>, last: Move?): List<Move> {
        if (last == null) return genLeads(hand)
        val out = ArrayList<Move>()
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }

        when (last.type) {
            MoveType.ROCKET -> return emptyList() // 无解
            MoveType.SINGLE -> {
                for (r in (last.mainRank + 1)..17) {
                    if ((counts[r] ?: 0) >= 1) {
                        Move.of(pick(hand, r, 1))?.let { out += it }
                    }
                }
            }
            MoveType.PAIR -> {
                for (r in (last.mainRank + 1)..15) {
                    if ((counts[r] ?: 0) >= 2) {
                        Move.of(pick(hand, r, 2))?.let { out += it }
                    }
                }
            }
            MoveType.TRIO, MoveType.TRIO_SINGLE, MoveType.TRIO_PAIR -> {
                for (r in (last.mainRank + 1)..15) {
                    if ((counts[r] ?: 0) >= 3) {
                        val body = pick(hand, r, 3)
                        if (last.type == MoveType.TRIO) {
                            out += Move(body, MoveType.TRIO, r, 1)
                            continue
                        }
                        val rest = hand.filter { it.rank != r }
                        val wingN = if (last.type == MoveType.TRIO_PAIR) 2 else 1
                        val wings = pickWings(rest, wingN, wingPairs = last.type == MoveType.TRIO_PAIR)
                        if (wings != null && wings.isNotEmpty()) {
                            Move.of(body + wings)?.let { out += it }
                        }
                    }
                }
            }
            MoveType.STRAIGHT -> {
                val len = last.length
                for (start in (last.mainRank - len + 2)..(14 - len + 1)) {
                    val window = (start until start + len).toList()
                    if (window.all { (counts[it] ?: 0) >= 1 }) {
                        Move.of(window.flatMap { r -> pick(hand, r, 1) })?.let { out += it }
                    }
                }
            }
            MoveType.PAIR_STRAIGHT -> {
                val len = last.length
                for (start in (last.mainRank - len + 2)..(14 - len + 1)) {
                    val window = (start until start + len).toList()
                    if (window.all { (counts[it] ?: 0) >= 2 }) {
                        Move.of(window.flatMap { r -> pick(hand, r, 2) })?.let { out += it }
                    }
                }
            }
            MoveType.PLANE, MoveType.PLANE_SINGLE, MoveType.PLANE_PAIR -> {
                val k = last.length
                val needWing = when (last.type) {
                    MoveType.PLANE_SINGLE -> 1
                    MoveType.PLANE_PAIR -> 2
                    else -> 0
                }
                for (start in (last.mainRank - k + 2)..(14 - k + 1)) {
                    val window = (start until start + k).toList()
                    if (window.all { (counts[it] ?: 0) >= 3 }) {
                        val body = window.flatMap { r -> pick(hand, r, 3) }
                        if (needWing == 0) {
                            Move.of(body)?.let { out += it }
                        } else {
                            val rest = hand.filter { it.rank !in window }
                            val wings = pickWings(rest, k * needWing, wingPairs = needWing == 2)
                            if (wings != null) Move.of(body + wings)?.let { out += it }
                        }
                    }
                }
            }
            MoveType.FOUR_TWO_SINGLE, MoveType.FOUR_TWO_PAIR -> {
                val pairsWing = last.type == MoveType.FOUR_TWO_PAIR
                for (r in (last.mainRank + 1)..15) {
                    if ((counts[r] ?: 0) == 4) {
                        val rest = hand.filter { it.rank != r }
                        val wings = pickWings(rest, if (pairsWing) 4 else 2, wingPairs = pairsWing)
                        if (wings != null) Move.of(pick(hand, r, 4) + wings)?.let { out += it }
                    }
                }
            }
            MoveType.BOMB -> {
                for (r in (last.mainRank + 1)..15) {
                    if ((counts[r] ?: 0) == 4) {
                        out += Move(pick(hand, r, 4), MoveType.BOMB, r, 1)
                    }
                }
                rocket(hand)?.let { out += it }
                return out.sortedBy { costOf(it, hand) }
            }
        }

        // 任何非炸牌型都可以被炸弹压
        if (last.type != MoveType.BOMB && last.type != MoveType.ROCKET) {
            for (r in 3..15) {
                if ((counts[r] ?: 0) == 4 && out.none { it.type == MoveType.BOMB && it.mainRank == r }) {
                    out += Move(pick(hand, r, 4), MoveType.BOMB, r, 1)
                }
            }
            rocket(hand)?.let { out += it }
        }

        return out.sortedBy { costOf(it, hand) }
    }

    // ------------------------------------------------ 领出候选（按代价升序）

    fun genLeads(hand: List<Card>): List<Move> {
        if (hand.isEmpty()) return emptyList()
        val out = ArrayList<Move>()
        val combos = HandDecompose.decompose(hand)
        combos.forEach { c -> out += c.move }

        // 三张带牌变体：三带一 / 三带二（多走一张，代价略降）
        val singlesPool = combos.filter { it.move.type == MoveType.SINGLE }.sortedBy { it.move.mainRank }
        val pairsPool = combos.filter { it.move.type == MoveType.PAIR }.sortedBy { it.move.mainRank }
        combos.filter { it.move.type == MoveType.TRIO }.forEach { trio ->
            singlesPool.firstOrNull()?.let { s ->
                Move.of(trio.cards + s.cards)?.let { out += it }
            }
            pairsPool.firstOrNull()?.let { p ->
                Move.of(trio.cards + p.cards)?.let { out += it }
            }
        }
        // 飞机带翅膀变体
        combos.filter { it.move.type == MoveType.PLANE }.forEach { plane ->
            val k = plane.move.length
            val wings = singlesPool.take(k).flatMap { it.cards }
            if (wings.size == k) {
                Move.of(plane.cards + wings)?.let { out += it }
            }
        }

        // 全副炸弹与王炸
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        counts.forEach { (r, c) ->
            if (c == 4 && out.none { it.type == MoveType.BOMB && it.mainRank == r }) {
                out += Move(pick(hand, r, 4), MoveType.BOMB, r, 1)
            }
        }
        rocket(hand)?.let { out += it }

        // 一步走完直接获胜的招最优先
        return out.sortedBy { costOf(it, hand) }
    }

    // ------------------------------------------------ 工具

    private fun rocket(hand: List<Card>): Move? {
        val sj = hand.firstOrNull { it.rank == 16 } ?: return null
        val bj = hand.firstOrNull { it.rank == 17 } ?: return null
        return Move(listOf(sj, bj), MoveType.ROCKET, 17, 1)
    }

    private fun pick(hand: List<Card>, rank: Int, n: Int): List<Card> =
        hand.filter { it.rank == rank }.take(n)

    /**
     * 从 rest 挑翅膀：n 张（单翅每 rank 可贡献多张）或 n/2 对
     * - 不动炸弹（count==4 的 rank）
     * - 单翅优先落单小牌，其次拆小对
     */
    private fun pickWings(rest: List<Card>, n: Int, wingPairs: Boolean): List<Card>? {
        if (n == 0) return emptyList()
        val byRank = rest.groupBy { it.rank }
        val safe = byRank.filterKeys { r -> (byRank[r]?.size ?: 0) < 4 }
        if (wingPairs) {
            val pairs = safe.entries
                .filter { it.value.size >= 2 }
                .sortedBy { it.key }
                .take(n / 2)
            if (pairs.size == n / 2) return pairs.flatMap { it.value.take(2) }
            return null
        }
        // 单翅：优先 count==1 的最小牌，然后 count==2、count==3
        val ordered = safe.entries
            .flatMap { (r, cards) ->
                val pri = when (cards.size) { 1 -> 0; 2 -> 1; else -> 2 }
                cards.map { pri to r to it }
            }
            .sortedWith(compareBy({ it.first.first }, { it.first.second }))
        if (ordered.size < n) return null
        return ordered.take(n).map { it.second }
    }

    /** 出牌代价：小 = 优先 */
    private fun costOf(move: Move, hand: List<Card>): Int {
        var c = move.mainRank * 2
        when (move.type) {
            MoveType.ROCKET -> c += 600
            MoveType.BOMB -> c += 500
            MoveType.PLANE, MoveType.PLANE_SINGLE, MoveType.PLANE_PAIR -> c += 6
            MoveType.FOUR_TWO_SINGLE, MoveType.FOUR_TWO_PAIR -> c += 30
            else -> {}
        }
        if (move.mainRank >= 15) c += 40
        // 拆炸弹惩罚：用了炸弹 rank 的牌但不是炸弹
        if (move.type != MoveType.BOMB && move.type != MoveType.ROCKET) {
            val handCounts = hand.groupBy { it.rank }.mapValues { it.value.size }
            move.cards.map { it.rank }.distinct().forEach { r ->
                if ((handCounts[r] ?: 0) >= 4) c += 200
            }
        }
        // 多走牌奖励（三带一比三张略优）
        c -= move.cards.size
        return c
    }
}

/**
 * 手牌结构分解：顺子 / 连对 / 飞机 / 三张 / 对子 / 单牌（炸弹与王炸单独保留）
 */
object HandDecompose {

    data class Combo(val cards: List<Card>, val move: Move)

    fun decompose(hand: List<Card>): List<Combo> {
        if (hand.isEmpty()) return emptyList()
        val combos = ArrayList<Combo>()
        val pool = hand.toMutableList()
        fun counts() = pool.groupBy { it.rank }.mapValues { it.value.size }

        // 1. 王（双王成王炸，单王作单牌）
        val sj = pool.firstOrNull { it.rank == 16 }
        val bj = pool.firstOrNull { it.rank == 17 }
        if (sj != null && bj != null) {
            combos += Combo(listOf(sj, bj), Move(listOf(sj, bj), MoveType.ROCKET, 17, 1))
            pool.removeAll { it.rank >= 16 }
        } else if (sj != null) {
            combos += Combo(listOf(sj), Move(listOf(sj), MoveType.SINGLE, 16, 1))
            pool.remove(sj)
        } else if (bj != null) {
            combos += Combo(listOf(bj), Move(listOf(bj), MoveType.SINGLE, 17, 1))
            pool.remove(bj)
        }

        // 2. 炸弹
        counts().filter { it.value == 4 }.keys.sorted().forEach { r ->
            val cards = pool.filter { it.rank == r }
            combos += Combo(cards, Move(cards, MoveType.BOMB, r, 1))
            pool.removeAll { it.rank == r }
        }

        // 3. 顺子（纯单牌最长优先）
        val singleRanks = counts().filter { it.value == 1 }.keys.filter { it in 3..14 }.sorted()
        extractRuns(singleRanks, 5).forEach { run ->
            val cards = run.flatMap { r -> pool.filter { it.rank == r } }
            Move.of(cards)?.let { combos += Combo(cards, it) }
            run.forEach { r -> pool.removeAll { it.rank == r } }
        }

        // 4. 连对
        val pairRanks = counts().filter { it.value == 2 }.keys.filter { it in 3..14 }.sorted()
        extractRuns(pairRanks, 3).forEach { run ->
            val cards = run.flatMap { r -> pool.filter { it.rank == r } }
            Move.of(cards)?.let { combos += Combo(cards, it) }
            run.forEach { r -> pool.removeAll { it.rank == r } }
        }

        // 5. 飞机（连续三张）
        val trioRanks = counts().filter { it.value == 3 }.keys.filter { it in 3..14 }.sorted()
        extractRuns(trioRanks, 2).forEach { run ->
            val cards = run.flatMap { r -> pool.filter { it.rank == r } }
            Move.of(cards)?.let { combos += Combo(cards, it) }
            run.forEach { r -> pool.removeAll { it.rank == r } }
        }

        // 6. 剩余三张 / 对子 / 单牌
        counts().entries.sortedBy { it.key }.forEach { (r, n) ->
            val cards = pool.filter { it.rank == r }
            when (n) {
                3 -> combos += Combo(cards, Move(cards, MoveType.TRIO, r, 1))
                2 -> combos += Combo(cards, Move(cards, MoveType.PAIR, r, 1))
                1 -> combos += Combo(cards, Move(cards, MoveType.SINGLE, r, 1))
            }
        }
        return combos
    }

    /** 在有序 rank 列表中提取所有 ≥minLen 的连续段 */
    private fun extractRuns(sortedRanks: List<Int>, minLen: Int): List<List<Int>> {
        val runs = ArrayList<List<Int>>()
        if (sortedRanks.isEmpty()) return runs
        var start = 0
        for (i in 1..sortedRanks.size) {
            if (i == sortedRanks.size || sortedRanks[i] != sortedRanks[i - 1] + 1) {
                if (i - start >= minLen) runs += sortedRanks.subList(start, i).toList()
                start = i
            }
        }
        return runs
    }
}
