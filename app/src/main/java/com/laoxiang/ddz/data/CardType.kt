package com.laoxiang.ddz.data

/**
 * 斗地主牌型定义与识别 / 比牌规则（纯 Kotlin）
 *
 * 支持牌型：
 *  单牌、对子、三张、三带一、三带二、顺子(≥5)、连对(≥3)、
 *  飞机(≥2组连续三张)、飞机带单翅、飞机带对翅、
 *  四带二单、四带两对、炸弹(四张同牌)、王炸(双王)
 */
@kotlinx.serialization.Serializable
enum class MoveType(val label: String) {
    SINGLE("单牌"),
    PAIR("对子"),
    TRIO("三张"),
    TRIO_SINGLE("三带一"),
    TRIO_PAIR("三带二"),
    STRAIGHT("顺子"),
    PAIR_STRAIGHT("连对"),
    PLANE("飞机"),
    PLANE_SINGLE("飞机带单"),
    PLANE_PAIR("飞机带对"),
    FOUR_TWO_SINGLE("四带二"),
    FOUR_TWO_PAIR("四带两对"),
    BOMB("炸弹"),
    ROCKET("王炸");

    val isBombLike: Boolean get() = this == BOMB || this == ROCKET
}

@kotlinx.serialization.Serializable
data class Move(
    val cards: List<Card>,
    val type: MoveType,
    /** 比大小用的主牌值（三张/四张/飞机的牌体最大值、顺子/连对的最高位） */
    val mainRank: Int,
    /** 结构长度：顺子张数（牌数）、连对对数、飞机三张组数；其余为 1 */
    val length: Int
) {
    companion object {
        /** 一手牌的牌型识别；不合法返回 null */
        fun of(cards: List<Card>): Move? {
            if (cards.isEmpty() || cards.size > 20) return null
            val cs = cards.sorted()
            val n = cs.size
            val counts = cs.groupBy { it.rank }.mapValues { it.value.size }

            // ---- 王炸 ----
            if (n == 2 && counts.keys.sorted() == listOf(16, 17)) {
                return Move(cards, MoveType.ROCKET, 17, 1)
            }

            // ---- 炸弹 ----
            if (n == 4 && counts.size == 1) {
                return Move(cards, MoveType.BOMB, cs[0].rank, 1)
            }

            // ---- 单牌 / 对子 / 三张 ----
            if (counts.size == 1) {
                val r = cs[0].rank
                return when (n) {
                    1 -> Move(cards, MoveType.SINGLE, r, 1)
                    2 -> Move(cards, MoveType.PAIR, r, 1)
                    3 -> Move(cards, MoveType.TRIO, r, 1)
                    else -> null // 4 张炸弹已在上面处理；>4 同牌不可能
                }
            }

            // ---- 三带一 / 三带二 ----
            val trioRanks = counts.filter { it.value == 3 }.keys
            if (trioRanks.size == 1 && n in 4..5) {
                val body = trioRanks.first()
                val rest = counts.filterKeys { it != body }
                return when (n) {
                    4 -> Move(cards, MoveType.TRIO_SINGLE, body, 1)          // 3+1
                    5 -> if (rest.values.sum() == 2 && rest.size == 1)
                        Move(cards, MoveType.TRIO_PAIR, body, 1)              // 3+2
                        else null
                    else -> null
                }
            }

            // ---- 四带二 ----
            val quadRanks = counts.filter { it.value == 4 }.keys
            if (quadRanks.size == 1) {
                val body = quadRanks.first()
                val restCount = n - 4
                val rest = counts.filterKeys { it != body }
                val restIsTwoSingles = restCount == 2                        // 两张任意单牌（可成对）
                val restIsTwoPairs = restCount == 4 && rest.size == 2 && rest.values.all { it == 2 }
                return when {
                    restCount == 0 -> Move(cards, MoveType.BOMB, body, 1)     // 已处理，防御
                    restIsTwoSingles -> Move(cards, MoveType.FOUR_TWO_SINGLE, body, 1)
                    restIsTwoPairs -> Move(cards, MoveType.FOUR_TWO_PAIR, body, 1)
                    else -> null
                }
            }

            // ---- 顺子（≥5 张连续单牌，最大到 A） ----
            if (n >= 5 && counts.size == n && runStraight(cs)) {
                val maxR = cs.maxOf { it.rank }
                if (maxR <= 14) return Move(cards, MoveType.STRAIGHT, maxR, n)
            }

            // ---- 连对（≥3 对连续，最大到 A） ----
            if (n >= 6 && n % 2 == 0 && counts.values.all { it == 2 }) {
                val rs = counts.keys.sorted()
                if (rs.max() <= 14 && rs.max() - rs.min() == rs.size - 1) {
                    return Move(cards, MoveType.PAIR_STRAIGHT, rs.max(), rs.size)
                }
            }

            // ---- 飞机（≥2 组连续三张，可带同数量单牌或对牌） ----
            parsePlane(cs, counts)?.let { return it }

            return null
        }

        /** 判断全为单牌时是否构成连续（3..A） */
        private fun runStraight(cs: List<Card>): Boolean {
            val rs = cs.map { it.rank }.sorted()
            return rs.max() <= 14 && rs.zipWithNext().all { (a, b) -> b - a == 1 }
        }

        /** 飞机识别：优先最长连续三张体，剩余作为翅膀（单或对） */
        private fun parsePlane(cs: List<Card>, counts: Map<Int, Int>): Move? {
            val trioRanks = counts.filter { it.value == 3 }.keys.sorted()
            if (trioRanks.size < 2) return null

            // 尝试从最长到最短的连续三张序列
            var best: IntRange? = null
            var runStart = trioRanks.first()
            var runLen = 1
            val runs = ArrayList<IntRange>()
            for (i in 1 until trioRanks.size) {
                if (trioRanks[i] == trioRanks[i - 1] + 1 && trioRanks[i] <= 14) {
                    runLen++
                } else {
                    if (runLen >= 2) runs += IntRange(runStart, trioRanks[i - 1])
                    runStart = trioRanks[i]; runLen = 1
                }
            }
            if (runLen >= 2 && trioRanks.last() <= 14) runs += IntRange(runStart, trioRanks.last())
            if (runs.isEmpty()) return null

            for (range in runs.sortedByDescending { it.last() }) {
                val k = range.last - range.first + 1
                if (range.last > 14) continue
                val rest = cs.filter { it.rank !in range }
                when (rest.size) {
                    0 -> return Move(cs, MoveType.PLANE, range.last, k)
                    k -> return Move(cs, MoveType.PLANE_SINGLE, range.last, k)
                    2 * k -> {
                        val rc = rest.groupBy { it.rank }.mapValues { it.value.size }
                        if (rc.size == k && rc.values.all { it == 2 }) {
                            return Move(cs, MoveType.PLANE_PAIR, range.last, k)
                        }
                    }
                }
            }
            return null
        }
    }

    /** 本手牌能否压过 [last]（null 表示桌面为空，任何合法牌都能出） */
    fun beats(last: Move?): Boolean {
        last ?: return true
        return CardRules.beats(this, last)
    }
}

object CardRules {

    /** 比牌规则核心 */
    fun beats(candidate: Move, last: Move): Boolean {
        // 王炸通吃（王炸压王炸不存在，全副牌只有一副王）
        if (candidate.type == MoveType.ROCKET) return last.type != MoveType.ROCKET
        if (last.type == MoveType.ROCKET) return false

        // 炸弹压非炸
        if (candidate.type == MoveType.BOMB) {
            if (last.type != MoveType.BOMB) return true
            return candidate.mainRank > last.mainRank
        }
        if (last.type == MoveType.BOMB) return false

        // 同型同长比主牌
        if (candidate.type != last.type) return false
        if (candidate.length != last.length) return false
        return candidate.mainRank > last.mainRank
    }

    /**
     * 快速判断一组牌是否合法且能压过上一手
     */
    fun canPlay(cards: List<Card>, last: Move?): Boolean {
        val mv = Move.of(cards) ?: return false
        return mv.beats(last)
    }
}
