package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 麻将 AI（三档难度）：
 *  简单 —— 偏随机，按「孤张→字牌→幺九」粗略丢牌，碰杠看心情
 *  中等 —— 以向听数为主：丢掉后向听最小的牌，遇碰杠看改善
 *  困难 —— 向听 + 进张数（能听多少张）双重评估，定缺换三张择优
 */
class MjAi(
    private val seat: Int,
    private val level: AiLevel,
    private val mode: MjMode,
    seed: Long? = null
) {
    private val rng = seed?.let { Random(it) } ?: Random.Default

    private val isNumberTile: (MjTile) -> Boolean = { it.suit.isNumber }

    // ------------------------------------------------ 定缺

    fun chooseDingque(hand: List<MjTile>): Int {
        if (level == AiLevel.EASY && rng.nextInt(100) < 35) return rng.nextInt(3)
        val cnt = IntArray(3)
        hand.forEach { if (it.suit.isNumber) cnt[it.code / 9]++ }
        // 选张数最少的门；同数选「孤张多」的门（散牌好换出）
        var best = 0
        var bestScore = Int.MAX_VALUE
        for (s in 0 until 3) {
            val tiles = hand.filter { it.code / 9 == s }
            var isolated = 0
            tiles.forEach { t ->
                val friends = tiles.count { it != t && kotlin.math.abs(it.code - t.code) <= 2 }
                if (friends == 0) isolated++
            }
            val score = cnt[s] * 10 - isolated
            if (score < bestScore) { bestScore = score; best = s }
        }
        return best
    }

    // ------------------------------------------------ 换三张

    /** 交给 [chooseDingque] 的结果：优先换出缺门的 3 张（孤张优先） */
    fun chooseSwap(hand: List<MjTile>, dingque: Int): List<Int> {
        var suit = dingque
        var tiles = hand.filter { it.code / 9 == suit }
        if (tiles.size < 3) {
            // 兜底：找张数 ≥3 的最差花色
            for (s in 0 until 3) {
                val t = hand.filter { it.code / 9 == s }
                if (t.size >= 3) { suit = s; tiles = t; if (t.size >= 3) break }
            }
        }
        val scored = tiles.map { t -> t to usefulness(t, hand) }.sortedBy { it.second }
        return scored.take(3).map { it.first.id }
    }

    // ------------------------------------------------ 自摸后的动作

    data class SelfAction(
        val type: String,   // DISCARD / GANG_AN / GANG_BU / HU
        val code: Int = -1,
        val tileId: Int = -1
    )

    fun chooseSelfAction(
        hand: List<MjTile>,
        melds: List<MjMeld>,
        dingque: Int,
        canHu: Boolean,
        anGangCodes: List<Int>,
        buGangTiles: List<MjTile>
    ): SelfAction {
        if (canHu && (level != AiLevel.EASY || rng.nextInt(100) < 92)) return SelfAction("HU")
        if (anGangCodes.isNotEmpty() && rng.nextInt(100) < 96) {
            return SelfAction("GANG_AN", code = anGangCodes.first())
        }
        if (buGangTiles.isNotEmpty() && rng.nextInt(100) < 96) {
            return SelfAction("GANG_BU", tileId = buGangTiles.first().id)
        }
        val t = chooseDiscard(hand, melds, dingque)
        return SelfAction("DISCARD", tileId = t.id)
    }

    // ------------------------------------------------ 弃牌

    fun chooseDiscard(hand: List<MjTile>, melds: List<MjMeld>, dingque: Int): MjTile {
        val pool = hand.filter { !isLaizi(it) }.ifEmpty { hand }
        if (pool.size == 1) return pool.first()
        // 川麻有缺必打缺（引擎亦强制）
        if (mode == MjMode.SICHUAN && dingque in 0..2) {
            val que = pool.filter { it.code / 9 == dingque }
            if (que.isNotEmpty()) {
                return que.minByOrNull { usefulness(it, hand) } ?: que.first()
            }
        }
        val meldsMade = melds.size
        val candidates = pool.distinctBy { it.code }

        // 先按丢牌后向听分组
        data class Cand(val tile: MjTile, val shanten: Int, val ukeire: Int, val useless: Double)

        val evaluated = candidates.map { t ->
            val rest = hand.filter { it.id != t.id }
            val cnt = MjRules.countsOf(rest.filter { !isLaizi(it) })
            val jokers = rest.count { isLaizi(it) }
            val st = MjRules.shanten(cnt, jokers)
            Cand(t, st, 0, uselessness(t, rest))
        }
        val bestSt = evaluated.minOf { it.shanten }
        val finalists = evaluated.filter { it.shanten == bestSt }

        if (level == AiLevel.EASY) {
            // 简单：一半概率直接挑最没用的
            return if (rng.nextInt(100) < 50) {
                finalists.maxByOrNull { it.useless }?.tile ?: finalists.first().tile
            } else {
                evaluated.maxByOrNull { it.useless }?.tile ?: pool.first()
            }
        }
        if (finalists.size == 1 || level == AiLevel.MEDIUM) {
            // 中等：向听最小里挑最没用；并列再随机
            val top = finalists.groupBy { it.useless }.maxByOrNull { it.key }!!.value
            return top.random(rng).tile
        }
        // 困难：对并列最优者数进张（加一张能降低向听的种类数）
        val withUke = finalists.map { c ->
            val rest = hand.filter { it.id != c.tile.id }
            val base = MjRules.countsOf(rest.filter { !isLaizi(it) })
            val jokers = rest.count { isLaizi(it) }
            var u = 0
            val range = if (mode.withZi) 0..33 else 0..26
            for (code in range) {
                if (base[code] >= 4) continue
                base[code]++
                if (MjRules.shanten(base, jokers) < c.shanten) u++
                base[code]--
            }
            Cand(c.tile, c.shanten, u, c.useless)
        }
        val maxU = withUke.maxOf { it.ukeire }
        val top = withUke.filter { it.ukeire >= maxU }
        return top.minByOrNull { it.useless }?.tile ?: top.first().tile
    }

    private fun isLaizi(t: MjTile): Boolean =
        mode.jokerCode >= 0 && t.code == mode.jokerCode

    // ------------------------------------------------ 宣告（吃/碰/杠/胡/过）

    /**
     * @return 选中的宣告或 null（过）
     */
    fun chooseClaim(
        opts: List<MjClaimOpt>,
        hand: List<MjTile>,
        melds: List<MjMeld>,
        dingque: Int,
        claimTile: MjTile
    ): MjClaimOpt? {
        if (opts.isEmpty()) return null
        // 胡必胡
        opts.firstOrNull { it.kind == "HU" }?.let { return it }
        if (level == AiLevel.EASY) {
            // 简单：爱碰不碰看心情，不吃
            val peng = opts.firstOrNull { it.kind == "PENG" }
            if (peng != null && rng.nextInt(100) < 55) return peng
            val gang = opts.firstOrNull { it.kind == "GANG" }
            if (gang != null && rng.nextInt(100) < 80) return gang
            return null
        }
        val gang = opts.firstOrNull { it.kind == "GANG" }
        if (gang != null) return gang   // 杠有即付/摸牌收益，直接杠
        val base = MjRules.countsOf(hand.filter { !isLaizi(it) })
        val jokers = hand.count { isLaizi(it) }
        val curSt = MjRules.shanten(base, jokers, melds.size)

        val peng = opts.firstOrNull { it.kind == "PENG" }
        if (peng != null) {
            val after = hand.filter { it.code != claimTile.code }
            val cnt = MjRules.countsOf(after.filter { !isLaizi(it) })
            val j2 = after.count { isLaizi(it) }
            val stAfter = bestDiscardShanten(cnt, j2, melds.size + 1)
            // 碰须严格改善向听（原 ≤ 会让 AI 见牌就碰，牌局呈脚本化观感）
            if (stAfter < curSt) return peng
        }
        val chiOpts = opts.filter { it.kind == "CHI" }
        if (chiOpts.isNotEmpty()) {
            var bestOpt: MjClaimOpt? = null
            var bestSt = curSt + 1
            for (o in chiOpts) {
                val used = chiUsedCodes(o.chiMid, claimTile.code)
                val after = hand.filter { h -> h.code !in used }
                val cnt = MjRules.countsOf(after.filter { !isLaizi(it) })
                val j2 = after.count { isLaizi(it) }
                val stAfter = bestDiscardShanten(cnt, j2, melds.size + 1)
                if (stAfter < bestSt) { bestSt = stAfter; bestOpt = o }
            }
            if (bestOpt != null && bestSt < curSt) return bestOpt
        }
        return null
    }

    private fun chiUsedCodes(mid: Int, claimCode: Int): List<Int> {
        // 吃进的 tile + 手里两张：mid 为中间 code
        val seq = listOf(mid - 1, mid, mid + 1)
        return seq - claimCode
    }

    /** 丢一张后的最小向听（含副露数） */
    private fun bestDiscardShanten(counts: IntArray, jokers: Int, meldsMade: Int): Int {
        // counts 为碰/吃后待弃牌型 → 枚举每种丢法
        var best = 8
        for (c in 0..33) {
            if (counts[c] == 0) continue
            counts[c]--
            val st = MjRules.shanten(counts, jokers, meldsMade)
            counts[c]++
            if (st < best) best = st
        }
        return best
    }

    // ------------------------------------------------ 工具

    /** 孤张无用度：越大越该丢（字 > 幺九 > 边张 > 中张；有成组意向减分） */
    private fun uselessness(t: MjTile, hand: List<MjTile>): Double {
        if (t.suit == MjSuit.ZI) return 30.0 + t.num
        val same = hand.count { it.code == t.code }
        var u = when (t.num) {
            1, 9 -> 12.0
            2, 8 -> 10.0
            3, 7 -> 8.0
            else -> 6.0
        }
        if (same >= 2) u -= 8.0 * (same - 1)
        val near = hand.count { it.code != t.code && it.code / 9 == t.code / 9 && kotlin.math.abs(it.code - t.code) <= 2 }
        u -= near * 2.5
        return u + rng.nextDouble() * 0.6
    }

    /** 组合价值（定缺/换三张排序用）：越小越没用 */
    private fun usefulness(t: MjTile, hand: List<MjTile>): Double {
        var u = uselessness(t, hand)
        if (hand.count { it.code == t.code } >= 3) u -= 20.0   // 刻子潜力别换
        return u
    }
}
